package com.heapy.auth.dto;

import java.util.UUID;

public record SignupResponse(
        UUID userId,
        String email,
        boolean emailVerificationRequired,
        boolean verificationEmailSent,
        String nextStep
) {
}
