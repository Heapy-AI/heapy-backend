package com.heapy.checkup;

import com.heapy.checkup.OcrModels.Confirmation;
import com.heapy.checkup.OcrModels.Confirmed;
import com.heapy.checkup.OcrModels.JobResponse;
import com.heapy.common.response.ApiResponse;
import com.heapy.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/checkups/ocr-jobs")
@Tag(name = "건강검진 OCR")
@SecurityRequirement(name = "bearerAuth")
public class CheckupOcrController {
    private final OcrService service;

    public CheckupOcrController(OcrService service) { this.service = service; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "건강검진 OCR 작업 생성")
    public ResponseEntity<ApiResponse<JobResponse>> upload(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key,
            @RequestParam String inputType, @RequestPart MultipartFile file) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(ApiResponse.success(
                service.upload(AuthenticatedUser.id(jwt), key, inputType, file), "건강검진 OCR 작업을 접수했습니다."));
    }

    @GetMapping("/{jobId}")
    @Operation(summary = "OCR 작업 상태와 임시 검수 결과 조회")
    public ResponseEntity<ApiResponse<JobResponse>> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID jobId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(
                service.get(AuthenticatedUser.id(jwt), jobId), "OCR 작업을 조회했습니다."));
    }

    @PostMapping("/{jobId}/confirm")
    @Operation(summary = "검수한 건강검진 결과 확정")
    public ResponseEntity<ApiResponse<Confirmed>> confirm(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID jobId, @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody Confirmation body) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(ApiResponse.success(
                service.confirm(AuthenticatedUser.id(jwt), jobId, key, body), "건강검진 결과를 확정했습니다."));
    }

    @DeleteMapping("/{jobId}")
    @Operation(summary = "화면 이탈 시 미확정 OCR 작업 종료 및 임시 결과 정리")
    public ResponseEntity<Void> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID jobId) {
        service.cancel(AuthenticatedUser.id(jwt), jobId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
