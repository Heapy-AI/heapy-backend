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
        try {
            return restClient.post()
                    .uri("/auth/v1/token?grant_type=password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new PasswordLoginBody(email, password))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().is2xxSuccessful()) {
                            PasswordLoginResponse body = response.bodyTo(PasswordLoginResponse.class);
                            if (body == null || body.user() == null) {
                                throw new SupabaseAuthClientException(
                                        SupabaseAuthClientException.Reason.PROVIDER_UNAVAILABLE
                                );
                            }
                            long expiresAt = body.expiresAt() == null
                                    ? Instant.now().plusSeconds(body.expiresIn()).getEpochSecond()
                                    : body.expiresAt();
                            return new SupabaseAuthSession(
                                    body.accessToken(),
                                    body.refreshToken(),
                                    body.tokenType(),
                                    body.expiresIn(),
                                    Instant.ofEpochSecond(expiresAt),
                                    body.user().id(),
                                    body.user().email(),
                                    body.user().emailConfirmedAt() != null
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
