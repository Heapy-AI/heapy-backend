package com.heapy.health.controller;

import com.heapy.common.response.ApiResponse;
import com.heapy.health.service.HealthRecordService;
import com.heapy.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 건강 직접 입력과 본인 물 기록 변경을 제공한다. @author 김진우 */
@RestController
@RequestMapping("/api/health")
@SecurityRequirement(name = "bearerAuth")
public class HealthRecordController {
    private final HealthRecordService records;
    public HealthRecordController(HealthRecordService records) { this.records = records; }

    @PostMapping("/{metric}/records")
    @Operation(summary = "수면·생체·물 기록 직접 입력")
    public ResponseEntity<ApiResponse<JsonNode>> create(@AuthenticationPrincipal Jwt jwt, @PathVariable String metric,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody JsonNode body) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(ApiResponse.success(
                records.create(AuthenticatedUser.id(jwt), metric, key, body), "건강 기록을 저장했습니다."));
    }

    @PatchMapping("/water/records/{recordId}")
    @Operation(summary = "앱에서 입력한 본인 물 기록 수정")
    public ResponseEntity<ApiResponse<JsonNode>> edit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID recordId,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody JsonNode body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(
                records.editWater(AuthenticatedUser.id(jwt), recordId, key, body), "물 기록을 수정했습니다."));
    }

    @PostMapping("/water/records/batch-delete")
    @Operation(summary = "앱에서 입력한 본인 물 기록 선택 삭제")
    public ResponseEntity<ApiResponse<JsonNode>> delete(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody JsonNode body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(
                records.deleteWater(AuthenticatedUser.id(jwt), key, body), "물 기록을 삭제했습니다."));
    }
}
