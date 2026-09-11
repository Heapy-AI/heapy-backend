package com.heapy.home.service;

import com.heapy.health.model.HealthData.Page;
import com.heapy.health.model.HealthData.Point;
import com.heapy.health.service.HealthQueryService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 내 건강과 동일한 일별 집계를 홈 카드에 사용한다. @author 김진우 */
@Service
public class HomeCards {
    private final HealthQueryService health;
    private final Clock clock;

    public HomeCards(HealthQueryService health, Clock clock) {
        this.health = health;
        this.clock = clock;
    }

    public Data find(UUID user) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        Map<String, Value> metrics = new LinkedHashMap<>();
        Map<String, List<Point>> trends = new LinkedHashMap<>();
        Page activity = page(user, "activity", today);
        Page sleep = page(user, "sleep", today);
        Page exercise = page(user, "exercise", today);
        Page bio = page(user, "bio", today);
        add(metrics, trends, "steps", "steps", activity, today);
        add(metrics, trends, "sleep", "total_sleep_minutes", sleep, today);
        add(metrics, trends, "exercise", "duration_seconds", exercise, today);
        add(metrics, trends, "heart", "heart_rate_bpm", bio, today);
        if (!bio.dataTruncated()) {
            bio.records().stream().filter(r -> r.values().get("systolic_mmhg") instanceof Number
                    && r.values().get("diastolic_mmhg") instanceof Number).findFirst().ifPresent(r ->
                    metrics.put("pressure", new Value(r.date(), new BigDecimal(r.values().get("systolic_mmhg").toString()),
                            new BigDecimal(r.values().get("diastolic_mmhg").toString()))));
        }
        if (!exercise.dataTruncated()) {
            long count = exercise.records().stream().filter(r -> !r.date().isBefore(today.minusDays(6))).count();
            if (count > 0) metrics.put("count", new Value(today, BigDecimal.valueOf(count), null));
        }
        return new Data(today, metrics, trends, activity.dataTruncated() || sleep.dataTruncated()
                || exercise.dataTruncated() || bio.dataTruncated());
    }

    private Page page(UUID user, String metric, LocalDate today) {
        return health.find(user, metric, "30d", today, "day", null, 200, null);
    }

    private void add(Map<String, Value> metrics, Map<String, List<Point>> trends,
                     String id, String key, Page page, LocalDate today) {
        List<Point> points = page.series().stream().filter(s -> s.key().equals(key))
                .findFirst().map(s -> s.points()).orElse(List.of());
        if (!points.isEmpty()) {
            Point last = points.getLast();
            metrics.put(id, new Value(last.date(), last.value(), null));
        }
        trends.put(id, points.stream().filter(p -> !p.date().isBefore(today.minusDays(14))
                && p.date().isBefore(today)).toList());
    }

    public record Value(LocalDate date, BigDecimal value, BigDecimal secondary) { }
    public record Data(LocalDate date, Map<String, Value> metrics,
                       Map<String, List<Point>> trends, boolean dataTruncated) { }
}
