package com.heapy.health.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record HealthConnectionResponse(
        UUID connectionId,
        String provider,
        UUID deviceInstallationId,
        String status,
        List<String> grantedDataTypes,
        String sdkVersion,
        Instant lastPermissionCheckedAt,
        Instant lastSyncedAt
) {
}
