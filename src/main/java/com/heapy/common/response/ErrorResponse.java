package com.heapy.common.response;

import java.time.Instant;
import java.util.List;

public record ErrorResponse(
        boolean success,
        Instant timestamp,
        int status,
        String code,
        String message,
        List<FieldErrorResponse> errors,
        String path,
        String traceId
) {
}
