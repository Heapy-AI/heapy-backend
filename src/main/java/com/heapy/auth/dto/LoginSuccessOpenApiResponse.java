package com.heapy.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "LoginSuccessResponse", description = "로그인 성공 공통 응답")
public record LoginSuccessOpenApiResponse(
        @Schema(example = "true")
        boolean success,

        LoginResponse data,

        @Schema(example = "로그인되었습니다.")
        String message,

        @Schema(nullable = true)
        Object meta
) {
}
