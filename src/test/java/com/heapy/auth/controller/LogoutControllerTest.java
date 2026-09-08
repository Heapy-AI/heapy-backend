package com.heapy.auth.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.heapy.auth.client.SupabaseLogoutClient;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.GlobalExceptionHandler;
import com.heapy.common.exception.HeapyException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class LogoutControllerTest {
    private SupabaseLogoutClient client;
    private MockMvc mvc;
    private Jwt jwt;

    @BeforeEach
    void 준비() {
        client = mock(SupabaseLogoutClient.class);
        jwt = Jwt.withTokenValue("test-access-token").header("alg", "none")
                .subject(UUID.randomUUID().toString()).build();
        mvc = MockMvcBuilders.standaloneSetup(new LogoutController(client))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.getParameterType() == Jwt.class;
                    }
                    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                            NativeWebRequest request, WebDataBinderFactory factory) {
                        return jwt;
                    }
                }).build();
    }

    @Test
    void 로그아웃_성공은_본문없는_204이다() throws Exception {
        mvc.perform(post("/api/auth/logout").header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(client).logout("test-access-token");
    }

    @Test
    void 인증정보가_없으면_세션을_종료하지_않는다() throws Exception {
        jwt = null;
        mvc.perform(post("/api/auth/logout").header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(client);
    }

    @Test
    void 잘못된_키와_명세에_없는_본문은_거절한다() throws Exception {
        mvc.perform(post("/api/auth/logout")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/logout").header("Idempotency-Key", "invalid"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/logout").header("Idempotency-Key", UUID.randomUUID()).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(client);
    }

    @Test
    void 공급자_장애를_성공으로_반환하지_않는다() throws Exception {
        doThrow(new HeapyException(ErrorCode.AUTH_PROVIDER_UNAVAILABLE)).when(client).logout("test-access-token");
        mvc.perform(post("/api/auth/logout").header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTH-009"));
    }
}
