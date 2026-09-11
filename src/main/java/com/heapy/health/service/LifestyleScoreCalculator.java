package com.heapy.health.service;

import com.heapy.health.model.HealthPeriod;
import com.heapy.health.model.LifestyleScore.Bmi;
import com.heapy.health.model.LifestyleScore.Component;
import com.heapy.health.model.LifestyleScore.Day;
import com.heapy.health.model.LifestyleScore.Input;
import com.heapy.health.model.LifestyleScore.Report;
import com.heapy.health.model.LifestyleScore.Session;
import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 진단 목적이 아닌 자체 생활습관 기준 v1. 원천 점수를 변경하지 않는다. @author 김진우 */
public final class LifestyleScoreCalculator {
    public static final String VERSION = "heapy-lifestyle-v1";
    private LifestyleScoreCalculator() { }

    public static Report calculate(Input input, HealthPeriod period) {
        Map<LocalDate, Double> sleep = dailyMinutes(input.sleep());
        Map<LocalDate, Double> exercise = dailyMinutes(input.exercise());
        Map<LocalDate, Double> activity = new HashMap<>();
        input.steps().stream().filter(s -> valid(s.value())).forEach(s ->
                activity.merge(s.date(), Math.min(s.value() / 8000, 1) * 100, Math::max));
        exercise.forEach((day, minutes) -> activity.merge(day, Math.min(minutes / 30, 1) * 100, Math::max));
        sleep.replaceAll((day, minutes) -> sleepScore(minutes / 60));
        List<Bmi> bmi = input.bmi().stream().filter(b -> valid(b.value()) && b.value() > 0)
                .sorted(Comparator.comparing(Bmi::measuredAt)).toList();
        List<Day> points = new ArrayList<>();
        for (LocalDate day = period.from(); !day.isAfter(period.to()); day = day.plusDays(1)) {
            List<String> reasons = new ArrayList<>();
            Component s = average(sleep, day, 7, 5), a = average(activity, day, 14, 7);
            if (s.score() == null) reasons.add("sleep_insufficient");
            if (a.score() == null) reasons.add("activity_insufficient");
            Bmi latest = null;
            for (Bmi record : bmi) {
                if (record.measuredAt().atZone(HealthPeriod.ZONE).toLocalDate().isAfter(day)) break;
                latest = record;
            }
            LocalDate bmiDate = latest == null ? null : latest.measuredAt().atZone(HealthPeriod.ZONE).toLocalDate();
            Double b = latest == null ? null : clamp(100 - 10 * Math.max(18.5 - latest.value(), latest.value() - 25));
            if (latest == null) reasons.add("bmi_missing");
            else if (bmiDate.isBefore(day.minusDays(89))) { b = null; reasons.add("bmi_stale"); }
            if (input.birthDate() == null) reasons.add("age_unavailable");
            else if (input.birthDate().plusYears(20).isAfter(day)) reasons.add("age_not_supported");
            if (input.truncated()) reasons.add("data_limit_exceeded");
            Integer total = reasons.isEmpty() ? roundedTotal(s.score(), a.score(), b) : null;
            points.add(new Day(day, total, s, a, b, bmiDate, List.copyOf(reasons)));
        }
        return new Report(VERSION, HealthPeriod.ZONE.getId(), period, points.getLast(), List.copyOf(points));
    }

    public static double sleepScore(double hours) {
        return clamp(hours < 7 ? 100 - (7 - hours) * 25 : hours > 9 ? 100 - (hours - 9) * 15 : 100);
    }

    private static Component average(Map<LocalDate, Double> days, LocalDate end, int window, int minimum) {
        double sum = 0;
        int count = 0;
        for (int i = 0; i < window; i++) {
            Double score = days.get(end.minusDays(i));
            if (score != null) { sum += score; count++; }
        }
        return new Component(count >= minimum ? sum / count : null, count, minimum);
    }

    /** 겹친 세션은 시간 구간을 추정해 더하지 않고 가장 긴 실제 기록 하나를 사용한다. */
    static Map<LocalDate, Double> dailyMinutes(List<Session> records) {
        List<Session> sorted = records.stream().filter(s -> s.start() != null && s.end() != null
                && s.end().isAfter(s.start()) && valid(s.minutes())
                && Duration.between(s.start(), s.end()).toMinutes() <= 1440
                && s.minutes() <= Duration.between(s.start(), s.end()).toSeconds() / 60.0 + 1)
                .sorted(Comparator.comparing(Session::start).thenComparing(Session::end)).toList();
        Map<LocalDate, Double> days = new HashMap<>();
        Session chosen = null;
        Instant groupEnd = null;
        for (Session session : sorted) {
            if (chosen == null || !session.start().isBefore(groupEnd)) {
                if (chosen != null) add(days, chosen);
                chosen = session;
                groupEnd = session.end();
            } else {
                if (session.minutes() > chosen.minutes()
                        || (session.minutes().equals(chosen.minutes()) && session.end().isAfter(chosen.end()))) chosen = session;
                if (session.end().isAfter(groupEnd)) groupEnd = session.end();
            }
        }
        if (chosen != null) add(days, chosen);
        return days;
    }

    private static void add(Map<LocalDate, Double> days, Session session) {
        days.merge(session.end().atZone(HealthPeriod.ZONE).toLocalDate(), session.minutes(), Double::sum);
    }
    private static boolean valid(Double value) { return value != null && Double.isFinite(value) && value >= 0; }
    private static int roundedTotal(double sleep, double activity, double bmi) {
        // 정수 반올림 경계에서 부동소수점 연산 오차만 제거한다.
        return BigDecimal.valueOf(sleep * .45 + activity * .45 + bmi * .10)
                .setScale(9, RoundingMode.HALF_UP).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }
    private static double clamp(double value) { return Math.max(0, Math.min(100, value)); }
}
