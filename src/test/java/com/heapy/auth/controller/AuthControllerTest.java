package com.heapy.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.heapy.auth.service.AuthService;
import com.heapy.common.exception.GlobalExceptionHandler;
import com.heapy.common.web.RequestTraceFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class AuthControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AuthService authService = mock(AuthService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .addFilters(new RequestTraceFilter())
                .build();
    }

    @Test
    void 요청값_검증_실패는_공통_오류_응답을_반환한다() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","password":"short"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON-001"))
                .andExpect(jsonPath("$.path").value("/api/auth/login"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.errors.length()").value(2));
    }

    @Test
    void 검증_오류에서도_비밀번호_값을_노출하지_않는다() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"heapy@example.com","password":"secret"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].value").value("[REDACTED]"));
    }
}
