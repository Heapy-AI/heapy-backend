package com.heapy.health;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.heapy.common.exception.GlobalExceptionHandler;
import com.heapy.health.controller.HealthConnectionController;
import com.heapy.health.dto.HealthConnectionResponse;
import com.heapy.health.service.HealthConnectionService;
import com.heapy.health.service.HealthConnectionService.SaveResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.bind.support.WebDataBinderFactory;

class HealthConnectionControllerTest {
    private HealthConnectionService service;
    private MockMvc mvc;
    private static final String BODY = """
            {"deviceInstallationId":"f64ee285-7ec2-48e0-bd0d-31dc759531f9",
             "grantedDataTypes":["steps"],"sdkVersion":"1.1.0",
             "permissionCheckedAt":"2026-09-07T10:00:00Z"}
            """;

    @BeforeEach
    void prepare() {
        service = mock(HealthConnectionService.class);
        mvc = MockMvcBuilders.standaloneSetup(new HealthConnectionController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.getParameterType() == Jwt.class;
                    }
                    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                            NativeWebRequest request, WebDataBinderFactory factory) {
                        return Jwt.withTokenValue("테스트").header("alg", "none")
                                .subject("9cf0cf52-b838-4f29-9756-858d45038ca5").build();
                    }
                }).build();
    }

    @Test
    void 실제앱요청으로_연결객체와_201을_반환한다() throws Exception {
        when(service.save(any(), any(), any())).thenReturn(new SaveResult(201,
                new HealthConnectionResponse(UUID.randomUUID(), "samsung_health", UUID.randomUUID(),
                        "connected", List.of("steps"), "1.1.0", Instant.now(), null)));
        mvc.perform(post("/api/health-connections/samsung").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID()).content(BODY))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("connected"))
                .andExpect(jsonPath("$.data.grantedDataTypes[0]").value("steps"));
    }

    @Test
    void 잘못된권한과_필수필드누락과_키오류는_저장하지_않는다() throws Exception {
        for (String body : List.of(BODY.replace("steps", "unknown"), BODY.replace("1.1.0", ""),
                BODY.replace("2026-09-07T10:00:00Z", "잘못된시각"), "{}")) {
            mvc.perform(post("/api/health-connections/samsung").contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", UUID.randomUUID()).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/health-connections/samsung").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/health-connections/samsung").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "invalid").content(BODY)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void 없는경로와_잘못된메서드는_500이_아니다() throws Exception {
        mvc.perform(get("/api/없는경로")).andExpect(status().isNotFound());
        mvc.perform(get("/api/health-connections/samsung")).andExpect(status().isMethodNotAllowed());
    }
}
