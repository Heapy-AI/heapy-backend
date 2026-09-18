package com.heapy.checkup;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.heapy.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.*;

/** OCR 오류 코드 추적과 민감정보 제외를 검증한다. @author 김진우 */
class AwsOcrGatewayTest {
    @Test
    void preservesSafeReasonWithoutExposingResponseOrInvalidTrace() {
        Logger logger = (Logger) LoggerFactory.getLogger(AwsOcrGateway.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MDC.put("traceId", "test-trace");
            var error = AwsOcrGateway.controlFailure("worker_error", "JOB_CONFLICT");
            assertEquals(ErrorCode.OCR_UNAVAILABLE, error.getErrorCode());
            assertTrue(appender.list.getFirst().getFormattedMessage().contains("code=JOB_CONFLICT"));
            MDC.put("traceId", "private\nvalue");
            AwsOcrGateway.controlFailure("worker_error", "private response body");
            String message = appender.list.getLast().getFormattedMessage();
            assertTrue(message.contains("traceId=none"));
            assertTrue(message.contains("code=unknown"));
            assertFalse(message.contains("private"));
        } finally {
            MDC.remove("traceId");
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
