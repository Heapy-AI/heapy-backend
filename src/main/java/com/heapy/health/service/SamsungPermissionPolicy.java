package com.heapy.health.service;

import com.heapy.health.dto.HealthConnectionResponse;
import java.util.Collection;
import java.util.Set;

/** 삼성 연결의 필수 읽기 권한을 일관되게 판단한다. @author 김진우 */
public final class SamsungPermissionPolicy {
    public static final Set<String> REQUIRED = Set.of("sleep", "heart_rate", "blood_glucose",
            "blood_pressure", "body_composition", "exercise", "floors", "steps", "activity",
            "water", "nutrition");

    private SamsungPermissionPolicy() { }

    public static boolean complete(Collection<String> granted) {
        return granted != null && granted.containsAll(REQUIRED);
    }

    public static HealthConnectionResponse effective(HealthConnectionResponse response) {
        if (!"connected".equals(response.status()) || complete(response.grantedDataTypes())) return response;
        return new HealthConnectionResponse(response.connectionId(), response.provider(),
                response.deviceInstallationId(), "permission_required", response.grantedDataTypes(),
                response.sdkVersion(), response.lastPermissionCheckedAt(), response.lastSyncedAt());
    }
}
