package com.heapy.health.model;

import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/** 한국 날짜와 데모의 기간별 구간을 정의한다. @author 김진우 */
public record HealthPeriod(String code, LocalDate from, LocalDate to, String aggregation) {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    public static HealthPeriod of(String code, LocalDate baseDate, String aggregation) {
        int days = switch (code) {
            case "7d" -> 7;
            case "30d" -> 30;
            case "90d" -> 90;
            case "180d" -> 180;
            case "1y" -> 365;
            default -> throw new HeapyException(ErrorCode.INVALID_INPUT);
        };
        LocalDate end = baseDate == null ? LocalDate.now(ZONE) : baseDate;
        if (end.isAfter(LocalDate.now(ZONE))) throw new HeapyException(ErrorCode.INVALID_INPUT);
        String mode = aggregation == null ? days >= 180 ? "month" : days >= 90 ? "week" : "day" : aggregation;
        if (!Set.of("day", "week", "month", "raw").contains(mode)) throw new HeapyException(ErrorCode.INVALID_INPUT);
        return new HealthPeriod(code, end.minusDays(days - 1), end, mode);
    }

    public LocalDate bucket(LocalDate date) {
        return switch (aggregation) {
            case "month" -> date.withDayOfMonth(1);
            case "week" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            default -> date;
        };
    }

    public int span(LocalDate bucket) {
        return switch (aggregation) {
            case "month" -> bucket.lengthOfMonth();
            case "week" -> 7;
            default -> 1;
        };
    }

    public int coveredSpan(LocalDate bucket) {
        LocalDate start = bucket.isBefore(from) ? from : bucket;
        LocalDate end = bucket.plusDays(span(bucket) - 1);
        if (end.isAfter(to)) end = to;
        return Math.toIntExact(ChronoUnit.DAYS.between(start, end) + 1);
    }
}
