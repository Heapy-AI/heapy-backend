package com.heapy.auth.client;

import com.heapy.common.config.SupabaseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 인증 공급자의 갱신·로그아웃 계약과 오류 분류를 확인한다. @author 김진우 */
class RestSupabaseAuthClientTest {
    private MockRestServiceServer server;
    private RestSupabaseAuthClient client;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestSupabaseAuthClient(builder, new SupabaseProperties("https://example.invalid", "격리된키"));
    }

    @Test
    void rotatesBothTokens() {
        server.expect(requestTo("https://example.invalid/auth/v1/token?grant_type=refresh_token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"refresh_token\":\"old-refresh\"}"))
                .andRespond(withSuccess("""
                        {"access_token":"new-access","refresh_token":"new-refresh","token_type":"bearer",
                         "expires_in":3600,"expires_at":1900000000,
                         "user":{"id":"11111111-1111-1111-1111-111111111111","email":"test@example.invalid",
                         "email_confirmed_at":"2026-09-18T00:00:00Z"}}
                        """, MediaType.APPLICATION_JSON));
        var session = client.refresh("old-refresh");
        assertEquals("new-access", session.accessToken());
        assertEquals("new-refresh", session.refreshToken());
        server.verify();
    }

    @Test
    void invalidPasswordIsNotReportedAsNetworkFailure() {
        server.expect(requestTo("https://example.invalid/auth/v1/token?grant_type=password"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"invalid_credentials\"}"));
        var error = assertThrows(SupabaseAuthClientException.class, () -> client.login("test@example.invalid", "wrong-password"));
        assertEquals(SupabaseAuthClientException.Reason.INVALID_CREDENTIALS, error.getReason());
    }

    @Test
    void providerOutageIsNotReportedAsRevokedSession() {
        server.expect(requestTo("https://example.invalid/auth/v1/token?grant_type=refresh_token"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                        .body("{}"));
        var error = assertThrows(SupabaseAuthClientException.class, () -> client.refresh("old-refresh"));
        assertEquals(SupabaseAuthClientException.Reason.PROVIDER_UNAVAILABLE, error.getReason());
    }

}
