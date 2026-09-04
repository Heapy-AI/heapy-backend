package com.heapy.auth.controller;

import com.heapy.auth.dto.LoginSuccessOpenApiResponse;
import com.heapy.common.response.ErrorResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "로그인 성공",
                content = @Content(schema = @Schema(implementation = LoginSuccessOpenApiResponse.class))
        ),
        @ApiResponse(
                responseCode = "400",
                description = "입력값 검증 실패(COMMON-001)",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @ApiResponse(
                responseCode = "401",
                description = "이메일 또는 비밀번호 불일치(AUTH-001)",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @ApiResponse(
                responseCode = "403",
                description = "이메일 인증 미완료(AUTH-002)",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @ApiResponse(
                responseCode = "429",
                description = "인증 요청 제한 초과(AUTH-008)",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @ApiResponse(
                responseCode = "503",
                description = "Supabase Auth 연결 장애(AUTH-009)",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
})
public @interface LoginApiResponses {
}
