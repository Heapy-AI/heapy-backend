package com.heapy.health.controller;

import com.heapy.common.response.ApiResponse;
import com.heapy.health.dto.HealthConnectionResponse;
import com.heapy.health.dto.SamsungConnectionRequest;
import com.heapy.health.service.HealthConnectionService;
import com.heapy.health.service.HealthConnectionService.SaveResult;
import com.heapy.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health-connections")
@Tag(name = "건강 데이터 연결", description = "삼성 헬스 연결과 읽기 권한 상태")
@SecurityRequirement(name = "bearerAuth")
public class HealthConnectionController {
    private final HealthConnectionService service;

    public HealthConnectionController(HealthConnectionService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "내 건강 데이터 연결 상태 조회")
    public ResponseEntity<ApiResponse<List<HealthConnectionResponse>>> findAll(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ApiResponse.success(service.findAll(AuthenticatedUser.id(jwt)),
                "건강 데이터 연결 상태를 조회했습니다."));
    }

    @PostMapping("/samsung")
    @Operation(summary = "삼성 헬스 연결 등록·갱신")
    public ResponseEntity<ApiResponse<HealthConnectionResponse>> save(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID requestKey,
            @Valid @RequestBody SamsungConnectionRequest request
    ) {
        SaveResult result = service.save(AuthenticatedUser.id(jwt), requestKey, request);
        return ResponseEntity.status(result.status()).body(
                ApiResponse.success(result.connection(), "건강 데이터 연결 정보를 저장했습니다."));
    }
}
