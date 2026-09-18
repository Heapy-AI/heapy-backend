package com.heapy.health.analysis;

import com.heapy.checkup.OcrJson;
import com.heapy.health.model.HealthPeriod;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 삭제 후 늦게 끝난 분석이 캐시를 다시 만들지 않는지 확인한다. @author 김진우 */
class HealthAnalysisCacheTest {
    @Test
    void lateResultDoesNotRecreateDeletedUsersCache() {
        var user = UUID.randomUUID();
        var jdbc = mock(JdbcTemplate.class);
        var redis = mock(StringRedisTemplate.class);
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<UUID>>any(), eq(user))).thenReturn(List.of());
        new HealthAnalysisCache(redis, jdbc).put(user, LocalDate.now(HealthPeriod.ZONE), "overall",
                OcrJson.MAPPER.createObjectNode().put("report", "사용자 분석"));
        verifyNoInteractions(redis);
    }
}
