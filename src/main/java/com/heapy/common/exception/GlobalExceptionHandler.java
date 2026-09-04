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

@RestControllerAdvice
public class GlobalExceptionHandler {

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
                        redactRejectedValue(error.getField(), error.getRejectedValue()),
                        error.getDefaultMessage()
                ))
                .toList();
        return buildResponse(ErrorCode.INVALID_INPUT, errors, request);
    }

    @ExceptionHandler({
            ConstraintViolationException.class,
            HandlerMethodValidationException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
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
