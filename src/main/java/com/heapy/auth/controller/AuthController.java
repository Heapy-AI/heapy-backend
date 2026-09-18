package com.heapy.auth.controller;

import com.heapy.auth.dto.LoginRequest;
import com.heapy.auth.dto.LoginResponse;
import com.heapy.auth.dto.RefreshRequest;
import com.heapy.auth.service.AuthService;
import com.heapy.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "인증", description = "Supabase Auth를 사용하는 인증 API")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 앱 재실행과 사용 중 만료 시 세션을 이어 간다. @author 김진우 */
    @PostMapping("/refresh")
    @Operation(summary = "로그인 세션 갱신")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(ApiResponse.success(authService.refresh(request.refreshToken()), "로그인을 유지했습니다."));
    }

    @PostMapping("/login")
    @Operation(
            summary = "이메일·비밀번호 로그인",
            description = "이메일을 정규화해 Supabase Auth로 인증하고 약관·프로필 상태에 따른 다음 단계를 반환합니다."
    )
    @LoginApiResponses
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success(response, "로그인되었습니다."));
    }
}
