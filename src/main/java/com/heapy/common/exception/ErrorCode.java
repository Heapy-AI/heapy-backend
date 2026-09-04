package com.heapy.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON-001", "입력값이 올바르지 않습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON-500", "서버 내부 오류가 발생했습니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH-001", "이메일 또는 비밀번호를 확인해 주세요."),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "AUTH-002", "이메일 인증이 필요합니다."),
    AUTH_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "AUTH-008", "잠시 후 다시 시도해 주세요."),
    AUTH_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AUTH-009", "인증 서비스에 일시적인 문제가 발생했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
