package com.heapy.health.analysis;

import com.heapy.checkup.OcrJson;
import com.heapy.health.model.HealthPeriod;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 다음 한국 자정 만료의 사용자별 분석 임시 보관이다. @author 김진우 */
@Component
public class HealthAnalysisCache {
    private final StringRedisTemplate redis;
    public HealthAnalysisCache(StringRedisTemplate redis) { this.redis = redis; }

    public void put(UUID user, LocalDate day, String category, JsonNode result) {
        Duration ttl = remaining(day, Instant.now());
        if (ttl.isNegative() || ttl.isZero()) return;
        String value = OcrJson.encode(result);
        if (value.length() > 65536) throw new IllegalArgumentException("분석 크기 한도 초과");
        redis.opsForValue().set(key(user, day, category), value, ttl);
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
