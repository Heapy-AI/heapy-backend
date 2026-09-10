package com.heapy.health.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 건강 원천값과 그래프의 공개 응답이다. @author 김진우 */
public final class HealthData {
    private HealthData() { }
    public record Record(UUID recordId, LocalDate date, Instant measuredAt, String source,
                         boolean editable, boolean deletable, String recordVersion, Map<String, Object> values) { }
    public record Point(LocalDate date, BigDecimal value, int recordedDays, int spanDays, int coveredDays) { }
    public record Series(String key, String label, String unit, String dailyAggregation,
                         List<Point> points) { }
    public record Page(String metric, HealthPeriod period, String timezone, List<Record> records,
                       List<Series> series, boolean dataTruncated, String nextCursor) { }
}
