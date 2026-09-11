package com.heapy.health.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 원천 기록과 HEAPY 자체 계산 결과를 분리한다. @author 김진우 */
public final class LifestyleScore {
    private LifestyleScore() { }
    public record Session(Instant start, Instant end, Double minutes) { }
    public record Steps(LocalDate date, Double value) { }
    public record Bmi(Instant measuredAt, Double value) { }
    public record Input(LocalDate birthDate, List<Session> sleep, List<Session> exercise,
                        List<Steps> steps, List<Bmi> bmi, boolean truncated) { }
    public record Component(Double score, int recordedDays, int requiredDays) { }
    public record Day(LocalDate date, Integer score, Component sleep, Component activity,
                      Double bmiScore, LocalDate bmiDate, List<String> reasons) { }
    public record Report(String policyVersion, String timezone, HealthPeriod period,
                         Day latest, List<Day> points) { }
}
