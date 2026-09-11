package com.heapy.health.controller;

import com.heapy.common.response.ApiResponse;
import com.heapy.health.service.HealthSyncService;
import com.heapy.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 인증된 사용자의 삼성헬스 배치와 완료 지점을 제공한다. @author 김진우 */
@RestController
@SecurityRequirement(name = "bearerAuth")
public class HealthSyncController {
    private final HealthSyncService sync;
    public HealthSyncController(HealthSyncService sync) { this.sync = sync; }
    @PostMapping("/api/health-sync-runs")
    @Operation(summary = "삼성헬스 원본 최대 500건 동기화", description = "배치 저장 완료 후 succeeded를 반환합니다. 마지막 페이지에만 through를 지정합니다.")
    public ResponseEntity<ApiResponse<JsonNode>> save(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") UUID key, @RequestBody JsonNode body) {
        return response(sync.save(AuthenticatedUser.id(jwt), key, body));
    }
    @GetMapping("/api/health-sync-state")
    @Operation(summary = "현재 설치의 항목별 동기화 완료 지점")
    public ResponseEntity<ApiResponse<JsonNode>> state(@AuthenticationPrincipal Jwt jwt, @RequestParam UUID connectionId) {
        return response(sync.state(AuthenticatedUser.id(jwt), connectionId));
    }
    @GetMapping("/api/health-sync-runs/{syncRunId}")
    @Operation(summary = "본인 동기화 배치 결과")
    public ResponseEntity<ApiResponse<JsonNode>> run(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID syncRunId) {
        return response(sync.run(AuthenticatedUser.id(jwt), syncRunId));
    }
    private ResponseEntity<ApiResponse<JsonNode>> response(JsonNode data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(data, "삼성헬스 동기화 결과입니다."));
    }
}
