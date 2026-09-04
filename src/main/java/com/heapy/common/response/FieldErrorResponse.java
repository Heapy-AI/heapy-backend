package com.heapy.common.response;

public record FieldErrorResponse(
        String field,
        Object value,
        String reason
) {
}
