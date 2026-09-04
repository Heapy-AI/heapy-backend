package com.heapy.user.controller;

import com.heapy.common.response.ApiResponse;
import com.heapy.security.AuthenticatedUser;
import com.heapy.user.dto.OnboardingCompleteResponse;
import com.heapy.user.dto.UpdateProfileRequest;
import com.heapy.user.dto.UserProfileResponse;
import com.heapy.user.service.UserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/users/me")
@Tag(name = "내 정보", description = "로그인 사용자 프로필과 온보딩 API")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

    private final UserProfileService userProfileService;

    public UserController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @GetMapping
    @Operation(summary = "로그인 사용자와 온보딩 상태 조회")
    public ResponseEntity<ApiResponse<UserProfileResponse>> getProfile(
            @AuthenticationPrincipal Jwt jwt
    ) {
        UserProfileResponse response = userProfileService.getProfile(AuthenticatedUser.id(jwt));
        return ResponseEntity.ok(ApiResponse.success(response, "사용자 정보를 조회했습니다."));
    }

    @PatchMapping("/profile")
    @Operation(summary = "온보딩 단계별 프로필 저장 또는 완료 후 수정")
    public ResponseEntity<ApiResponse<UserProfileResponse>> updateProfile(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateProfileRequest request
    ) {
        UserProfileResponse response = userProfileService.updateProfile(AuthenticatedUser.id(jwt), request);
        return ResponseEntity.ok(ApiResponse.success(response, "프로필을 저장했습니다."));
    }

    @PostMapping("/onboarding/complete")
    @Operation(summary = "온보딩 필수값 검증 및 완료 확정")
    public ResponseEntity<ApiResponse<OnboardingCompleteResponse>> completeOnboarding(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey
    ) {
        OnboardingCompleteResponse response = userProfileService.completeOnboarding(
                AuthenticatedUser.id(jwt),
                idempotencyKey
        );
        return ResponseEntity.ok(ApiResponse.success(response, "프로필 설정을 완료했습니다."));
    }
}
