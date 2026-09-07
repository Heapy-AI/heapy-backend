package com.heapy.auth.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.heapy.auth.dto.SignupRequest;
import com.heapy.common.config.SupabaseProperties;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class SupabaseSignupClient {
    private final RestClient client;
    private final String redirectUrl;

    public SupabaseSignupClient(RestClient.Builder builder, SupabaseProperties properties,
            @Value("${heapy.supabase.signup-redirect-url}") String redirectUrl) {
        this.client = builder.baseUrl(properties.url())
                .defaultHeader("apikey", properties.anonKey()).build();
        this.redirectUrl = redirectUrl;
    }

    public SignupResult signup(SignupRequest request) {
        try {
            Settings settings = client.get().uri("/auth/v1/settings")
                    .retrieve().body(Settings.class);
            if (settings == null || settings.mailerAutoconfirm() == null) {
                throw new HeapyException(ErrorCode.AUTH_PROVIDER_UNAVAILABLE);
            }
            return client.post()
                    .uri(builder -> builder.path("/auth/v1/signup")
                            .queryParam("redirect_to", redirectUrl).build())
                    .contentType(MediaType.APPLICATION_JSON).body(request)
                    .exchange((outgoing, response) -> {
                        if (response.getStatusCode().is2xxSuccessful()) {
                            SignupUser user = response.bodyTo(SignupUser.class);
                            UUID id = user == null ? null : user.id() != null ? user.id()
                                    : user.user() == null ? null : user.user().id();
                            if (id == null) {
                                throw new HeapyException(ErrorCode.AUTH_PROVIDER_UNAVAILABLE);
                            }
                            return new SignupResult(id, !settings.mailerAutoconfirm());
                        }
                        SignupError error = response.bodyTo(SignupError.class);
                        String code = error == null ? "" : error.code();
                        if (response.getStatusCode().value() == 429) {
                            throw new HeapyException(ErrorCode.AUTH_RATE_LIMITED);
                        }
                        if ("weak_password".equals(code)) {
                            throw new HeapyException(ErrorCode.PASSWORD_POLICY_VIOLATION);
                        }
                        if ("user_already_exists".equals(code) || "email_exists".equals(code)) {
                            throw new HeapyException(ErrorCode.EMAIL_ALREADY_REGISTERED);
                        }
                        if ("email_address_invalid".equals(code)) {
                            throw new HeapyException(ErrorCode.INVALID_INPUT);
                        }
                        throw new HeapyException(ErrorCode.AUTH_PROVIDER_UNAVAILABLE);
                    });
        } catch (RestClientException exception) {
            throw new HeapyException(ErrorCode.AUTH_PROVIDER_UNAVAILABLE);
        }
    }

    private record Settings(@JsonProperty("mailer_autoconfirm") Boolean mailerAutoconfirm) { }
    public record SignupResult(UUID userId, boolean emailVerificationRequired) { }
    private record SignupUser(UUID id, SignupUser user) { }
    private record SignupError(String code) { }
}
