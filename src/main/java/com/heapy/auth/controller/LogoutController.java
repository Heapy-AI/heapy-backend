package com.heapy.auth.controller;

import com.heapy.auth.client.SupabaseLogoutClient;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import com.heapy.security.AuthenticatedUser;
import com.heapy.notification.DeviceTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "인증")
@SecurityRequirement(name = "bearerAuth")
public class LogoutController {
    private final SupabaseLogoutClient client;
    private final DeviceTokenService devices;

    public LogoutController(SupabaseLogoutClient client, DeviceTokenService devices) {
        this.client = client;
        this.devices = devices;
    }

    @PostMapping("/api/auth/logout")
    @Operation(summary = "로그아웃 및 전체 갱신 토큰 세션 종료")
    public ResponseEntity<Void> logout(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID requestKey,
            @RequestBody(required = false) String body
    ) {
        UUID user = AuthenticatedUser.id(jwt);
        if (body != null && !body.isBlank()) throw new HeapyException(ErrorCode.INVALID_INPUT);
        devices.deactivateAll(user);
        client.logout(jwt.getTokenValue());
        return ResponseEntity.noContent().build();
    }
}
