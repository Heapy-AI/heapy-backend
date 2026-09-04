package com.heapy.user.dto;

public record ProfileOptionResponse(
        String canonicalKey,
        String displayName,
        String source
) {
}
