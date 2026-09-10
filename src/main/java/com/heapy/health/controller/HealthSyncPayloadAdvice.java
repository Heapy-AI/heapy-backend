package com.heapy.health.controller;

import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/** 역직렬화 전 동기화 배치 크기를 1MiB로 제한한다. @author 김진우 */
@ControllerAdvice
public class HealthSyncPayloadAdvice extends RequestBodyAdviceAdapter {
    @Override
    public boolean supports(MethodParameter parameter, Type type, Class<? extends HttpMessageConverter<?>> converter) {
        return parameter.getContainingClass() == HealthSyncController.class;
    }
    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage message, MethodParameter parameter, Type type,
            Class<? extends HttpMessageConverter<?>> converter) throws IOException {
        byte[] bytes = message.getBody().readNBytes(1048577);
        if (bytes.length > 1048576) throw new HeapyException(ErrorCode.HEALTH_SYNC_LIMIT);
        return new HttpInputMessage() {
            public InputStream getBody() { return new ByteArrayInputStream(bytes); }
            public HttpHeaders getHeaders() { return message.getHeaders(); }
        };
    }
}
