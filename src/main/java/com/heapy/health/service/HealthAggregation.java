package com.heapy.health.service;

import com.heapy.health.model.HealthData.Point;
import com.heapy.health.model.HealthData.Record;
import com.heapy.health.model.HealthData.Series;
import com.heapy.health.model.HealthMetric;
import com.heapy.health.model.HealthMetric.Field;
import com.heapy.health.model.HealthPeriod;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 결측을 제외하고 일별 집계 후 기록일 평균을 계산한다. @author 김진우 */
public final class HealthAggregation {
    private HealthAggregation() { }

    public static List<Series> aggregate(HealthMetric metric, List<Record> records, HealthPeriod period) {
        List<Series> result = new ArrayList<>();
        for (Field field : metric.fields()) {
            if ("blood_glucose_mg_dl".equals(field.key())) {
                result.add(series(new Field("glucose_fasting", "공복 혈당", field.unit(), false), field.key(),
                        records.stream().filter(r -> Boolean.TRUE.equals(r.values().get("is_fasting"))).toList(), period));
                result.add(series(new Field("glucose_nonfasting", "비공복 혈당", field.unit(), false), field.key(),
                        records.stream().filter(r -> Boolean.FALSE.equals(r.values().get("is_fasting"))).toList(), period));
                result.add(series(new Field("glucose_unknown", "공복 여부 미기록", field.unit(), false), field.key(),
                        records.stream().filter(r -> r.values().get("is_fasting") == null).toList(), period));
            } else result.add(series(field, field.key(), records, period));
        }
        return List.copyOf(result);
    }

    private static Series series(Field field, String source, List<Record> records, HealthPeriod period) {
        Map<LocalDate, List<BigDecimal>> byDay = new TreeMap<>();
        for (Record record : records) {
            Object value = record.values().get(source);
            if (!(value instanceof Number) || record.date().isBefore(period.from()) || record.date().isAfter(period.to())) continue;
            byDay.computeIfAbsent(record.date(), key -> new ArrayList<>()).add(new BigDecimal(value.toString()));
        }
        Map<LocalDate, List<BigDecimal>> buckets = new TreeMap<>();
        byDay.forEach((day, values) -> {
            BigDecimal daily = sum(values);
            if (!field.dailySum()) daily = daily.divide(BigDecimal.valueOf(values.size()), 8, RoundingMode.HALF_UP);
            buckets.computeIfAbsent(period.bucket(day), key -> new ArrayList<>()).add(daily);
        });
        List<Point> points = buckets.entrySet().stream().map(entry -> new Point(entry.getKey(),
                sum(entry.getValue()).divide(BigDecimal.valueOf(entry.getValue().size()), 4, RoundingMode.HALF_UP),
                entry.getValue().size(), period.span(entry.getKey()), period.coveredSpan(entry.getKey()))).toList();
        return new Series(field.key(), field.label(), field.unit(), field.dailySum() ? "sum" : "mean", points);
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
