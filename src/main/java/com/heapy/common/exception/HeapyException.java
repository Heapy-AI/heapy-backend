package com.heapy.common.exception;

import com.heapy.common.response.FieldErrorResponse;
import java.util.List;

public class HeapyException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<FieldErrorResponse> errors;

    public HeapyException(ErrorCode errorCode) {
        this(errorCode, List.of());
    }

    public HeapyException(ErrorCode errorCode, List<FieldErrorResponse> errors) {
        super(errorCode.message());
        this.errorCode = errorCode;
        this.errors = List.copyOf(errors);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public List<FieldErrorResponse> getErrors() {
        return errors;
    }
}
