package com.heapy.terms.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "SaveConsentsSuccessResponse", description = "약관 동의 저장 성공 공통 응답")
public record SaveConsentsSuccessOpenApiResponse(
        @Schema(example = "true") boolean success,
        SaveConsentsResponse data,
        @Schema(example = "약관 동의 상태를 저장했습니다.") String message,
        @Schema(nullable = true) Object meta
) {
}
