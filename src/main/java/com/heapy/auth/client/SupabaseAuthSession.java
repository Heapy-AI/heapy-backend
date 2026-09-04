package com.heapy.auth.client;

import java.time.Instant;
import java.util.UUID;

public record SupabaseAuthSession(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        Instant expiresAt,
        UUID userId,
        String email,
        boolean emailVerified
) {

    @Override
    public String toString() {
        return "SupabaseAuthSession[accessToken=[REDACTED], refreshToken=[REDACTED], tokenType="
                + tokenType + ", expiresIn=" + expiresIn + ", expiresAt=" + expiresAt
                + ", userId=" + userId + ", email=" + email
                + ", emailVerified=" + emailVerified + "]";
    }
}
