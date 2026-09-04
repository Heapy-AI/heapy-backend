package com.heapy.auth.dto;

import java.util.UUID;

public record LoginUserResponse(
        UUID userId,
        String email,
        boolean emailVerified
) {
}
