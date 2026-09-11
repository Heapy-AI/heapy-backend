package com.heapy.health.service;

import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import com.heapy.health.model.HealthMetric;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** 입력 필드와 단위를 허용 목록으로 검증한다. @author 김진우 */
public final class HealthRecordInput {
    private HealthRecordInput() { }

    public static Map<String, Object> manual(HealthMetric metric, JsonNode body) {
        if (body == null || !body.isObject()) invalid();
        Map<String, Object> values = new LinkedHashMap<>();
        Set<String> allowed;
        switch (metric) {
            case WATER -> {
                allowed = Set.of("consumedAt", "amountMl", "recordVersion");
                values.put("consumed_at", time(body, "consumedAt"));
                values.put("amount_ml", number(body, "amountMl", true, true));
            }
            case SLEEP -> {
                allowed = Set.of("startAt", "endAt", "totalSleepMinutes");
                Timestamp start = time(body, "startAt"), end = time(body, "endAt");
                BigDecimal minutes = number(body, "totalSleepMinutes", true, false);
                long duration = Duration.between(start.toInstant(), end.toInstant()).toMinutes();
                if (duration <= 0 || duration > 2880 || minutes.compareTo(BigDecimal.valueOf(duration)) > 0
                        || minutes.stripTrailingZeros().scale() > 0) invalid();
                values.put("start_at", start); values.put("end_at", end); values.put("total_sleep_minutes", minutes.intValueExact());
            }
            case BIO -> {
                String type = body.path("bioType").asText("");
                values.put("bio_type", type); values.put("measured_at", time(body, "measuredAt"));
                allowed = switch (type) {
                    case "blood_pressure" -> Set.of("bioType", "measuredAt", "systolicMmhg", "diastolicMmhg", "pulseBpm");
                    case "blood_glucose" -> Set.of("bioType", "measuredAt", "bloodGlucoseMgDl", "isFasting", "insulinMicroIuMl");
                    case "body_composition" -> Set.of("bioType", "measuredAt", "weightKg", "heightCm", "bodyFatPercent", "skeletalMuscleKg");
                    default -> throw new HeapyException(ErrorCode.INVALID_INPUT);
                };
                if ("blood_pressure".equals(type)) {
                    BigDecimal systolic = number(body, "systolicMmhg", true, true), diastolic = number(body, "diastolicMmhg", true, true);
                    if (systolic.compareTo(diastolic) <= 0) invalid();
                    values.put("systolic_mmhg", systolic); values.put("diastolic_mmhg", diastolic);
                    values.put("pulse_bpm", number(body, "pulseBpm", false, true));
                } else if ("blood_glucose".equals(type)) {
                    values.put("blood_glucose_mg_dl", number(body, "bloodGlucoseMgDl", true, true));
                    JsonNode fasting = body.get("isFasting");
                    if (fasting != null && !fasting.isNull() && !fasting.isBoolean()) invalid();
                    values.put("is_fasting", fasting == null || fasting.isNull() ? null : fasting.asBoolean());
                    values.put("insulin_micro_iu_ml", number(body, "insulinMicroIuMl", false, false));
                } else {
                    BigDecimal weight = number(body, "weightKg", true, true), height = number(body, "heightCm", false, true);
                    values.put("weight_kg", weight); values.put("height_cm", height);
                    values.put("bmi_value", height == null ? null : weight.multiply(BigDecimal.valueOf(10000))
                            .divide(height.multiply(height), 2, RoundingMode.HALF_UP));
                    BigDecimal fat = number(body, "bodyFatPercent", false, false);
                    if (fat != null && fat.compareTo(BigDecimal.valueOf(100)) > 0) invalid();
                    values.put("body_fat_percent", fat); values.put("skeletal_muscle_kg", number(body, "skeletalMuscleKg", false, false));
                }
            }
            default -> throw new HeapyException(ErrorCode.INVALID_INPUT);
        }
        for (String field : body.propertyNames()) if (!allowed.contains(field)) invalid();
        return values;
    }

    private static Timestamp time(JsonNode body, String field) {
        try {
            if (!body.path(field).isTextual()) throw new IllegalArgumentException();
            Instant value = Instant.parse(body.path(field).asText());
            if (value.isAfter(Instant.now()) || value.isBefore(Instant.parse("1900-01-01T00:00:00Z"))) throw new IllegalArgumentException();
            return Timestamp.from(value);
        } catch (RuntimeException error) { throw new HeapyException(ErrorCode.INVALID_INPUT); }
    }

    private static BigDecimal number(JsonNode body, String field, boolean required, boolean positive) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            if (required) invalid();
            return null;
        }
        if (!node.isNumber()) invalid();
        BigDecimal value;
        try { value = node.decimalValue(); } catch (RuntimeException error) { throw new HeapyException(ErrorCode.INVALID_INPUT); }
        if (value.signum() < 0 || (positive && value.signum() == 0) || value.compareTo(BigDecimal.valueOf(1000000)) > 0
                || value.precision() > 12 || value.scale() > 4) invalid();
        return value;
    }

    private static void invalid() { throw new HeapyException(ErrorCode.INVALID_INPUT); }
}
