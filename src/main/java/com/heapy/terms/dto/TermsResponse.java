package com.heapy.terms.dto;

import java.time.Instant;

public record TermsResponse(
        Long termsId,
        String termsCode,
        String version,
        String title,
        String contentUrl,
        String contentHash,
        boolean required,
        Instant effectiveAt,
        String consentStatus
) {
}
