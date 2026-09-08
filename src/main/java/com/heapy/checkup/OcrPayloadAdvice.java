package com.heapy.checkup;

import com.heapy.checkup.OcrModels.Confirmation;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.Set;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import tools.jackson.databind.JsonNode;

/**
 * 확정 본문을 역직렬화 전에 제한하고 알 수 없는 건강정보 필드를 거부한다.
 * @author 김진우
 */
@ControllerAdvice
public class OcrPayloadAdvice extends RequestBodyAdviceAdapter {
    @Override
    public boolean supports(MethodParameter parameter, Type type, Class<? extends HttpMessageConverter<?>> converter) {
        return type == Confirmation.class;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage message, MethodParameter parameter, Type type,
            Class<? extends HttpMessageConverter<?>> converter) throws IOException {
        byte[] bytes = message.getBody().readNBytes(OcrReviewValidator.MAX_BYTES + 1);
        if (bytes.length > OcrReviewValidator.MAX_BYTES) throw new HeapyException(ErrorCode.OCR_RESULT_LIMIT);
        JsonNode root;
        try { root = OcrJson.MAPPER.readTree(bytes); }
        catch (RuntimeException exception) { throw new HeapyException(ErrorCode.INVALID_INPUT); }
        fields(root, Set.of("measuredAt", "providerName", "results", "corrections", "reviewVersion",
                "findings", "overallOpinions", "excludedFieldKeys"));
        for (JsonNode result : root.path("results")) fields(result,
                Set.of("fieldKey", "itemCode", "value", "numericValue", "unit", "status"));
        for (JsonNode correction : root.path("corrections")) fields(correction,
                Set.of("fieldKey", "itemCode", "originalValue", "correctedValue", "correctionType"));
        for (String name : Set.of("findings", "overallOpinions")) {
            for (JsonNode finding : root.path(name)) {
                fields(finding, OcrReviewValidator.FINDING_FIELDS);
                if (finding.hasNonNull("summary")) fields(finding.path("summary"), Set.of("text", "source", "basisHash"));
                OcrReviewValidator.parseFinding(finding);
            }
        }
        return new HttpInputMessage() {
            public InputStream getBody() { return new ByteArrayInputStream(bytes); }
            public HttpHeaders getHeaders() { return message.getHeaders(); }
        };
    }

    private static void fields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()
                || node.properties().stream().anyMatch(entry -> !allowed.contains(entry.getKey()))) {
            throw new HeapyException(ErrorCode.INVALID_INPUT);
        }
    }
}
