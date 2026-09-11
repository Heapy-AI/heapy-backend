package com.heapy.medication;

import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** 사용자 검수값만 저장하며 복용량과 시각을 추측하지 않는다. @author 김진우 */
public final class MedicationInput {
    private MedicationInput() { }
    private static final Set<String> FIELDS = Set.of("displayName", "doseAmount", "doseUnit", "dosageText",
            "instructions", "startDate", "endDate", "scheduledTimes");

    public static void fields(JsonNode body) {
        if (body == null || !body.isObject() || body.isEmpty()) invalid();
        for (var field : body.properties()) if (!FIELDS.contains(field.getKey())) invalid();
    }

    public static void validate(JsonNode body) {
        text(body, "displayName", 200, true); text(body, "dosageText", 1000, true);
        text(body, "instructions", 1000, false); text(body, "doseUnit", 30, false);
        JsonNode amount = body.path("doseAmount");
        if (!amount.isMissingNode() && !amount.isNull() && (!amount.isNumber()
                || amount.decimalValue().signum() <= 0 || amount.decimalValue().compareTo(new BigDecimal("9999999.999")) > 0
                || amount.decimalValue().scale() > 3)) invalid();
        try {
            LocalDate start = LocalDate.parse(body.path("startDate").asText());
            if (start.getYear() < 1900 || start.getYear() > 2200) invalid();
            if (body.hasNonNull("endDate")) {
                LocalDate end = LocalDate.parse(body.path("endDate").asText());
                if (end.isBefore(start) || end.getYear() > 2200) invalid();
            }
            JsonNode times = body.path("scheduledTimes");
            if (!times.isArray() || times.isEmpty() || times.size() > 12) invalid();
            Set<LocalTime> unique = new HashSet<>();
            for (JsonNode time : times) {
                if (!time.isString() || !time.asText().matches("\\d{2}:\\d{2}(:00)?")) invalid();
                if (!unique.add(LocalTime.parse(time.asText()))) invalid();
            }
        } catch (RuntimeException exception) { invalid(); }
    }

    private static void text(JsonNode body, String field, int max, boolean required) {
        JsonNode node = body.path(field);
        if (!required && (node.isNull() || node.isMissingNode())) return;
        if (!node.isString() || node.asText().length() > max || (required && node.asText().isBlank())) invalid();
    }
    static void invalid() { throw new HeapyException(ErrorCode.INVALID_INPUT); }
}
