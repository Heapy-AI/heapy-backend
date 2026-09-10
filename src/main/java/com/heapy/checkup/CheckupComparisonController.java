package com.heapy.checkup;

import com.heapy.common.response.ApiResponse;
import com.heapy.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 비교할 두 회차의 소유권을 확인한다. @author 김진우 */
@RestController
@SecurityRequirement(name = "bearerAuth")
public class CheckupComparisonController {
    private final CheckupComparisonService service;
    public CheckupComparisonController(CheckupComparisonService service) { this.service = service; }

    @GetMapping("/api/checkups/comparison")
    @Operation(summary = "본인 검진 두 회차 비교, 이전·현재 순서로 ID 전달")
    public ResponseEntity<ApiResponse<Map<String, Object>>> compare(@AuthenticationPrincipal Jwt jwt,
            @RequestParam List<UUID> recordIds) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(
                service.compare(AuthenticatedUser.id(jwt), recordIds), "건강검진을 비교했습니다."));
    }
}
