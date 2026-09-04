package com.heapy.terms.dto;

import java.time.Instant;

public record ConsentEventResponse(
        Long consentId,
        Long termsId,
        String action,
        Instant occurredAt
) {
}
