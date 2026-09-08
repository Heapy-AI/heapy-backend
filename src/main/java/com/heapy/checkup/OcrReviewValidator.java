package com.heapy.checkup;

import com.heapy.checkup.OcrModels.Confirmation;
import com.heapy.checkup.OcrModels.Correction;
import com.heapy.checkup.OcrModels.Finding;
import com.heapy.checkup.OcrModels.Result;
import com.heapy.checkup.OcrModels.Validated;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/**
 * 의미 분류와 스냅샷 식별자를 검증하고 사용자 수정 차이만 계산한다.
 * @author 김진우
 */
public final class OcrReviewValidator {
    public static final int MAX_BYTES = 262144;
    public static final Set<String> FINDING_FIELDS = Set.of("schemaVersion", "findingId", "classification",
            "examType", "examName", "text", "bodySite", "method", "performedAt", "summary");
    private static final Set<String> EXAM_TYPES = Set.of("upper_gi_endoscopy", "colonoscopy", "biopsy",
            "ultrasound", "ct", "mri", "other_procedure");

    private OcrReviewValidator() { }

    public static Validated validate(JsonNode original, Confirmation body, Function<String, String> itemType) {
        size(body, MAX_BYTES);
        if (body.measuredAt() == null || body.results().size() > 200 || body.findings().size() > 50
                || body.overallOpinions().size() > 20 || body.excludedFieldKeys().size() > 470
                || body.corrections().size() > 600
                || body.results().isEmpty() && body.findings().isEmpty() && body.overallOpinions().isEmpty()) throw invalid();
        optional(body.providerName(), 200);
        validateSnapshot(original);
        boolean modern = original.has("schemaVersion");
        if (modern != Integer.valueOf(2).equals(body.reviewVersion())
                || body.reviewVersion() != null && body.reviewVersion() != 2) {
            throw new HeapyException(ErrorCode.OCR_REVIEW_VERSION);
        }
        for (Result result : body.results()) {
            if (result == null) throw invalid();
            required(result.itemCode(), 100);
            validateResult(result, itemType.apply(result.itemCode()));
        }
        if (!modern) {
            if (!body.findings().isEmpty() || !body.overallOpinions().isEmpty() || !body.excludedFieldKeys().isEmpty()) throw invalid();
            return new Validated(body, OcrConfirmationValidator.validate(original, body, code -> itemType.apply(code) != null));
        }
        if (!body.corrections().isEmpty()) throw invalid();
        Map<String, JsonNode> source = new HashMap<>();
        for (JsonNode item : original.path("items")) source.put(item.path("fieldKey").asText(), item);
        Set<String> excluded = new HashSet<>(body.excludedFieldKeys());
        if (excluded.size() != body.excludedFieldKeys().size()) throw invalid();
        Set<String> handled = new HashSet<>();
        Set<String> codes = new HashSet<>();
        List<Correction> changes = new ArrayList<>();
        for (Result result : body.results()) {
            key(result.fieldKey());
            JsonNode item = source.get(result.fieldKey());
            if (item == null || !handled.add(result.fieldKey()) || excluded.contains(result.fieldKey())
                    || Set.of("UPPER_GI_ENDOSCOPY", "COLONOSCOPY").contains(result.itemCode())
                    || !codes.add(result.itemCode()) || !Objects.equals(text(item, "itemCode"), result.itemCode())
                    || !Objects.equals(text(item, "status"), result.status())) throw invalid();
            change(changes, result.fieldKey() + ".value", result.itemCode(), text(item, "value"), result.value(), "value");
            change(changes, result.fieldKey() + ".unit", result.itemCode(), text(item, "unit"), result.unit(), "unit");
        }
        for (String field : source.keySet()) complete(field, handled, excluded, changes);
        List<Finding> findings = findings(original.path("findings"), body.findings(), "procedure_finding",
                "findings", handled, excluded, changes);
        List<Finding> opinions = findings(original.path("overallOpinions"), body.overallOpinions(), "overall_opinion",
                "overallOpinions", handled, excluded, changes);
        for (JsonNode review : original.path("reviewRequired")) {
            complete(review.path("fieldKey").asText(), handled, excluded, changes);
        }
        if (!excluded.isEmpty()) throw invalid();
        change(changes, "measuredAt", null, text(original, "measuredAt"), body.measuredAt().toString(), "value");
        change(changes, "providerName", null, text(original, "providerName"), body.providerName(), "label");
        return new Validated(new Confirmation(body.measuredAt(), body.providerName(), body.results(), List.of(), 2,
                findings, opinions, body.excludedFieldKeys()), changes);
    }

    public static void validateSnapshot(JsonNode root) {
        if (root == null || !root.isObject() || !root.path("items").isArray() || root.path("items").size() > 200) throw invalid();
        size(root, MAX_BYTES);
        if (!root.has("schemaVersion")) {
            if (root.has("findings") || root.has("overallOpinions") || root.has("reviewRequired")) throw invalid();
            return;
        }
        if (!root.path("schemaVersion").isIntegralNumber() || root.path("schemaVersion").asInt() != 2) {
            throw new HeapyException(ErrorCode.OCR_REVIEW_VERSION);
        }
        allowed(root, Set.of("schemaVersion", "measuredAt", "providerName", "items", "findings", "overallOpinions", "reviewRequired"));
        optionalNode(root, "providerName", 200);
        if (root.hasNonNull("measuredAt")) date(root.path("measuredAt"));
        Set<String> ids = new HashSet<>();
        for (JsonNode item : root.path("items")) {
            allowed(item, Set.of("classification", "fieldKey", "itemCode", "itemName", "value", "numericValue", "unit", "status", "confidence"));
            key(text(item, "fieldKey"));
            if (!"general_test".equals(text(item, "classification")) || !ids.add(text(item, "fieldKey"))) throw invalid();
            required(text(item, "value"), 2000);
            required(text(item, "itemName"), 200);
            optionalNode(item, "itemCode", 100);
            optionalNode(item, "unit", 100);
            optionalNode(item, "status", 500);
            if (item.hasNonNull("numericValue") && !item.path("numericValue").isNumber()) throw invalid();
            if (item.hasNonNull("confidence") && !item.path("confidence").isNumber()) throw invalid();
        }
        array(root, "findings", 50);
        array(root, "overallOpinions", 20);
        array(root, "reviewRequired", 200);
        for (String name : List.of("findings", "overallOpinions")) {
            for (JsonNode node : root.path(name)) {
                Finding finding = parseFinding(node);
                validateFinding(finding, "findings".equals(name) ? "procedure_finding" : "overall_opinion");
                if (!ids.add(finding.findingId().toString())) throw invalid();
                if (finding.summary() != null && !OcrJson.hash(finding.text()).equals(finding.summary().basisHash())) throw invalid();
            }
        }
        for (JsonNode node : root.path("reviewRequired")) {
            allowed(node, Set.of("fieldKey", "classification", "text", "reason"));
            key(text(node, "fieldKey"));
            required(text(node, "text"), 2000);
            if (!ids.add(text(node, "fieldKey")) || !"needs_review".equals(text(node, "classification"))
                    || !Set.of("uncertain_classification", "unmatched_item", "invalid_extraction").contains(node.path("reason").asText())) throw invalid();
        }
    }

    private static List<Finding> findings(JsonNode nodes, List<Finding> submitted, String classification, String prefix,
            Set<String> handled, Set<String> excluded, List<Correction> changes) {
        Map<String, Finding> originals = new HashMap<>();
        for (JsonNode node : nodes) {
            Finding finding = parseFinding(node);
            originals.put(finding.findingId().toString(), finding);
        }
        List<Finding> saved = new ArrayList<>();
        for (Finding finding : submitted) {
            validateFinding(finding, classification);
            String id = finding.findingId().toString();
            Finding before = originals.get(id);
            if (before == null || excluded.contains(id) || !handled.add(id)
                    || !Objects.equals(before.examType(), finding.examType())
                    || !Objects.equals(before.examName(), finding.examName())
                    || !Objects.equals(before.bodySite(), finding.bodySite())
                    || !Objects.equals(before.method(), finding.method())
                    || !Objects.equals(before.performedAt(), finding.performedAt())
                    || finding.summary() != null && !finding.summary().equals(before.summary())) throw invalid();
            change(changes, prefix + "." + id + ".text", null, before.text(), finding.text(), "value");
            saved.add(before.text().equals(finding.text()) ? finding : finding.withoutSummary());
        }
        for (String id : originals.keySet()) complete(id, handled, excluded, changes);
        return saved;
    }

    private static void complete(String id, Set<String> handled, Set<String> excluded, List<Correction> changes) {
        if (handled.contains(id)) return;
        if (!excluded.remove(id)) throw invalid();
        handled.add(id);
        changes.add(new Correction(id, null, null, null, "excluded"));
    }

    static Finding parseFinding(JsonNode node) {
        allowed(node, FINDING_FIELDS);
        if (!node.path("schemaVersion").isIntegralNumber()) throw invalid();
        for (String field : List.of("findingId", "classification", "examName", "text")) {
            if (!node.path(field).isTextual()) throw invalid();
        }
        for (String field : List.of("examType", "bodySite", "method")) optionalNode(node, field, 200);
        if (node.hasNonNull("performedAt")) date(node.path("performedAt"));
        if (node.hasNonNull("summary")) {
            allowed(node.path("summary"), Set.of("text", "source", "basisHash"));
            for (String field : List.of("text", "source", "basisHash")) {
                if (!node.path("summary").path(field).isTextual()) throw invalid();
            }
        }
        try {
            Finding finding = OcrJson.MAPPER.treeToValue(node, Finding.class);
            if (!finding.findingId().toString().equals(text(node, "findingId"))) throw invalid();
            return finding;
        } catch (RuntimeException exception) { throw invalid(); }
    }

    private static void validateFinding(Finding finding, String classification) {
        if (finding == null || !Integer.valueOf(1).equals(finding.schemaVersion()) || finding.findingId() == null
                || !classification.equals(finding.classification())) throw invalid();
        required(finding.examName(), 200);
        required(finding.text(), 12000);
        optional(finding.bodySite(), 200);
        optional(finding.method(), 200);
        if ("procedure_finding".equals(classification)) {
            if (finding.examType() == null || !EXAM_TYPES.contains(finding.examType())) throw invalid();
        } else if (finding.examType() != null || finding.bodySite() != null || finding.method() != null
                || finding.performedAt() != null) throw invalid();
        if (finding.summary() != null) {
            required(finding.summary().text(), 2000);
            if (!Set.of("institution", "ai").contains(Objects.toString(finding.summary().source(), ""))
                    || finding.summary().basisHash() == null || !finding.summary().basisHash().matches("[0-9a-f]{64}")) throw invalid();
        }
        size(finding, 65536);
    }

    private static void validateResult(Result result, String type) {
        if (result == null || type == null || !Set.of("numeric", "text", "composite").contains(type)) throw invalid();
        required(result.itemCode(), 100);
        required(result.value(), 2000);
        optional(result.unit(), 100);
        optional(result.status(), 500);
        BigDecimal numeric = result.numericValue();
        if (numeric != null && (numeric.scale() > 8 || numeric.precision() - numeric.scale() > 18)) throw invalid();
        if (!"numeric".equals(type)) {
            if (numeric != null) throw invalid();
            return;
        }
        boolean number = result.value().trim().matches("[+-]?\\d+(\\.\\d+)?");
        if (number != (numeric != null) || number && new BigDecimal(result.value().trim()).compareTo(numeric) != 0) throw invalid();
    }

    private static void change(List<Correction> changes, String key, String code, String before, String after, String type) {
        if (!Objects.equals(before, after)) changes.add(new Correction(key, code, before, after, type));
    }
    private static void array(JsonNode root, String name, int max) {
        if (!root.path(name).isArray() || root.path(name).size() > max) throw invalid();
    }
    private static void allowed(JsonNode node, Set<String> fields) {
        if (!node.isObject() || node.properties().stream().anyMatch(entry -> !fields.contains(entry.getKey()))) throw invalid();
    }
    private static void key(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,100}")) throw invalid();
    }
    private static String text(JsonNode node, String field) { return node.path(field).isTextual() ? node.path(field).asText() : null; }
    private static void optionalNode(JsonNode node, String name, int max) {
        if (node.hasNonNull(name) && !node.path(name).isTextual()) throw invalid();
        optional(text(node, name), max);
    }
    private static void required(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || value.indexOf('\0') >= 0) throw invalid();
    }
    private static void optional(String value, int max) { if (value != null) required(value, max); }
    private static void date(JsonNode node) {
        try {
            if (!node.isTextual() || !LocalDate.parse(node.asText()).toString().equals(node.asText())) throw invalid();
        } catch (RuntimeException exception) { throw invalid(); }
    }
    private static void size(Object value, int max) {
        if (OcrJson.encode(value).getBytes(StandardCharsets.UTF_8).length > max) throw new HeapyException(ErrorCode.OCR_RESULT_LIMIT);
    }
    private static HeapyException invalid() { return new HeapyException(ErrorCode.OCR_INVALID_RESULT); }
}
