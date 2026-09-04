package com.heapy.user.dto;

import java.time.Instant;

public record OnboardingCompleteResponse(
        boolean onboardingCompleted,
        int onboardingStep,
        Instant onboardingCompletedAt,
        String nextStep
) {
}
