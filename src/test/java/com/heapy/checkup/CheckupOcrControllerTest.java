package com.heapy.checkup;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.heapy.common.exception.GlobalExceptionHandler;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class CheckupOcrControllerTest {
    private OcrService service;
    private MockMvc mvc;

    @BeforeEach
    void 준비() {
        service = mock(OcrService.class);
        mvc = MockMvcBuilders.standaloneSetup(new CheckupOcrController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(MethodParameter parameter) { return parameter.getParameterType() == Jwt.class; }
                    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                            NativeWebRequest request, WebDataBinderFactory factory) {
                        return Jwt.withTokenValue("fixture").header("alg", "none").subject(UUID.randomUUID().toString()).build();
                    }
                }).build();
    }

    @Test
    void 필수_파일과_멱등성키가_없으면_400이다() throws Exception {
        mvc.perform(multipart("/api/checkups/ocr-jobs").param("inputType", "pdf")
                .header("Idempotency-Key", UUID.randomUUID())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/checkups/ocr-jobs").file("file", "%PDF-1.4".getBytes())
                .param("inputType", "pdf")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void 검증_실패에_건강값을_노출하지_않는다() throws Exception {
        String value = "민감한합성결과".repeat(300);
        String body = """
                {"measuredAt":"2026-08-12","providerName":"합성 기관",
                 "results":[{"itemCode":"FASTING_GLUCOSE","value":"%s","numericValue":null}],"corrections":[]}
                """.formatted(value);
        mvc.perform(post("/api/checkups/ocr-jobs/" + UUID.randomUUID() + "/confirm")
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors[0].value").value("[REDACTED]"));
        verifyNoInteractions(service);
    }
}
