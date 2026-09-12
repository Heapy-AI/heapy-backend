package com.heapy.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    MEDICATION_CONFLICT(HttpStatus.CONFLICT, "MEDICATION-001", "이미 처리한 복약 일정이거나 변경할 수 없는 약입니다. 새로고침해 주세요."),
    HEALTH_SYNC_LIMIT(HttpStatus.PAYLOAD_TOO_LARGE, "HEALTH-009", "동기화 배치는 1MiB 이하로 나눠 전송해 주세요."),
    HEALTH_SYNC_PERMISSION(HttpStatus.FORBIDDEN, "HEALTH-008", "삼성헬스의 11개 읽기 권한을 확인해 주세요."),
    HEALTH_READ_ONLY(HttpStatus.FORBIDDEN, "HEALTH-005", "앱에서 직접 추가한 물 기록만 수정·삭제할 수 있습니다."),
    HEALTH_RECORD_CONFLICT(HttpStatus.CONFLICT, "HEALTH-006", "기록이 변경되었거나 같은 시각의 기록이 있습니다. 새로고침해 주세요."),
    HEALTH_ORIGIN_REQUIRED(HttpStatus.CONFLICT, "HEALTH-007", "삼성헬스 원본을 다시 동기화한 뒤 변경해 주세요."),
    CHAT_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "CHAT-001", "상담 서비스를 준비 중이거나 일시적으로 연결할 수 없습니다."),
    CHAT_CONFLICT(HttpStatus.CONFLICT, "CHAT-002", "진행 중인 답변이 있습니다. 완료 후 다시 시도해 주세요."),
    OCR_FILE_LIMIT(HttpStatus.PAYLOAD_TOO_LARGE, "OCR-001", "파일은 20MB 이하, PDF는 20페이지 이하로 등록해 주세요."),
    OCR_UNSUPPORTED_FILE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "OCR-002", "비밀번호가 없는 PDF 또는 JPG, PNG 파일을 등록해 주세요."),
    OCR_EXPIRED(HttpStatus.GONE, "OCR-003", "OCR 작업이 만료되었거나 종료됐습니다. 파일을 다시 등록해 주세요."),
    OCR_ALREADY_CONFIRMED(HttpStatus.CONFLICT, "OCR-004", "이미 확정했거나 중복된 검진 결과입니다."),
    OCR_INVALID_RESULT(HttpStatus.UNPROCESSABLE_CONTENT, "OCR-005", "검진일, 검사 항목과 수정 내용을 확인해 주세요."),
    OCR_REVIEW_VERSION(HttpStatus.CONFLICT, "OCR-006", "검수 형식이 다릅니다. 앱을 업데이트한 뒤 다시 확인해 주세요."),
    OCR_RESULT_LIMIT(HttpStatus.PAYLOAD_TOO_LARGE, "OCR-007", "검진 확정 내용의 크기 제한을 초과했습니다."),
    OCR_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI-001", "OCR 서비스에 일시적인 문제가 있습니다. 잠시 후 다시 시도해 주세요."),
    PUSH_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "PUSH-001", "복약 알림 서버 설정이 아직 준비되지 않았어요."),
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON-001", "입력값이 올바르지 않습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "지원하지 않는 요청 방식입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 요청 형식입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON-500", "서버 내부 오류가 발생했습니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH-001", "이메일 또는 비밀번호를 확인해 주세요."),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "AUTH-002", "이메일 인증이 필요합니다."),
    INVALID_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH-003", "인증 정보가 올바르지 않습니다."),
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "AUTH-005", "회원가입을 진행할 수 없습니다. 로그인 또는 비밀번호 재설정을 이용해 주세요."),
    PASSWORD_POLICY_VIOLATION(HttpStatus.UNPROCESSABLE_CONTENT, "AUTH-006", "비밀번호 정책을 충족하지 않습니다."),
    AUTH_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "AUTH-008", "잠시 후 다시 시도해 주세요."),
    AUTH_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AUTH-009", "인증 서비스에 일시적인 문제가 발생했습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 리소스를 찾을 수 없습니다."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "같은 멱등성 키를 다른 요청에 사용할 수 없습니다."),
    ONBOARDING_INCOMPLETE(HttpStatus.CONFLICT, "ONBOARDING-001", "필수 프로필과 약관을 먼저 완료해 주세요."),
    ONBOARDING_REQUIRED_FIELDS_MISSING(HttpStatus.UNPROCESSABLE_CONTENT, "ONBOARDING-002", "온보딩 필수값이 누락되었습니다."),
    REQUIRED_TERMS_REVOCATION(HttpStatus.CONFLICT, "TERMS-002", "필수 약관은 철회할 수 없습니다.");

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
