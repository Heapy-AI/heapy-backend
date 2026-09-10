package com.heapy.health.controller;

import com.heapy.common.response.ApiResponse;
import com.heapy.health.model.HealthData.Page;
import com.heapy.health.service.HealthQueryService;
import com.heapy.health.service.HealthSummaryService;
import com.heapy.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 내 건강 지표와 기록을 인증 사용자에게만 제공한다. @author 김진우 */
@RestController
@RequestMapping("/api/health")
@Tag(name = "내 건강")
@SecurityRequirement(name = "bearerAuth")
public class HealthController {
    private final HealthQueryService query;
    private final HealthSummaryService summary;
    public HealthController(HealthQueryService query, HealthSummaryService summary) { this.query = query; this.summary = summary; }

    @GetMapping("/summary")
    @Operation(summary = "내 건강 통합 리포트의 기록과 연결 상태 조회")
    public ResponseEntity<ApiResponse<Map<String, Object>>> summary(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "7d") String period, @RequestParam(required = false) LocalDate baseDate) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(
                summary.find(AuthenticatedUser.id(jwt), period, baseDate), "내 건강 요약을 조회했습니다."));
    }

    @GetMapping("/{metric}")
    @Operation(summary = "내 건강 기간별 기록·그래프 조회")
    public ResponseEntity<ApiResponse<Page>> find(@AuthenticationPrincipal Jwt jwt, @PathVariable String metric,
            @RequestParam(defaultValue = "7d") String period, @RequestParam(required = false) LocalDate baseDate,
            @RequestParam(required = false) String aggregation, @RequestParam(required = false) String bioType,
            @RequestParam(defaultValue = "100") int limit, @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(
                query.find(AuthenticatedUser.id(jwt), metric, period, baseDate, aggregation, bioType, limit, cursor),
                "건강 기록을 조회했습니다."));
    }
}
