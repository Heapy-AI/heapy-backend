package com.heapy.auth.controller;

import com.heapy.auth.dto.SignupRequest;
import com.heapy.auth.dto.SignupResponse;
import com.heapy.auth.service.SignupService;
import com.heapy.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "인증")
public class SignupController {
    private final SignupService service;

    public SignupController(SignupService service) {
        this.service = service;
    }

    @PostMapping("/api/auth/signup")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "이메일 회원가입 및 인증 메일 발송", security = {})
    public ResponseEntity<ApiResponse<SignupResponse>> signup(
            @Valid @RequestBody SignupRequest request,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey) {
        SignupResponse response = service.signup(request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, response.emailVerificationRequired()
                        ? "인증 메일을 발송했습니다." : "회원가입이 완료되었습니다. 로그인해 주세요."));
    }
}
