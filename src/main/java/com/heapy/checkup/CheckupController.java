package com.heapy.checkup;

import com.heapy.checkup.OcrModels.Detail;
import com.heapy.checkup.CheckupHistoryService.RecordSummary;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import com.heapy.common.response.ApiResponse;
import com.heapy.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 임시 OCR 수명과 분리된 정식 검진 기록의 소유자 조회를 제공한다.
 * @author 김진우
 */
@RestController
@RequestMapping("/api/checkups")
@Tag(name = "건강검진")
@SecurityRequirement(name = "bearerAuth")
public class CheckupController {
    private final OcrRepository repository;
    private final CheckupHistoryService history;

    public CheckupController(OcrRepository repository, CheckupHistoryService history) {
        this.repository = repository;
        this.history = history;
    }

    @GetMapping
    @Operation(summary = "본인 건강검진 회차 목록 조회")
    public ResponseEntity<ApiResponse<List<RecordSummary>>> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String cursor) {
        var page = history.list(AuthenticatedUser.id(jwt), limit, cursor);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                new ApiResponse<>(true, page.records(), "건강검진 목록을 조회했습니다.", page.meta()));
    }

    @GetMapping("/{recordId}")
    @Transactional(readOnly = true)
    @Operation(summary = "확정 건강검진 결과와 소견 상세 조회")
    public ResponseEntity<ApiResponse<Detail>> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID recordId) {
        Detail detail = repository.detail(AuthenticatedUser.id(jwt), recordId)
                .orElseThrow(() -> new HeapyException(ErrorCode.RESOURCE_NOT_FOUND));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(detail, "건강검진 상세를 조회했습니다."));
    }
}
