package com.heapy.checkup;

import com.heapy.checkup.OcrModels.Confirmation;
import com.heapy.checkup.OcrModels.Correction;
import com.heapy.checkup.OcrModels.Result;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;

public final class OcrConfirmationValidator {
    private OcrConfirmationValidator() { }

    public static List<Correction> validate(JsonNode original, Confirmation body, Predicate<String> activeItem) {
        if (original == null || !original.path("items").isArray()) throw invalid();
        Map<String, Result> included = new HashMap<>();
        for (Result result : body.results()) {
            if (included.put(result.itemCode(), result) != null || !activeItem.test(result.itemCode())) throw invalid();
            BigDecimal numeric = number(result.value());
            if ((numeric == null) != (result.numericValue() == null)
                    || numeric != null && numeric.compareTo(result.numericValue()) != 0) throw invalid();
        }
        Map<String, Correction> supplied = new HashMap<>();
        for (Correction correction : body.corrections()) {
            if (supplied.put(correction.fieldKey(), correction) != null) throw invalid();
        }
        List<Correction> actual = new ArrayList<>();
        Set<String> consumed = new HashSet<>();
        Set<String> fields = new HashSet<>();
        for (JsonNode item : original.path("items")) {
            String field = text(item, "fieldKey");
            String code = text(item, "itemCode");
            if (field == null || !fields.add(field)) throw invalid();
            Correction excluded = supplied.get(field);
            if (excluded != null) {
                if (!"excluded".equals(excluded.correctionType())) throw invalid();
                // 작성자: 김진우 — 비활성·미매칭 코드가 교정 로그 FK를 위반하지 않도록 검증한다.
                String logCode = code != null && activeItem.test(code) ? code : null;
                actual.add(new Correction(field, logCode, text(item, "value"), "", "excluded"));
                continue;
            }
            Result result = included.get(code);
            if (result == null || !consumed.add(code) || !equal(text(item, "status"), result.status())) throw invalid();
            add(actual, field + ".value", code, text(item, "value"), result.value(), "value");
            add(actual, field + ".unit", code, text(item, "unit"), result.unit(), "unit");
        }
        if (consumed.size() != included.size() || supplied.size() != actual.size()) throw invalid();
        for (Correction correction : actual) {
            Correction client = supplied.get(correction.fieldKey());
            if (client == null || !Objects.equals(client.itemCode(), correction.itemCode())
                    || !equal(client.originalValue(), correction.originalValue())
                    || !equal(client.correctedValue(), correction.correctedValue())
                    || !client.correctionType().equals(correction.correctionType())) throw invalid();
        }
        add(actual, "measuredAt", null, text(original, "measuredAt"), body.measuredAt().toString(), "value");
        add(actual, "providerName", null, text(original, "providerName"), body.providerName(), "label");
        return actual;
    }

    private static void add(List<Correction> changes, String field, String code, String before, String after, String type) {
        if (!equal(before, after)) changes.add(new Correction(field, code, before, after, type));
    }

    private static BigDecimal number(String value) {
        if (value == null || !value.trim().matches("[+-]?\\d+(\\.\\d+)?")) return null;
        try { return new BigDecimal(value.trim()); } catch (NumberFormatException exception) { throw invalid(); }
    }

    private static boolean equal(String first, String second) {
        return Objects.equals(first == null ? "" : first, second == null ? "" : second);
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).isTextual() ? node.path(field).asText() : null;
    }

    private static HeapyException invalid() { return new HeapyException(ErrorCode.OCR_INVALID_RESULT); }
}
