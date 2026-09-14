package com.heapy.health.analysis;

import com.heapy.checkup.OcrJson;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 내부 분석을 재시도 없이 호출하며 응답 크기를 제한한다. @author 김진우 */
@Component
public class HealthAnalysisGateway {
    /** 분석 문장 응답의 상한. 한 탭 분량이라 64KiB 로 충분하다. */
    private static final int REPORT_LIMIT = 65536;
    /**
     * 점수 응답의 상한.
     *
     * 점수는 하루치가 성분과 근거까지 담아 1.3KB 쯤 된다. 최대 90일치를 한 번에 받으므로
     * 120KB 를 넘긴다. 분석 문장과 같은 64KiB 를 쓰면 30일만 넘어도 잘린다.
     *
     * @author 고수연
     */
    private static final int SCORE_LIMIT = 1048576;
    private final String baseUrl;
    private final String token;
    public HealthAnalysisGateway(@Value("${heapy.chat.base-url:http://heapy-fastapi:8000}") String baseUrl,
                                 @Value("${heapy.chat.internal-token:}") String token) {
        this.baseUrl = baseUrl; this.token = token;
    }

    /** 모델이 쓴 분석 문장을 받는다. report.headline 이 없으면 실패로 본다. */
    public JsonNode generate(Map<String, Object> snapshot) {
        return post(snapshot, false);
    }

    /**
     * 오늘의 건강 종합 점수를 받는다.
     *
     * 점수는 모델을 부르지 않는 순수 계산이라 report 도 headline 도 없다. 그래서
     * {@link #generate} 의 검증을 타면 안 된다. 대신 score 객체와 points 배열을 본다.
     *
     * @author 고수연
     */
    public JsonNode score(Map<String, Object> snapshot) {
        return post(snapshot, true);
    }

    private JsonNode post(Map<String, Object> snapshot, boolean scoring) {
        HttpURLConnection connection = null;
        try {
            if (token.length() < 32) throw new IllegalStateException();
            byte[] request = OcrJson.encode(snapshot).getBytes(StandardCharsets.UTF_8);
            if (request.length > 262144) throw new IllegalArgumentException();
            connection = (HttpURLConnection) URI.create(baseUrl + "/internal/health/analyses").toURL().openConnection();
            connection.setRequestMethod("POST"); connection.setConnectTimeout(5000); connection.setReadTimeout(90000);
            connection.setInstanceFollowRedirects(false); connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(request.length);
            try (var stream = connection.getOutputStream()) { stream.write(request); }
            if (connection.getResponseCode() != 200) throw new IllegalStateException();
            int limit = scoring ? SCORE_LIMIT : REPORT_LIMIT;
            try (InputStream stream = connection.getInputStream()) {
                byte[] body = stream.readNBytes(limit + 1);
                if (body.length > limit) throw new IllegalArgumentException();
                JsonNode result = OcrJson.MAPPER.readTree(body);
                String status = result.path("status").asText();
                if (!"generated".equals(status) && !"data_insufficient".equals(status)) throw new IllegalArgumentException();
                if (scoring) {
                    // 기록이 모자란 날도 score·points 는 온다. total_score 만 null 이다.
                    if (!result.path("score").isObject() || !result.path("points").isArray()
                            || result.path("points").size() < 1) throw new IllegalArgumentException();
                } else if ("generated".equals(status) && (!result.path("report").isObject()
                        || !result.path("report").path("headline").isTextual())) {
                    throw new IllegalArgumentException();
                }
                return result;
            }
        } catch (Exception error) {
            throw new IllegalStateException("건강 분석을 완료하지 못했습니다.");
        } finally { if (connection != null) connection.disconnect(); }
    }
}
