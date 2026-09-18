package com.heapy.auth.controller;

import com.heapy.auth.client.SupabaseLogoutClient;
import com.heapy.common.exception.HeapyException;
import com.heapy.notification.DeviceTokenService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 기존 로그아웃의 기기 토큰 정리와 입력 검증을 보존한다. @author 김진우 */
class LogoutControllerTest {
    private final SupabaseLogoutClient client = mock(SupabaseLogoutClient.class);
    private final DeviceTokenService devices = mock(DeviceTokenService.class);
    private final LogoutController controller = new LogoutController(client, devices);
    private final UUID user = UUID.randomUUID();
    private final Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "RS256").subject(user.toString()).build();

    @Test
    void clearsDeviceTokensAndRevokesSession() {
        assertEquals(204, controller.logout(jwt, UUID.randomUUID(), null).getStatusCode().value());
        var order = inOrder(devices, client);
        order.verify(devices).deactivateAll(user);
        order.verify(client).logout("test-token");
    }

    @Test
    void invalidBodyDoesNotChangeSessionsOrDevices() {
        assertThrows(HeapyException.class, () -> controller.logout(jwt, UUID.randomUUID(), "{}"));
        verifyNoInteractions(client, devices);
    }
}
