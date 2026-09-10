package com.heapy.health;

import static org.junit.jupiter.api.Assertions.*;
import com.heapy.health.analysis.HealthAnalysisCache;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** UTC 날짜와 다른 한국 자정에서 결과 보관이 끝나는지 확인한다. @author 김진우 */
class HealthAnalysisCacheTest {
    @Test void expiresAtKoreanMidnight() {
        LocalDate date = LocalDate.of(2026, 9, 10);
        assertEquals(1, HealthAnalysisCache.remaining(date, Instant.parse("2026-09-10T14:59:59Z")).toSeconds());
        assertTrue(HealthAnalysisCache.remaining(date, Instant.parse("2026-09-10T15:00:00Z")).isZero());
        assertTrue(HealthAnalysisCache.remaining(date, Instant.parse("2026-09-10T15:00:01Z")).isNegative());
    }
}
