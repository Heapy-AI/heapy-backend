package com.heapy.auth.dto;

import java.time.Instant;

public record LoginResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        Instant expiresAt,
        LoginUserResponse user,
        String nextStep,
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
