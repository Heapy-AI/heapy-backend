package com.heapy.checkup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.heapy.checkup.OcrModels.Confirmation;
import com.heapy.common.exception.HeapyException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * OCR 파서와 매핑 함수가 생성한 합성 스냅샷을 실제 백엔드 검증기에 연결한다.
 * @author 김진우
 */
class OcrProducerContractTest {
    private ObjectNode original() throws IOException {
        try (var input = getClass().getResourceAsStream("/ocr-v2-synthetic.json")) {
            return (ObjectNode) OcrJson.MAPPER.readTree(input);
        }
    }

    private ObjectNode confirmation(ObjectNode original) {
        ObjectNode body = OcrJson.MAPPER.createObjectNode();
        body.put("reviewVersion", 2);
        body.set("measuredAt", original.get("measuredAt"));
        body.set("providerName", original.get("providerName"));
        var results = body.putArray("results");
        for (var item : original.path("items")) {
            ObjectNode result = results.addObject();
            for (String field : new String[]{"fieldKey", "itemCode", "value", "numericValue", "unit", "status"}) {
                result.set(field, item.get(field));
            }
        }
        body.set("findings", original.get("findings"));
        body.set("overallOpinions", original.get("overallOpinions"));
        body.putArray("corrections");
        var excluded = body.putArray("excludedFieldKeys");
        for (var item : original.path("reviewRequired")) excluded.add(item.path("fieldKey").asText());
        return body;
    }

    @Test
    void OCR_생성_스냅샷의_일반결과와_소견을_검증한다() throws IOException {
        ObjectNode original = original();
        Confirmation request = OcrJson.MAPPER.treeToValue(confirmation(original), Confirmation.class);
        var validated = OcrReviewValidator.validate(original, request,
                code -> "HEMOGLOBIN".equals(code) ? "numeric" : null);
        assertThat(validated.confirmation().results()).hasSize(1);
        assertThat(validated.confirmation().findings()).hasSize(1);
        assertThat(validated.confirmation().overallOpinions()).hasSize(1);
    }

    @Test
    void OCR_미매칭_항목의_명시적_제외_누락을_거부한다() throws IOException {
        ObjectNode original = original();
        ObjectNode body = confirmation(original);
        body.putArray("excludedFieldKeys");
        Confirmation request = OcrJson.MAPPER.treeToValue(body, Confirmation.class);
        assertThatThrownBy(() -> OcrReviewValidator.validate(original, request, code -> "numeric"))
                .isInstanceOf(HeapyException.class);
    }
}
