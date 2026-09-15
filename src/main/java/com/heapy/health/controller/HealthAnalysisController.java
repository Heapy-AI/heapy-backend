package com.heapy.health.controller;

import com.heapy.common.response.ApiResponse;
import com.heapy.health.analysis.HealthAnalysisReader;
import com.heapy.health.analysis.HealthAnalysisRefresh;
import com.heapy.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 오늘의 분석 결과만 조회한다. @author 김진우 */
@RestController
@SecurityRequirement(name = "bearerAuth")
public class HealthAnalysisController {
    private final HealthAnalysisReader reader;
    private final HealthAnalysisRefresh refresh;
    public HealthAnalysisController(HealthAnalysisReader reader, HealthAnalysisRefresh refresh) {
        this.reader = reader; this.refresh = refresh;
    }

    @PostMapping("/api/health/analyses/retry")
    @Operation(summary = "오늘의 실패한 건강 분석 재시도")
    public ResponseEntity<ApiResponse<Map<String, Object>>> retry(@AuthenticationPrincipal Jwt jwt,
            @RequestParam String category) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(
                refresh.retry(AuthenticatedUser.id(jwt), category), "건강 분석 상태를 확인했습니다."));
    }

    @GetMapping("/api/health/analyses/today")
    @Operation(summary = "오늘의 건강 분석 조회, 재생성하지 않음")
    public ResponseEntity<ApiResponse<Map<String, Object>>> today(@AuthenticationPrincipal Jwt jwt,
            @RequestParam String category) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(
                reader.today(AuthenticatedUser.id(jwt), category), "오늘의 건강 분석 상태를 조회했습니다."));
    }
}
