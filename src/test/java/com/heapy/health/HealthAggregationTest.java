package com.heapy.health;

import static org.assertj.core.api.Assertions.assertThat;
import com.heapy.health.model.HealthData.Record;
import com.heapy.health.model.HealthMetric;
import com.heapy.health.model.HealthPeriod;
import com.heapy.health.service.HealthAggregation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 합성 건강값으로 집계와 결측 경계를 검증한다. @author 김진우 */
class HealthAggregationTest {
    private Record row(String date, Map<String, Object> values) {
        LocalDate day = LocalDate.parse(date);
        return new Record(UUID.randomUUID(), day, day.atStartOfDay(HealthPeriod.ZONE).toInstant(),
                "manual", true, true, "1", values);
    }

    @Test
    void 물은_일합계_후_기록일평균이며_미기록을_영으로_채우지_않는다() {
        var rows = List.of(row("2026-08-01", Map.of("amount_ml", 100)),
                row("2026-08-01", Map.of("amount_ml", 300)), row("2026-08-03", Map.of("amount_ml", 800)));
        var p = HealthPeriod.of("180d", LocalDate.parse("2026-08-31"), null);
        var point = HealthAggregation.aggregate(HealthMetric.WATER, rows, p).getFirst().points().getFirst();
        assertThat(point.value()).isEqualByComparingTo("600");
        assertThat(point.recordedDays()).isEqualTo(2);
        assertThat(point.spanDays()).isEqualTo(31);
    }

    @Test
    void 혈압은_측정횟수가_많은날에_가중하지_않는다() {
        var rows = List.of(row("2026-08-01", Map.of("systolic_mmhg", 100)),
                row("2026-08-01", Map.of("systolic_mmhg", 120)), row("2026-08-02", Map.of("systolic_mmhg", 130)));
        var series = HealthAggregation.aggregate(HealthMetric.BIO, rows,
                HealthPeriod.of("90d", LocalDate.parse("2026-08-03"), null));
        assertThat(series.stream().filter(s -> s.key().equals("systolic_mmhg")).findFirst().orElseThrow()
                .points().getFirst().value()).isEqualByComparingTo(new BigDecimal("120"));
    }

    @Test
    void 공복_비공복_미기록은_서로_섞지_않는다() {
        var rows = List.of(row("2026-08-01", Map.of("blood_glucose_mg_dl", 90, "is_fasting", true)),
                row("2026-08-01", Map.of("blood_glucose_mg_dl", 140, "is_fasting", false)),
                row("2026-08-01", Map.of("blood_glucose_mg_dl", 110)));
        var values = HealthAggregation.aggregate(HealthMetric.BIO, rows,
                HealthPeriod.of("7d", LocalDate.parse("2026-08-01"), null)).stream()
                .filter(s -> s.key().startsWith("glucose_")).map(s -> s.points().getFirst().value()).toList();
        assertThat(values).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("90"), new BigDecimal("140"), new BigDecimal("110"));
    }

    @Test
    void 구간_일수는_표시범위와_전체구간을_구분한다() {
        var period = HealthPeriod.of("7d", LocalDate.parse("2026-09-02"), "month");
        assertThat(period.coveredSpan(LocalDate.parse("2026-08-01"))).isEqualTo(5);
        assertThat(period.coveredSpan(LocalDate.parse("2026-09-01"))).isEqualTo(2);
    }
}
