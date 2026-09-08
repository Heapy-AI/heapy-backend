package com.heapy.auth.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.heapy.common.config.SupabaseProperties;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 전체 갱신 토큰 세션을 종료하고 이미 종료된 세션의 재시도를 허용한다.
 *
 * @author 김진우
 */
@Component
public class SupabaseLogoutClient {
    private final RestClient restClient;

    public SupabaseLogoutClient(RestClient.Builder builder, SupabaseProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(7));
        this.restClient = builder.clone()
                .baseUrl(properties.url())
                .defaultHeader("apikey", properties.anonKey())
                .requestFactory(factory)
                .build();
    }

    public void logout(String accessToken) {
        try {
            restClient.post().uri("/auth/v1/logout?scope=global")
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 204) return null;
                        if (status == 401 || status == 403) {
                            MediaType type = response.getHeaders().getContentType();
                            if (type != null && MediaType.APPLICATION_JSON.isCompatibleWith(type)) {
                                LogoutError error = response.bodyTo(LogoutError.class);
                                if (error != null && "session_not_found".equals(error.errorCode())) return null;
                            }
                            throw new HeapyException(ErrorCode.INVALID_ACCESS_TOKEN);
                        }
                        throw new HeapyException(ErrorCode.AUTH_PROVIDER_UNAVAILABLE);
                    });
        } catch (RestClientException exception) {
            throw new HeapyException(ErrorCode.AUTH_PROVIDER_UNAVAILABLE);
        }
    }

    private record LogoutError(@JsonProperty("error_code") String errorCode) {
    }
}
