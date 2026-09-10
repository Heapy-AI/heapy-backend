package com.heapy.health.service;

import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import com.heapy.health.model.HealthMetric;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** SDK 정규화 결과만 허용하며 소유자·출처·원본 버전은 서버에서 설정한다. @author 김진우 */
public final class HealthSyncInput {
    public static final Set<String> STREAMS = Set.of("sleep", "heart_rate", "blood_glucose", "blood_pressure",
            "body_composition", "exercise", "activity", "water", "nutrition");
    private static final Map<HealthMetric, String> FIELDS = Map.of(
            HealthMetric.SLEEP, "start_at end_at total_sleep_minutes awake_minutes deep_sleep_minutes light_sleep_minutes rem_sleep_minutes sleep_score",
            HealthMetric.BIO, "measured_at bio_type heart_rate_bpm blood_glucose_mg_dl is_fasting systolic_mmhg diastolic_mmhg pulse_bpm weight_kg height_cm bmi_value body_fat_percent skeletal_muscle_kg",
            HealthMetric.ACTIVITY, "record_date steps floors active_time_minutes distance_m active_calories_kcal",
            HealthMetric.EXERCISE, "start_at end_at exercise_type duration_seconds distance_m calories_kcal",
            HealthMetric.NUTRITION, "consumed_at meal_type title calories total_fat saturated_fat polyunsaturated_fat monounsaturated_fat trans_fat carbohydrate dietary_fiber sugar protein cholesterol sodium potassium vitamin_a vitamin_c calcium iron",
            HealthMetric.WATER, "consumed_at amount_ml");
    private static final Set<String> INTEGERS = Set.of("steps", "floors", "active_time_minutes", "total_sleep_minutes",
            "awake_minutes", "deep_sleep_minutes", "light_sleep_minutes", "rem_sleep_minutes", "duration_seconds");
    private HealthSyncInput() { }

    public record Record(HealthMetric metric, String externalId, Instant updatedAt, boolean deleted, Map<String, Object> values) { }

    public static Record record(String stream, JsonNode body) {
        if (!STREAMS.contains(stream) || body == null || !body.isObject()) invalid();
        String metricCode = Set.of("heart_rate", "blood_glucose", "blood_pressure", "body_composition").contains(stream) ? "bio" : stream;
        HealthMetric metric = HealthMetric.of(metricCode);
        if (!metricCode.equals(body.path("metric").asText())) invalid();
        String id = body.path("externalRecordId").asText("");
        if (!id.startsWith(stream + ":") || id.length() <= stream.length() + 1 || id.length() > 256) invalid();
        Instant updated = instant(body.path("sourceUpdatedAt"));
        String operation = body.path("operation").asText("UPSERT");
        if (!Set.of("UPSERT", "DELETE").contains(operation)) invalid();
        if (operation.equals("DELETE")) return new Record(metric, id, updated, true, Map.of());
        JsonNode data = body.path("data");
        if (!data.isObject()) invalid();
        Map<String, Object> values = new LinkedHashMap<>();
        for (String field : FIELDS.get(metric).split(" ")) {
            String key = camel(field);
            JsonNode value = data.path(key);
            Object parsed = null;
            if (!value.isMissingNode() && !value.isNull()) {
                if (field.endsWith("_at")) parsed = Timestamp.from(instant(value));
                else if (field.equals("record_date")) {
                    try {
                        LocalDate date = LocalDate.parse(value.asText());
                        if (date.isAfter(LocalDate.now(ZoneId.of("Asia/Seoul"))) || date.isBefore(LocalDate.of(1900, 1, 1))) invalid();
                        parsed = Date.valueOf(date);
                    } catch (RuntimeException error) { invalid(); }
                } else if (Set.of("bio_type", "exercise_type", "meal_type", "title").contains(field)) {
                    if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 500) invalid();
                    parsed = value.asText();
                } else if (field.equals("is_fasting")) {
                    if (!value.isBoolean()) invalid();
                    parsed = value.asBoolean();
                } else {
                    if (!value.isNumber()) invalid();
                    BigDecimal number = value.decimalValue();
                    if (number.signum() < 0 || number.compareTo(BigDecimal.valueOf(1000000000)) > 0) invalid();
                    if (INTEGERS.contains(field)) {
                        try { parsed = number.intValueExact(); } catch (ArithmeticException error) { invalid(); }
                    } else parsed = number.setScale(4, RoundingMode.HALF_UP);
                }
            }
            values.put(field, parsed);
        }
        for (String key : data.propertyNames()) if (!values.containsKey(snake(key))) invalid();
        required(values, metric.time());
        switch (metric) {
            case BIO -> {
                if (!stream.equals(values.get("bio_type"))) invalid();
                switch (stream) {
                    case "heart_rate" -> required(values, "heart_rate_bpm");
                    case "blood_glucose" -> required(values, "blood_glucose_mg_dl");
                    case "blood_pressure" -> { required(values, "systolic_mmhg"); required(values, "diastolic_mmhg"); }
                    case "body_composition" -> required(values, "weight_kg");
                    default -> invalid();
                }
                if (values.get("height_cm") instanceof BigDecimal height && height.signum() == 0) invalid();
            }
            case WATER -> {
                required(values, "amount_ml");
                if (((BigDecimal) values.get("amount_ml")).signum() <= 0) invalid();
            }
            case SLEEP, EXERCISE -> {
                required(values, "end_at");
                if (!((Timestamp) values.get("end_at")).after((Timestamp) values.get("start_at"))) invalid();
                required(values, metric == HealthMetric.SLEEP ? "total_sleep_minutes" : "duration_seconds");
                if (metric == HealthMetric.EXERCISE) required(values, "exercise_type");
            }
            case ACTIVITY -> {
                if (!id.equals("activity:" + values.get("record_date"))) invalid();
                if (values.entrySet().stream().noneMatch(e -> !e.getKey().equals("record_date") && e.getValue() != null)) invalid();
            }
            default -> { }
        }
        return new Record(metric, id, updated, false, values);
    }

    public static Instant instant(JsonNode node) {
        try {
            if (!node.isTextual()) throw new IllegalArgumentException();
            Instant time = Instant.parse(node.asText());
            if (time.isAfter(Instant.now().plusSeconds(300)) || time.isBefore(Instant.parse("1900-01-01T00:00:00Z"))) invalid();
            return time;
        } catch (RuntimeException error) { throw new HeapyException(ErrorCode.INVALID_INPUT); }
    }
    private static void required(Map<String, Object> values, String field) { if (values.get(field) == null) invalid(); }
    private static String camel(String value) {
        StringBuilder result = new StringBuilder(); boolean upper = false;
        for (char c : value.toCharArray()) { if (c == '_') upper = true; else { result.append(upper ? Character.toUpperCase(c) : c); upper = false; } }
        return result.toString();
    }
    private static String snake(String value) { return value.replaceAll("([A-Z])", "_$1").toLowerCase(Locale.ROOT); }
    private static void invalid() { throw new HeapyException(ErrorCode.INVALID_INPUT); }
}
