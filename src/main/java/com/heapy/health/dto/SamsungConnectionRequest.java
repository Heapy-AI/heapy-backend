package com.heapy.health.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SamsungConnectionRequest(
        @NotNull UUID deviceInstallationId,
        @NotNull @Size(max = 11) List<@NotNull @Pattern(regexp =
                "sleep|heart_rate|blood_glucose|blood_pressure|body_composition|exercise|floors|steps|activity|water|nutrition") String> grantedDataTypes,
        @NotBlank @Size(max = 64) String sdkVersion,
        @NotNull Instant permissionCheckedAt
) {
}
