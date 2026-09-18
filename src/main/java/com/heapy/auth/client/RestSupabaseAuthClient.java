package com.heapy.auth.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.heapy.common.config.SupabaseProperties;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class RestSupabaseAuthClient implements SupabaseAuthClient {

    private final RestClient restClient;

    public RestSupabaseAuthClient(RestClient.Builder builder, SupabaseProperties properties) {
        this.restClient = builder
                .baseUrl(properties.url())
                .defaultHeader("apikey", properties.anonKey())
                .build();
    }

    @Override
    public SupabaseAuthSession login(String email, String password) {
        return exchangeTokens("password", new PasswordLoginBody(email, password));
    }

    /** 만료된 액세스 토큰 대신 갱신 토큰으로 새 세션 쌍을 받는다. @author 김진우 */
    @Override
    public SupabaseAuthSession refresh(String refreshToken) {
        return exchangeTokens("refresh_token", new RefreshBody(refreshToken));
    }

    private SupabaseAuthSession exchangeTokens(String grantType, Object body) {
        try {
            return restClient.post()
                    .uri("/auth/v1/token?grant_type=" + grantType)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().is2xxSuccessful()) {
                            PasswordLoginResponse tokens = response.bodyTo(PasswordLoginResponse.class);
                            if (tokens == null || tokens.user() == null) {
                                throw new SupabaseAuthClientException(
                                        SupabaseAuthClientException.Reason.PROVIDER_UNAVAILABLE
                                );
                            }
                            long expiresAt = tokens.expiresAt() == null
                                    ? Instant.now().plusSeconds(tokens.expiresIn()).getEpochSecond()
                                    : tokens.expiresAt();
                            return new SupabaseAuthSession(
                                    tokens.accessToken(),
                                    tokens.refreshToken(),
                                    tokens.tokenType(),
                                    tokens.expiresIn(),
                                    Instant.ofEpochSecond(expiresAt),
                                    tokens.user().id(),
                                    tokens.user().email(),
                                    tokens.user().emailConfirmedAt() != null
                            );
                        }
                        SupabaseErrorBody error = response.bodyTo(SupabaseErrorBody.class);
                        throw mapError(response.getStatusCode().value(), error);
                    });
        } catch (SupabaseAuthClientException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new SupabaseAuthClientException(
                    SupabaseAuthClientException.Reason.PROVIDER_UNAVAILABLE,
                    exception
            );
        }
    }

    private SupabaseAuthClientException mapError(int status, SupabaseErrorBody error) {
        String code = error == null ? "" : safe(error.code()) + " " + safe(error.errorCode());
        String message = error == null ? "" : safe(error.message()) + " " + safe(error.msg());
        String details = (code + " " + message).toLowerCase(Locale.ROOT);
        if (details.contains("email_not_confirmed") || details.contains("email not confirmed")) {
            return new SupabaseAuthClientException(SupabaseAuthClientException.Reason.EMAIL_NOT_VERIFIED);
        }
        if (status == 429) {
            return new SupabaseAuthClientException(SupabaseAuthClientException.Reason.RATE_LIMITED);
        }
        if (status >= 500) {
            return new SupabaseAuthClientException(SupabaseAuthClientException.Reason.PROVIDER_UNAVAILABLE);
        }
        return new SupabaseAuthClientException(SupabaseAuthClientException.Reason.INVALID_CREDENTIALS);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record PasswordLoginBody(String email, String password) {
    }

    private record RefreshBody(@JsonProperty("refresh_token") String refreshToken) {
        @Override public String toString() { return "RefreshBody[refreshToken=[REDACTED]]"; }
    }

    private record PasswordLoginResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") long expiresIn,
            @JsonProperty("expires_at") Long expiresAt,
            SupabaseUser user
    ) {
    }

    private record SupabaseUser(
            UUID id,
            String email,
            @JsonProperty("email_confirmed_at") Instant emailConfirmedAt
    ) {
    }

    private record SupabaseErrorBody(
            String code,
            @JsonProperty("error_code") String errorCode,
            String message,
            String msg
    ) {
    }
}
