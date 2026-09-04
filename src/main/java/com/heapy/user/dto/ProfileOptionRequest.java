package com.heapy.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ProfileOptionRequest(
        @Size(max = 100) String canonicalKey,
        @NotBlank @Size(max = 100) String displayName,
        @NotBlank @Pattern(regexp = "user|normalized") String source
) {
}
