package com.heapy.health.controller;

import com.heapy.common.response.ApiResponse;
import com.heapy.health.model.HealthBriefing;
import com.heapy.health.service.HealthBriefingRefresh;
import com.heapy.security.AuthenticatedUser;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 본인의 오늘 브리핑 조회와 실패 재시도 API. @author 김진우 */
@RestController
@RequestMapping("/api/health/briefings")
public class HealthBriefingController {
    private final HealthBriefingRefresh refresh;

    public HealthBriefingController(HealthBriefingRefresh refresh) {
        this.refresh = refresh;
    }

    @GetMapping("/latest")
    public ResponseEntity<ApiResponse<HealthBriefing>> latest(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(refresh.today(AuthenticatedUser.id(jwt)), "브리핑을 조회했습니다."));
    }

    @PostMapping("/retry")
    public ResponseEntity<ApiResponse<HealthBriefing>> retry(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(refresh.retry(AuthenticatedUser.id(jwt)), "브리핑 상태를 확인했습니다."));
    }
}
