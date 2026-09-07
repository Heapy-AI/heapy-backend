package com.heapy.auth.client;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.heapy.auth.dto.SignupRequest;
import com.heapy.common.config.SupabaseProperties;
import com.heapy.common.exception.HeapyException;
import com.heapy.common.exception.ErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SupabaseSignupClientTest {
    @Test
    void 이메일을_정규화하고_가입과_메일발송을_요청한다() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new SupabaseSignupClient(builder,
                new SupabaseProperties("https://auth.example", "publishable"), "heapy://auth/callback");
        server.expect(requestTo("https://auth.example/auth/v1/settings"))
                .andRespond(withSuccess("{\"mailer_autoconfirm\":false}", MediaType.APPLICATION_JSON));
        UUID id = UUID.randomUUID();
        server.expect(requestTo("https://auth.example/auth/v1/signup?redirect_to=heapy://auth/callback"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("apikey", "publishable"))
                .andExpect(content().json("{\"email\":\"test@example.com\",\"password\":\"Strong123!\"}"))
                .andRespond(withSuccess("{\"id\":\"" + id + "\"}", MediaType.APPLICATION_JSON));
        var result = client.signup(new SignupRequest(" Test@Example.COM ", "Strong123!"));
        assertThat(result.userId()).isEqualTo(id);
        assertThat(result.emailVerificationRequired()).isTrue();
        server.verify();
    }

    @Test
    void 이메일_자동인증이면_중첩_사용자를_읽고_인증을_요구하지_않는다() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new SupabaseSignupClient(builder,
                new SupabaseProperties("https://auth.example", "publishable"), "heapy://auth/callback");
        server.expect(requestTo("https://auth.example/auth/v1/settings"))
                .andRespond(withSuccess("{\"mailer_autoconfirm\":true}", MediaType.APPLICATION_JSON));
        UUID id = UUID.randomUUID();
        server.expect(method(HttpMethod.POST)).andRespond(withSuccess(
                "{\"user\":{\"id\":\"" + id + "\"},\"access_token\":\"not-returned\"}", MediaType.APPLICATION_JSON));
        var result = client.signup(new SignupRequest("test@example.com", "Strong123!"));
        assertThat(result.userId()).isEqualTo(id);
        assertThat(result.emailVerificationRequired()).isFalse();
        server.verify();
    }

    @Test
    void 비밀번호_정책오류를_422로_변환한다() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new SupabaseSignupClient(builder,
                new SupabaseProperties("https://auth.example", "publishable"), "heapy://auth/callback");
        server.expect(requestTo("https://auth.example/auth/v1/settings"))
                .andRespond(withSuccess("{\"mailer_autoconfirm\":false}", MediaType.APPLICATION_JSON));
        server.expect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"weak_password\"}"));
        assertThatThrownBy(() -> client.signup(new SignupRequest("test@example.com", "Strong123!")))
                .isInstanceOfSatisfying(HeapyException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PASSWORD_POLICY_VIOLATION));
        server.verify();
    }
}
