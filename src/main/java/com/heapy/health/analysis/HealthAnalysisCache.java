package com.heapy.health.analysis;

import com.heapy.checkup.OcrJson;
import com.heapy.health.model.HealthPeriod;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** 다음 한국 자정 만료의 사용자별 분석 임시 보관이다. @author 김진우 */
@Component
public class HealthAnalysisCache {
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    public HealthAnalysisCache(StringRedisTemplate redis, JdbcTemplate jdbc) {
        this.redis = redis;
        this.jdbc = jdbc;
    }

    @Transactional
    public void put(UUID user, LocalDate day, String category, JsonNode result) {
        // 작성자: 김진우 — 탈퇴와 직렬화하여 늦게 도착한 분석이 삭제한 캐시를 되살리지 못하게 한다.
        if (jdbc.query("select user_id from public.users where user_id=? for share",
                (rs, row) -> rs.getObject(1, UUID.class), user).isEmpty()) return;
        Duration ttl = remaining(day, Instant.now());
        if (ttl.isNegative() || ttl.isZero()) return;
        String value = OcrJson.encode(result);
        if (value.length() > 65536) throw new IllegalArgumentException("분석 크기 한도 초과");
        redis.opsForValue().set(key(user, day, category), value, ttl);
    }

    /** 이전 날짜 키도 함께 정리하며 Redis 장애를 성공으로 숨기지 않는다. @author 김진우 */
    public void evictUser(UUID user) {
        try (var keys = redis.scan(ScanOptions.scanOptions()
                .match("heapy:health:" + user + ":*").count(100).build())) {
            while (keys.hasNext()) redis.delete(keys.next());
        }
    }

    public JsonNode get(UUID user, LocalDate day, String category) {
        if (!day.equals(LocalDate.now(HealthPeriod.ZONE))) return null;
        String value = redis.opsForValue().get(key(user, day, category));
        return value == null ? null : OcrJson.MAPPER.readTree(value);
    }

    public static Duration remaining(LocalDate day, Instant now) {
        return Duration.between(now, day.plusDays(1).atStartOfDay(HealthPeriod.ZONE).toInstant());
    }

    private String key(UUID user, LocalDate day, String category) { return "heapy:health:" + user + ":" + day + ":" + category; }
}
