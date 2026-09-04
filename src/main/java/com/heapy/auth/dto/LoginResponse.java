package com.heapy.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(name = "LoginResponse", description = "로그인 세션과 다음 진입 단계")
public record LoginResponse(
        @Schema(description = "Supabase Access Token", example = "eyJhbGciOiJSUzI1NiIs...")
        String accessToken,

        @Schema(description = "Supabase Refresh Token", example = "refresh-token-example")
        String refreshToken,

        @Schema(example = "Bearer")
        String tokenType,

        @Schema(example = "3600")
        long expiresIn,

        @Schema(example = "2026-09-03T10:30:00Z")
        Instant expiresAt,
        LoginUserResponse user,

        @Schema(description = "다음 앱 진입 단계", allowableValues = {"terms", "profile", "home"}, example = "terms")
        String nextStep,

        @Schema(description = "마지막으로 완료한 프로필 단계", minimum = "1", maximum = "6", example = "1")
        Integer onboardingStep
) {

    @Override
    public String toString() {
        return "LoginResponse[accessToken=[REDACTED], refreshToken=[REDACTED], tokenType="
                + tokenType + ", expiresIn=" + expiresIn + ", expiresAt=" + expiresAt
                + ", user=" + user + ", nextStep=" + nextStep
                + ", onboardingStep=" + onboardingStep + "]";
    }
}
