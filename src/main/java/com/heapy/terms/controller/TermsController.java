package com.heapy.terms.controller;

import com.heapy.common.response.ApiResponse;
import com.heapy.security.AuthenticatedUser;
import com.heapy.terms.dto.SaveConsentsRequest;
import com.heapy.terms.dto.SaveConsentsResponse;
import com.heapy.terms.dto.TermsResponse;
import com.heapy.terms.service.TermsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@Tag(name = "약관", description = "현재 약관 조회와 동의 이력 API")
@SecurityRequirement(name = "bearerAuth")
public class TermsController {

    private final TermsService termsService;

    public TermsController(TermsService termsService) {
        this.termsService = termsService;
    }

    @GetMapping("/api/terms")
    @Operation(summary = "현재 효력이 있는 약관 조회")
    public ResponseEntity<ApiResponse<List<TermsResponse>>> getTerms(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "true") boolean includeOptional
    ) {
        List<TermsResponse> response = termsService.getCurrentTerms(
                AuthenticatedUser.id(jwt),
                includeOptional
        );
        return ResponseEntity.ok(ApiResponse.success(response, "약관을 조회했습니다."));
    }

    @PostMapping("/api/users/me/consents")
    @Operation(summary = "약관 동의·철회 이벤트 저장")
    @SaveConsentsApiResponses
    public ResponseEntity<ApiResponse<SaveConsentsResponse>> saveConsents(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody SaveConsentsRequest request
    ) {
        SaveConsentsResponse response = termsService.saveConsents(
                AuthenticatedUser.id(jwt),
                idempotencyKey,
                request
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "약관 동의 상태를 저장했습니다."));
    }
}
