package com.heapy.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "LoginUserResponse", description = "로그인 사용자 정보")
public record LoginUserResponse(
        @Schema(example = "9cf0cf52-b838-4f29-9756-858d45038ca5")
        UUID userId,

        @Schema(example = "heapy@example.com")
        String email,

        @Schema(example = "true")
        boolean emailVerified
) {
}
