package com.heapy.chat;

import com.heapy.chat.ChatModels.Citation;
import com.heapy.chat.ChatModels.Context;
import com.heapy.chat.ChatModels.Generated;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 내부 응답 원문을 로그로 복제하지 않는 제한된 SSE 수신기. @author 김진우 */
@Component
public class ChatGateway {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final String baseUrl;
    private final String token;

    public ChatGateway(@Value("${heapy.chat.base-url:http://heapy-fastapi:8000}") String baseUrl,
                       @Value("${heapy.chat.internal-token:}") String token) {
        this.baseUrl = baseUrl;
        this.token = token;
    }

    public Generated generate(UUID requestId, String question, Context context, String health,
                              Consumer<String> progress, BooleanSupplier cancelled) {
        if (token.length() < 32) throw new HeapyException(ErrorCode.CHAT_UNAVAILABLE);
        StringBuilder partial = new StringBuilder();
        HttpURLConnection connection = null;
        long deadline = System.nanoTime() + 90_000_000_000L;
        try {
            connection = (HttpURLConnection) URI.create(baseUrl + "/internal/chat/stream").toURL().openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(90000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("X-Request-Id", requestId.toString());
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            byte[] body = JSON.writeValueAsBytes(Map.of("contractVersion", "1.0", "message", question,
                    "history", context.messages().stream().map(message -> Map.of("role", message.role(),
                            "content", message.content().substring(0, Math.min(2000, message.content().length())))).toList(),
                    "summary", context.summary().substring(0, Math.min(4000, context.summary().length())),
                    "persona", "heapy_dog".equals(context.session().companionCode()) ? "professional" : "coach", "personalContext", health));
            if (body.length > 262144) throw new IllegalArgumentException();
            connection.setFixedLengthStreamingMode(body.length);
            try (var output = connection.getOutputStream()) { output.write(body); }
            if (connection.getResponseCode() != 200) throw new IllegalStateException();
            try (var reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder line = new StringBuilder();
                String event = "";
                String data = "";
                int size = 0;
                int character;
                while (true) {
                    long remainingMillis = (deadline - System.nanoTime()) / 1_000_000L;
                    if (remainingMillis <= 0 || cancelled.getAsBoolean()) throw new IllegalStateException();
                    connection.setReadTimeout((int) Math.max(1, remainingMillis));
                    character = reader.read();
                    if (character == -1) break;
                    if (cancelled.getAsBoolean() || System.nanoTime() > deadline || ++size > 1048576) throw new IllegalStateException();
                    if (character == '\r') continue;
                    if (character != '\n') {
                        if (line.length() >= 65536) throw new IllegalArgumentException();
                        line.append((char) character);
                        continue;
                    }
                    String value = line.toString();
                    line.setLength(0);
                    if (value.startsWith("event:")) event = value.substring(6).strip();
                    else if (value.startsWith("data:")) data = value.substring(5).strip();
                    else if (value.isEmpty() && !data.isEmpty()) {
                        JsonNode payload = JSON.readTree(data);
                        if ("status".equals(event)) progress.accept("generating");
                        else if ("delta".equals(event)) {
                            partial.append(payload.path("content").asText(""));
                            if (partial.length() > 16000) throw new IllegalArgumentException();
                        } else if ("done".equals(event)) return completed(payload);
                        else if ("error".equals(event)) throw new IllegalStateException();
                        data = "";
                        event = "";
                    }
                }
            }
        } catch (Exception ignored) {
            // 작성자: 김진우 — 내부 공급자의 예외·요청·키를 외부 응답에 포함하지 않는다.
        } finally {
            if (connection != null) connection.disconnect();
        }
        if (!partial.isEmpty() && partial.length() <= 16000) {
            return new Generated(partial.toString(), "partial", "", List.of(), Map.of());
        }
        throw new HeapyException(ErrorCode.CHAT_UNAVAILABLE);
    }

    private Generated completed(JsonNode body) {
        String answer = body.path("answer").asText("");
        if (answer.isBlank() || answer.length() > 16000) throw new IllegalArgumentException();
        List<Citation> citations = new ArrayList<>();
        for (JsonNode item : body.path("citations")) {
            if (citations.size() >= 12) throw new IllegalArgumentException();
            String url = item.path("sourceUrl").asText("");
            String document = item.path("documentId").asText("");
            String title = item.path("title").asText("");
            if (url.length() > 2000 || document.length() > 200 || title.length() > 300
                    || (!url.isEmpty() && !url.startsWith("https://")) || (url.isEmpty() && document.isEmpty())) throw new IllegalArgumentException();
            citations.add(new Citation(citations.size() + 1, title, url.isEmpty() ? null : url, document.isEmpty() ? null : document));
        }
        String summary = body.path("summary").asText("");
        if (summary.length() > 4000) throw new IllegalArgumentException();
        return new Generated(answer, "completed", summary, citations,
                Map.of("intent", body.path("metadata").path("intent").asText(""),
                        "emergency", body.path("metadata").path("emergency").asBoolean(false)));
    }
}
