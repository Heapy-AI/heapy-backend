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
    private final String baseUrl;
    private final String token;
    public HealthAnalysisGateway(@Value("${heapy.chat.base-url:http://heapy-fastapi:8000}") String baseUrl,
                                 @Value("${heapy.chat.internal-token:}") String token) {
        this.baseUrl = baseUrl; this.token = token;
    }

    public JsonNode generate(Map<String, Object> snapshot) {
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
            try (InputStream stream = connection.getInputStream()) {
                byte[] body = stream.readNBytes(65537);
                if (body.length > 65536) throw new IllegalArgumentException();
                JsonNode result = OcrJson.MAPPER.readTree(body);
                String status = result.path("status").asText();
                if (!"generated".equals(status) && !"data_insufficient".equals(status)) throw new IllegalArgumentException();
                if ("generated".equals(status) && (!result.path("report").isObject()
                        || !result.path("report").path("headline").isTextual())) throw new IllegalArgumentException();
                return result;
            }
        } catch (Exception error) {
            throw new IllegalStateException("건강 분석을 완료하지 못했습니다.");
        } finally { if (connection != null) connection.disconnect(); }
    }
}
