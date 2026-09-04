package com.heapy.terms.controller;

import com.heapy.common.response.ErrorResponse;
import com.heapy.terms.dto.SaveConsentsSuccessOpenApiResponse;
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
                responseCode = "201",
                description = "약관 동의 상태 저장 성공",
                content = @Content(schema = @Schema(implementation = SaveConsentsSuccessOpenApiResponse.class))
        ),
        @ApiResponse(
                responseCode = "400",
                description = "입력값 검증 실패(COMMON-001)",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @ApiResponse(
                responseCode = "401",
                description = "Access Token 검증 실패(AUTH-003)",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        ),
        @ApiResponse(
                responseCode = "409",
                description = "필수 약관 철회 또는 멱등성 키 충돌",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))
        )
})
public @interface SaveConsentsApiResponses {
}
