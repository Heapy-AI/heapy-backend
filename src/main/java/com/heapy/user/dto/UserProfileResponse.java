package com.heapy.user.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record UserProfileResponse(
        UUID userId,
        String name,
        LocalDate birthDate,
        String sex,
        BigDecimal heightCm,
        BigDecimal weightKg,
        String smokingStatus,
        String alcoholFrequency,
        List<ProfileOptionResponse> chronicConditions,
        List<ProfileOptionResponse> allergies,
        String healthCautions,
        int onboardingStep,
        boolean onboardingCompleted,
        Instant onboardingCompletedAt,
        boolean requiredConsentCompleted
) {
}
