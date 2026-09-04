package com.heapy.terms.dto;

import java.util.List;

public record SaveConsentsResponse(
        List<ConsentEventResponse> consents,
        boolean requiredConsentCompleted,
        String nextStep
) {
}
