package com.heapy.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.heapy.chat.ChatModels.Context;
import com.heapy.chat.ChatModels.Session;
import com.heapy.common.exception.HeapyException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 외부 AI를 호출하지 않는 내부 스트림 계약 시험. @author 김진우 */
class ChatGatewayTest {
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private HttpServer server;
    private ChatGateway gateway;
    private final AtomicReference<String> response = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/chat/stream", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        server.start();
        gateway = new ChatGateway("http://127.0.0.1:" + server.getAddress().getPort(), "synthetic-token-for-contract-tests-only");
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void 검증된_최종답변을_저장용으로_선택한다() {
        response.set("event: delta\ndata: {\"content\":\"검증 전\"}\n\n"
                + "event: done\ndata: {\"answer\":\"최종 합성 답변\",\"citations\":[],\"summary\":\"요약\",\"metadata\":{}}\n\n");
        var result = gateway.generate(UUID.randomUUID(), "질문", context(), "", stage -> { }, () -> false);
        assertThat(result.answer()).isEqualTo("최종 합성 답변");
        assertThat(result.responseStatus()).isEqualTo("completed");
        assertThat(authorization.get()).startsWith("Bearer synthetic-");
    }

    @Test
    void 오류가_나면_생성된_부분만_출처없이_반환한다() {
        response.set("event: delta\ndata: {\"content\":\"생성된 부분\"}\n\nevent: error\ndata: {\"code\":\"PRIVATE_PROVIDER_ERROR\"}\n\n");
        var result = gateway.generate(UUID.randomUUID(), "질문", context(), "", stage -> { }, () -> false);
        assertThat(result.answer()).isEqualTo("생성된 부분");
        assertThat(result.responseStatus()).isEqualTo("partial");
        assertThat(result.citations()).isEmpty();
    }

    @Test
    void 빈_실패는_저장할_답변을_만들지_않는다() {
        response.set("event: error\ndata: {\"code\":\"PRIVATE_PROVIDER_ERROR\"}\n\n");
        assertThatThrownBy(() -> gateway.generate(UUID.randomUUID(), "질문", context(), "", stage -> { }, () -> false))
                .isInstanceOf(HeapyException.class).hasMessageNotContaining("PRIVATE_PROVIDER_ERROR");
    }

    @Test
    void 선택한_파트너를_내부_페르소나로_전달한다() {
        response.set("event: done\ndata: {\"answer\":\"합성 답변\",\"citations\":[],\"summary\":\"\",\"metadata\":{}}\n\n");
        for (String code : List.of("heapy_cat", "heapy_dog")) {
            var context = new Context(new Session(UUID.randomUUID(), "합성", code, Instant.now(), null), "", List.of());
            gateway.generate(UUID.randomUUID(), "질문", context, "", stage -> { }, () -> false);
            assertThat(requestBody.get()).contains("\"persona\":\"" + (code.equals("heapy_cat") ? "coach" : "professional") + "\"");
        }
    }

    private Context context() {
        return new Context(new Session(UUID.randomUUID(), "합성 대화", "heapy_cat", Instant.now(), null), "", List.of());
    }
}
