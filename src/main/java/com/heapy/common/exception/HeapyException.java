package com.heapy.common.exception;

public class HeapyException extends RuntimeException {

    private final ErrorCode errorCode;

    public HeapyException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
