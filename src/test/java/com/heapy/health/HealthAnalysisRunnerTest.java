package com.heapy.health;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.heapy.checkup.OcrJson;
import com.heapy.health.analysis.HealthAnalysisCache;
import com.heapy.health.analysis.HealthAnalysisGateway;
import com.heapy.health.analysis.HealthAnalysisRunner;
import com.heapy.health.model.HealthPeriod;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 실행권 중복과 캐시 실패가 추가 모델 요청을 만들지 않는지 검증한다. @author 김진우 */
class HealthAnalysisRunnerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final HealthAnalysisGateway gateway = mock(HealthAnalysisGateway.class);
    private final HealthAnalysisCache cache = mock(HealthAnalysisCache.class);
    private final HealthAnalysisRunner runner = new HealthAnalysisRunner(jdbc, gateway, cache);

    @Test void duplicateClaimDoesNotCallModel() {
        runner.run(UUID.randomUUID(), LocalDate.now(HealthPeriod.ZONE), "bio", Map.of());
        verifyNoInteractions(gateway, cache);
    }

    @Test void expiredDateDoesNotClaimOrCall() {
        runner.run(UUID.randomUUID(), LocalDate.now(HealthPeriod.ZONE).minusDays(1), "bio", Map.of());
        verifyNoInteractions(jdbc, gateway, cache);
    }

    @Test void cacheFailureDoesNotRetryModel() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(gateway.generate(anyMap())).thenReturn(OcrJson.MAPPER.readTree("{\"status\":\"generated\",\"report\":{}}"));
        doThrow(new IllegalStateException()).when(cache).put(any(), any(), anyString(), any());
        runner.run(UUID.randomUUID(), LocalDate.now(HealthPeriod.ZONE), "bio", Map.of());
        verify(gateway, times(1)).generate(anyMap());
        verify(jdbc).update(contains("update private.health_analysis_runs"), eq("failed"), any(UUID.class), any(), eq("bio"));
    }
}
