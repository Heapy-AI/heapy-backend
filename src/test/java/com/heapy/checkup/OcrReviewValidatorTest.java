package com.heapy.checkup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.heapy.checkup.OcrModels.Confirmation;
import com.heapy.checkup.OcrModels.Finding;
import com.heapy.checkup.OcrModels.Result;
import com.heapy.checkup.OcrModels.Summary;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 실제 건강 문서와 외부 모델 없이 합성 검수 계약을 검증한다.
 * @author 김진우
 */
class OcrReviewValidatorTest {
    static final UUID FINDING_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static Finding finding() {
        return new Finding(1, FINDING_ID, "procedure_finding", "upper_gi_endoscopy", "위내시경",
                "합성 기관 소견 첫 문장.\n동일 검사 두 번째 문장.", "위", null, null, null);
    }

    static ObjectNode snapshot() {
        ObjectNode root = OcrJson.MAPPER.createObjectNode();
        root.put("schemaVersion", 2);
        root.put("measuredAt", "2026-08-12");
        root.put("providerName", "합성 검진센터");
        root.putArray("items");
        root.putArray("findings").add(OcrJson.MAPPER.valueToTree(finding()));
        root.putArray("overallOpinions");
        root.putArray("reviewRequired");
        return root;
    }

    static Confirmation body(List<Result> results, List<Finding> findings, List<Finding> opinions, List<String> excluded) {
        return new Confirmation(LocalDate.of(2026, 8, 12), "합성 검진센터", results, List.of(), 2, findings, opinions, excluded);
    }

    static String type(String code) {
        return switch (code) {
            case "FASTING_GLUCOSE" -> "numeric";
            case "HEARING_GENERAL_LEFT", "CHEST_XRAY" -> "text";
            default -> null;
        };
    }

    private void invalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(HeapyException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.OCR_INVALID_RESULT));
    }

    @Test
    void 여러문장을_검사단위로_보존하고_소견전용_회차를_허용한다() {
        var saved = OcrReviewValidator.validate(snapshot(), body(List.of(), List.of(finding()), List.of(), List.of()), OcrReviewValidatorTest::type);
        assertThat(saved.confirmation().findings()).containsExactly(finding());
        assertThat(saved.corrections()).isEmpty();
    }

    @Test
    void 같은종류의_다른부위와_검사시점은_합치지않는다() {
        Finding second = new Finding(1, UUID.randomUUID(), "procedure_finding", "upper_gi_endoscopy", "위내시경",
                "다른 부위의 합성 소견", "식도", null, LocalDate.of(2026, 8, 11), null);
        var root = snapshot();
        root.withArray("findings").add(OcrJson.MAPPER.valueToTree(second));
        var saved = OcrReviewValidator.validate(root, body(List.of(), List.of(finding(), second), List.of(), List.of()), OcrReviewValidatorTest::type);
        assertThat(saved.confirmation().findings()).hasSize(2);
    }

    @Test
    void 종합소견만있는_회차를_허용한다() {
        Finding opinion = new Finding(1, UUID.randomUUID(), "overall_opinion", null, "종합소견", "합성 권고", null, null, null, null);
        var root = snapshot();
        root.withArray("findings").removeAll();
        root.withArray("overallOpinions").add(OcrJson.MAPPER.valueToTree(opinion));
        assertThat(OcrReviewValidator.validate(root, body(List.of(), List.of(), List.of(opinion), List.of()), OcrReviewValidatorTest::type)
                .confirmation().overallOpinions()).containsExactly(opinion);
    }

    @Test
    void 숫자수정과_정성일반검사와_소견을_함께확정한다() {
        var root = snapshot();
        root.withArray("items").add(item("result-1", "FASTING_GLUCOSE", "102", "정상"));
        root.withArray("items").add(item("result-2", "HEARING_GENERAL_LEFT", "정상", null));
        var submitted = body(List.of(new Result("FASTING_GLUCOSE", "100", new BigDecimal("100"), null, "정상", "result-1"),
                new Result("HEARING_GENERAL_LEFT", "정상", null, null, null, "result-2")), List.of(finding()), List.of(), List.of());
        var saved = OcrReviewValidator.validate(root, submitted, OcrReviewValidatorTest::type);
        assertThat(saved.corrections()).hasSize(1);
        assertThat(saved.corrections().getFirst().correctedValue()).isEqualTo("100");
        assertThat(saved.confirmation().results().get(1).numericValue()).isNull();
    }

    static JsonNode item(String field, String code, String value, String status) {
        var item = OcrJson.MAPPER.createObjectNode();
        item.put("classification", "general_test");
        item.put("fieldKey", field);
        item.put("itemCode", code);
        item.put("itemName", "합성 검사");
        item.put("value", value);
        item.put("status", status);
        return item;
    }

    @Test
    void 수정한소견의_오래된요약은_제거하고_본문차이만_기록한다() {
        Summary summary = new Summary("합성 요약", "ai", OcrJson.hash(finding().text()));
        Finding original = new Finding(1, FINDING_ID, "procedure_finding", "upper_gi_endoscopy", "위내시경",
                finding().text(), "위", null, null, summary);
        Finding edited = new Finding(1, FINDING_ID, "procedure_finding", "upper_gi_endoscopy", "위내시경",
                "사용자가 수정한 기관 소견", "위", null, null, summary);
        var root = snapshot();
        root.withArray("findings").set(0, OcrJson.MAPPER.valueToTree(original));
        var saved = OcrReviewValidator.validate(root, body(List.of(), List.of(edited), List.of(), List.of()), OcrReviewValidatorTest::type);
        assertThat(saved.confirmation().findings().getFirst().summary()).isNull();
        assertThat(saved.corrections()).hasSize(1);
        assertThat(saved.corrections().getFirst().fieldKey()).isEqualTo("findings." + FINDING_ID + ".text");
    }

    @Test
    void 클라이언트가_요약을_주입하지못한다() {
        Finding forged = new Finding(1, FINDING_ID, "procedure_finding", "upper_gi_endoscopy", "위내시경",
                finding().text(), "위", null, null, new Summary("임의 요약", "ai", OcrJson.hash(finding().text())));
        invalid(() -> OcrReviewValidator.validate(snapshot(), body(List.of(), List.of(forged), List.of(), List.of()), OcrReviewValidatorTest::type));
    }

    @Test
    void 다른작업_소견식별자와_분류변조를_거부한다() {
        Finding forged = new Finding(1, UUID.randomUUID(), "procedure_finding", "upper_gi_endoscopy", "위내시경",
                finding().text(), "위", null, null, null);
        invalid(() -> OcrReviewValidator.validate(snapshot(), body(List.of(), List.of(forged), List.of(), List.of()), OcrReviewValidatorTest::type));
        invalid(() -> OcrReviewValidator.validate(snapshot(), body(List.of(), List.of(), List.of(finding()), List.of()), OcrReviewValidatorTest::type));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\n\t"})
    void 빈소견을_거부한다(String text) {
        Finding empty = new Finding(1, FINDING_ID, "procedure_finding", "upper_gi_endoscopy", "위내시경", text, "위", null, null, null);
        invalid(() -> OcrReviewValidator.validate(snapshot(), body(List.of(), List.of(empty), List.of(), List.of()), OcrReviewValidatorTest::type));
    }

    @Test
    void 잘못된_enum_버전_허용목록_길이를_검증한다() {
        List<Consumer<ObjectNode>> mutations = List.of(
                node -> node.put("examType", "invented_diagnosis"),
                node -> node.put("schemaVersion", 3),
                node -> node.put("text", "가".repeat(12001)),
                node -> node.put("sourcePath", "/private/document"),
                node -> node.put("performedAt", "2026-02-30"),
                node -> node.put("findingId", "1-1-1-1-1"));
        for (Consumer<ObjectNode> mutation : mutations) {
            var root = snapshot();
            mutation.accept((ObjectNode) root.path("findings").get(0));
            invalid(() -> OcrReviewValidator.validateSnapshot(root));
        }
    }

    @Test
    void 전체바이트와_개수제한을_거부한다() {
        var root = snapshot();
        for (int i = 0; i < 10; i++) {
            var node = (ObjectNode) OcrJson.MAPPER.valueToTree(finding());
            node.put("findingId", UUID.randomUUID().toString());
            node.put("text", "가".repeat(12000));
            root.withArray("findings").add(node);
        }
        assertThatThrownBy(() -> OcrReviewValidator.validateSnapshot(root)).isInstanceOfSatisfying(HeapyException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.OCR_RESULT_LIMIT));
        var excessive = snapshot();
        for (int i = 0; i < 50; i++) excessive.withArray("findings").add(OcrJson.MAPPER.valueToTree(finding()));
        invalid(() -> OcrReviewValidator.validateSnapshot(excessive));
    }

    @Test
    void 미매칭과_확인필요는_자동소견변환없이_명시적으로_제외한다() {
        var root = snapshot();
        root.withArray("items").add(item("unmatched", null, "합성 안내", null));
        root.withArray("reviewRequired").add(OcrJson.MAPPER.readTree("""
                {"fieldKey":"review-1","classification":"needs_review","text":"확인 필요 합성 문장","reason":"invalid_extraction"}
                """));
        invalid(() -> OcrReviewValidator.validate(root, body(List.of(), List.of(finding()), List.of(), List.of()), OcrReviewValidatorTest::type));
        var saved = OcrReviewValidator.validate(root, body(List.of(), List.of(finding()), List.of(), List.of("unmatched", "review-1")), OcrReviewValidatorTest::type);
        assertThat(saved.confirmation().findings()).hasSize(1);
        assertThat(saved.corrections()).allSatisfy(change -> assertThat(change.originalValue()).isNull());
    }

    @Test
    void 상충코드는_fieldKey로_선택하고_나머지를_제외해야한다() {
        var root = snapshot();
        root.withArray("items").add(item("a", "FASTING_GLUCOSE", "100", null));
        root.withArray("items").add(item("b", "FASTING_GLUCOSE", "110", null));
        Result selected = new Result("FASTING_GLUCOSE", "110", new BigDecimal("110"), null, null, "b");
        invalid(() -> OcrReviewValidator.validate(root, body(List.of(selected), List.of(finding()), List.of(), List.of()), OcrReviewValidatorTest::type));
        assertThat(OcrReviewValidator.validate(root, body(List.of(selected), List.of(finding()), List.of(), List.of("a")), OcrReviewValidatorTest::type)
                .confirmation().results()).containsExactly(selected);
    }

    @Test
    void 기관판정변조와_수치불일치와_정성숫자변환을_거부한다() {
        var root = snapshot();
        root.withArray("items").add(item("a", "FASTING_GLUCOSE", "100", "기관 원문"));
        for (Result result : List.of(new Result("FASTING_GLUCOSE", "100", new BigDecimal("100"), null, "임의 판정", "a"),
                new Result("FASTING_GLUCOSE", "100", new BigDecimal("200"), null, "기관 원문", "a"))) {
            invalid(() -> OcrReviewValidator.validate(root, body(List.of(result), List.of(finding()), List.of(), List.of()), OcrReviewValidatorTest::type));
        }
        var qualitative = snapshot();
        qualitative.withArray("items").add(item("a", "HEARING_GENERAL_LEFT", "0", null));
        Result number = new Result("HEARING_GENERAL_LEFT", "0", BigDecimal.ZERO, null, null, "a");
        invalid(() -> OcrReviewValidator.validate(qualitative, body(List.of(number), List.of(finding()), List.of(), List.of()), OcrReviewValidatorTest::type));
    }

    @Test
    void 구앱은_새소견을_조용히_누락할수없다() {
        var legacy = new Confirmation(LocalDate.of(2026, 8, 12), null,
                List.of(new Result("FASTING_GLUCOSE", "100", new BigDecimal("100"), null, null)), List.of());
        assertThatThrownBy(() -> OcrReviewValidator.validate(snapshot(), legacy, OcrReviewValidatorTest::type))
                .isInstanceOfSatisfying(HeapyException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.OCR_REVIEW_VERSION));
    }

    @Test
    void 전체제외된_빈회차와_알수없는제외ID와_중복식별자를_거부한다() {
        invalid(() -> OcrReviewValidator.validate(snapshot(), body(List.of(), List.of(), List.of(), List.of(FINDING_ID.toString())), OcrReviewValidatorTest::type));
        invalid(() -> OcrReviewValidator.validate(snapshot(), body(List.of(), List.of(finding()), List.of(), List.of("unknown")), OcrReviewValidatorTest::type));
        invalid(() -> OcrReviewValidator.validate(snapshot(), body(List.of(), List.of(finding(), finding()), List.of(), List.of()), OcrReviewValidatorTest::type));
    }

    @Test
    void 마스터에_연결된_내시경도_일반검사로_확정하지않는다() {
        var root = snapshot();
        root.withArray("items").add(item("endoscopy", "UPPER_GI_ENDOSCOPY", "합성 기관 소견", null));
        Result misplaced = new Result("UPPER_GI_ENDOSCOPY", "합성 기관 소견", null, null, null, "endoscopy");
        invalid(() -> OcrReviewValidator.validate(root, body(List.of(misplaced), List.of(finding()), List.of(), List.of()), code -> "numeric"));
        assertThat(OcrReviewValidator.validate(root, body(List.of(), List.of(finding()), List.of(), List.of("endoscopy")), code -> "numeric")
                .confirmation().findings()).containsExactly(finding());
    }

    @Test
    void 소견객체_바이트제한은_글자수와_별도로_검증한다() {
        var root = snapshot();
        ((ObjectNode) root.path("findings").get(0)).put("text", "\u0001".repeat(12000));
        assertThatThrownBy(() -> OcrReviewValidator.validateSnapshot(root)).isInstanceOfSatisfying(HeapyException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.OCR_RESULT_LIMIT));
    }
}
