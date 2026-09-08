package com.heapy.common.exception;

import com.heapy.common.response.ErrorResponse;
import com.heapy.common.response.FieldErrorResponse;
import com.heapy.common.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.MissingServletRequestParameterException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadLimit(Exception exception, HttpServletRequest request) {
        return buildResponse(ErrorCode.OCR_FILE_LIMIT, List.of(), request);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(Exception exception, HttpServletRequest request) {
        return buildResponse(ErrorCode.RESOURCE_NOT_FOUND, List.of(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
        return buildResponse(ErrorCode.METHOD_NOT_ALLOWED, List.of(), request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException exception, HttpServletRequest request) {
        return buildResponse(ErrorCode.UNSUPPORTED_MEDIA_TYPE, List.of(), request);
    }

    @ExceptionHandler(HeapyException.class)
    public ResponseEntity<ErrorResponse> handleHeapyException(
            HeapyException exception,
            HttpServletRequest request
    ) {
        return buildResponse(exception.getErrorCode(), exception.getErrors(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        List<FieldErrorResponse> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldErrorResponse(
                        error.getField(),
                        request.getRequestURI().startsWith("/api/checkups") ? "[REDACTED]"
                                : redactRejectedValue(error.getField(), error.getRejectedValue()),
                        error.getDefaultMessage()
                ))
                .toList();
        return buildResponse(request.getRequestURI().startsWith("/api/checkups/")
                ? ErrorCode.OCR_INVALID_RESULT : ErrorCode.INVALID_INPUT, errors, request);
    }

    @ExceptionHandler({
            ConstraintViolationException.class,
            HandlerMethodValidationException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestPartException.class,
            MissingServletRequestParameterException.class
    })
    public ResponseEntity<ErrorResponse> handleInvalidRequest(
            Exception exception,
            HttpServletRequest request
    ) {
        return buildResponse(ErrorCode.INVALID_INPUT, List.of(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request
    ) {
        // 작성자: 김진우 — 예외 메시지에는 SQL·건강정보가 포함될 수 있어 유형과 추적 ID만 기록한다.
        log.error("요청 처리 실패: traceId={}, exceptionType={}",
                RequestTraceFilter.getTraceId(request), exception.getClass().getName());
        return buildResponse(ErrorCode.INTERNAL_SERVER_ERROR, List.of(), request);
    }

    private ResponseEntity<ErrorResponse> buildResponse(
            ErrorCode errorCode,
            List<FieldErrorResponse> errors,
            HttpServletRequest request
    ) {
        ErrorResponse response = new ErrorResponse(
                false,
                Instant.now(),
                errorCode.status().value(),
                errorCode.code(),
                errorCode.message(),
                errors,
                request.getRequestURI(),
                RequestTraceFilter.getTraceId(request)
        );
        return ResponseEntity.status(errorCode.status()).body(response);
    }

    private Object redactRejectedValue(String field, Object value) {
        if ("password".equalsIgnoreCase(field)) {
            return "[REDACTED]";
        }
        return value;
    }
}
