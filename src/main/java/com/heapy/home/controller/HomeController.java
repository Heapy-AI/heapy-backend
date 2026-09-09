package com.heapy.home.controller;

import com.heapy.common.response.ApiResponse;
import com.heapy.home.dto.HomeResponse;
import com.heapy.home.service.HomeService;
import com.heapy.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/home")
@Tag(name = "홈", description = "홈 카드 통합 조회 API")
@SecurityRequirement(name = "bearerAuth")
public class HomeController {

    private final HomeService homeService;

    public HomeController(HomeService homeService) {
        this.homeService = homeService;
    }

    @GetMapping
    @Operation(summary = "홈 카드 전체 조합 조회")
    public ResponseEntity<ApiResponse<HomeResponse>> getHome(@AuthenticationPrincipal Jwt jwt) {
        HomeResponse response = homeService.getHome(AuthenticatedUser.id(jwt));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(response, "홈 화면을 조회했습니다."));
    }
}
