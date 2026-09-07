package com.heapy.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.heapy.auth.dto.SignupResponse;
import com.heapy.auth.service.SignupService;
import com.heapy.common.exception.GlobalExceptionHandler;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SignupControllerTest {
    @Test
    void 회원가입_성공은_토큰없이_201을_반환한다() throws Exception {
        SignupService service = mock(SignupService.class);
        when(service.signup(any(), any())).thenReturn(new SignupResponse(
                UUID.randomUUID(), "test@example.com", true, true, "emailVerification"));
        var mvc = MockMvcBuilders.standaloneSetup(new SignupController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/api/auth/signup").header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"test@example.com\",\"password\":\"Strong123!\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.emailVerificationRequired").value(true))
                .andExpect(jsonPath("$.data.nextStep").value("emailVerification"))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist());
    }

    @Test
    void 멱등성_키가_없으면_가입하지_않는다() throws Exception {
        SignupService service = mock(SignupService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new SignupController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"test@example.com\",\"password\":\"Strong123!\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
