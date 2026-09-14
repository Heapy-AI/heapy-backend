package com.heapy.mission.controller;

import com.heapy.common.response.ApiResponse;
import com.heapy.mission.dto.MissionCalendarResponse;
import com.heapy.mission.dto.MissionDetailResponse;
import com.heapy.mission.dto.MissionFeedbackRequest;
import java.time.LocalDate;
import com.heapy.mission.dto.MissionTodayResponse;
import com.heapy.mission.service.MissionService;
import com.heapy.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/missions")
@Tag(name = "미션", description = "오늘 미션, 진행도, 완료, 피드백, 월간 달력 API")
@SecurityRequirement(name = "bearerAuth")
public class MissionController {

    private final MissionService missionService;

    public MissionController(MissionService missionService) {
        this.missionService = missionService;
    }

    @GetMapping("/today")
    @Operation(summary = "오늘 미션 목록 조회")
    public ResponseEntity<ApiResponse<MissionTodayResponse>> today(@AuthenticationPrincipal Jwt jwt) {
        return ok(missionService.today(AuthenticatedUser.id(jwt)), "오늘의 미션을 조회했습니다.");
    }

    @GetMapping("/by-date")
    public ResponseEntity<ApiResponse<MissionTodayResponse>> byDate(@AuthenticationPrincipal Jwt jwt,@RequestParam LocalDate date) {
        return ok(missionService.byDate(AuthenticatedUser.id(jwt),date),"날짜별 미션을 조회했습니다.");
    }

    @GetMapping("/{missionId}")
    @Operation(summary = "미션 상세 조회")
    public ResponseEntity<ApiResponse<MissionDetailResponse>> detail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID missionId
    ) {
        return ok(missionService.detail(AuthenticatedUser.id(jwt), missionId), "미션 상세를 조회했습니다.");
    }

    @PatchMapping("/{missionId}/progress")
    @Operation(summary = "미션 진행도 갱신")
    public ResponseEntity<ApiResponse<MissionDetailResponse>> progress(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID missionId
    ) {
        return ok(missionService.updateProgress(
                AuthenticatedUser.id(jwt), missionId), "미션 진행도를 갱신했습니다.");
    }

    @PostMapping("/{missionId}/complete")
    @Operation(summary = "달성 가능한 미션 완료 처리")
    public ResponseEntity<ApiResponse<MissionDetailResponse>> complete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID missionId
    ) {
        return ok(missionService.complete(AuthenticatedUser.id(jwt), missionId), "미션을 완료했습니다.");
    }

    @PostMapping("/{missionId}/feedback")
    @Operation(summary = "완료 미션 난이도 피드백 저장")
    public ResponseEntity<ApiResponse<MissionDetailResponse>> feedback(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID missionId,
            @Valid @RequestBody MissionFeedbackRequest request
    ) {
        return ok(missionService.feedback(
                AuthenticatedUser.id(jwt), missionId, request.difficulty()), "미션 피드백을 저장했습니다.");
    }

    @GetMapping("/calendar")
    @Operation(summary = "월별 미션 달성률 조회")
    public ResponseEntity<ApiResponse<MissionCalendarResponse>> calendar(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam int year,
            @RequestParam int month
    ) {
        return ok(missionService.calendar(AuthenticatedUser.id(jwt), year, month), "월별 미션 기록을 조회했습니다.");
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T data, String message) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(data, message));
    }
}
