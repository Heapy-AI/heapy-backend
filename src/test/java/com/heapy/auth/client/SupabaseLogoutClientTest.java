package com.heapy.auth.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.heapy.common.config.SupabaseProperties;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.client.RestClient;

class SupabaseLogoutClientTest {
    private HttpServer server;
    private SupabaseLogoutClient client;
    private final AtomicReference<String> request = new AtomicReference<>();

    @BeforeEach
    void 준비() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        client = new SupabaseLogoutClient(RestClient.builder(), new SupabaseProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-publishable-key"));
    }

    @AfterEach
    void 종료() {
        server.stop(0);
    }

    private void 응답(int status, String body) {
        server.createContext("/auth/v1/logout", exchange -> {
            request.set(exchange.getRequestMethod() + " " + exchange.getRequestURI()
                    + " " + exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
            if (status != 204) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @Test
    void 사용자토큰으로_전체세션을_종료한다() {
        응답(204, "");
        client.logout("test-access-token");
        assertThat(request.get()).isEqualTo("POST /auth/v1/logout?scope=global Bearer test-access-token");
    }

    @ParameterizedTest
    @CsvSource({"401", "403"})
    void 이미_종료된_세션은_성공으로_처리한다(int status) {
        응답(status, "{\"error_code\":\"session_not_found\"}");
        client.logout("test-access-token");
    }

    @ParameterizedTest
    @CsvSource({"401,INVALID_ACCESS_TOKEN", "403,INVALID_ACCESS_TOKEN", "429,AUTH_PROVIDER_UNAVAILABLE",
            "500,AUTH_PROVIDER_UNAVAILABLE", "502,AUTH_PROVIDER_UNAVAILABLE"})
    void 인증오류와_서비스장애를_구분한다(int status, ErrorCode code) {
        응답(status, "{\"error_code\":\"other_error\"}");
        assertThatThrownBy(() -> client.logout("test-access-token"))
                .isInstanceOfSatisfying(HeapyException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }

    @Test
    void 공급자에_연결하지_못하면_503이다() {
        server.stop(0);
        assertThatThrownBy(() -> client.logout("test-access-token"))
                .isInstanceOfSatisfying(HeapyException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.AUTH_PROVIDER_UNAVAILABLE));
    }
}
