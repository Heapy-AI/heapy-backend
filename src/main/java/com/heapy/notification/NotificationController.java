package com.heapy.notification;

import com.heapy.common.response.ApiResponse;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import com.heapy.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.UUID;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 인증된 기기의 알림 등록과 열람을 처리한다. @author 김진우 */
@RestController
public class NotificationController {
    private final DeviceTokenService devices;
    private final NotificationInboxService notifications;
    private final PushGateway gateway;
    public NotificationController(DeviceTokenService devices, NotificationInboxService notifications, PushGateway gateway) {
        this.devices = devices; this.notifications = notifications;
        this.gateway = gateway;
    }
    @PostMapping("/api/devices/push-tokens")
    public ResponseEntity<?> register(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody DeviceTokenService.Registration body) {
        if (!gateway.enabled()) throw new HeapyException(ErrorCode.PUSH_UNAVAILABLE);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(devices.register(AuthenticatedUser.id(jwt), body), "알림 기기를 등록했습니다."));
    }
    @DeleteMapping("/api/devices/push-tokens/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        devices.deactivate(AuthenticatedUser.id(jwt), id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @PostMapping("/api/notifications/{id}/open")
    public ResponseEntity<?> open(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") UUID key) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(notifications.open(AuthenticatedUser.id(jwt), id), "알림을 확인했습니다."));
    }
    @GetMapping("/api/notifications")
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="all") String status, @RequestParam(required=false) String notificationType,
            @RequestParam(required=false) String cursor, @RequestParam(defaultValue="20") int limit) {
        var page=notifications.list(AuthenticatedUser.id(jwt),status,notificationType,cursor,limit);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ApiResponse<>(true,page.data(),"알림 목록을 조회했습니다.",page.meta()));
    }
    @PostMapping("/api/notifications/read-all")
    public ResponseEntity<?> readAll(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") UUID key) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(Map.of("updatedCount",notifications.readAll(AuthenticatedUser.id(jwt))),"모든 알림을 읽음 처리했습니다."));
    }
}
