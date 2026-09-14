package com.heapy.health.analysis;

import com.heapy.checkup.OcrJson;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import com.heapy.health.model.HealthPeriod;
import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** 당일 분석 조회는 모델 생성을 유발하지 않는다. @author 김진우 */
@Service
public class HealthAnalysisReader {
    public static final Set<String> CATEGORIES = Set.of("bio", "activity", "nutrition", "sleep", "checkup", "overall");
    private final JdbcTemplate jdbc;
    private final HealthAnalysisCache cache;
    private final boolean enabled;
    public HealthAnalysisReader(JdbcTemplate jdbc, HealthAnalysisCache cache,
            @Value("${heapy.health.analysis.enabled:false}") boolean enabled) {
        this.jdbc = jdbc; this.cache = cache; this.enabled = enabled;
    }

    public Map<String, Object> today(UUID user, String category) {
        if (!CATEGORIES.contains(category)) throw new HeapyException(ErrorCode.INVALID_INPUT);
        LocalDate date = LocalDate.now(HealthPeriod.ZONE);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("analysisDate", date); response.put("category", category);
        response.put("cutoff", date.atStartOfDay(HealthPeriod.ZONE).toInstant());
        response.put("expiresAt", date.plusDays(1).atStartOfDay(HealthPeriod.ZONE).toInstant());
        response.put("status", "unavailable");
        if (!enabled) return response;
        var rows = jdbc.queryForList("select status,completed_at,result::text as result from private.health_analysis_runs where user_id=? and analysis_date=? and category=?",
                user, Date.valueOf(date), category);
        if (rows.isEmpty()) { response.put("status", "pending"); return response; }
        response.put("status", rows.getFirst().get("status"));
        if ("generated".equals(rows.getFirst().get("status"))) {
            try {
                var result = cache.get(user, date, category);
                // 작성자: 고수연 — 캐시가 비면 실행 이력의 원본을 읽고 캐시를 다시 채운다.
                // 재시작이나 TTL 만료로 Redis가 비었다고 문장이 사라지면 안 된다.
                if (result == null) {
                    result = stored(rows.getFirst().get("result"));
                    if (result != null) cache.put(user, date, category, result);
                }
                if (result == null) response.put("status", "result_lost");
                else { response.put("report", result.get("report")); response.put("generatedAt", rows.getFirst().get("completed_at")); }
            } catch (RuntimeException error) { response.put("status", "unavailable"); }
        }
        return response;
    }

    /** 실행 이력에 남은 원본. 열이 비었거나 읽히지 않으면 없는 것으로 본다. */
    private static JsonNode stored(Object value) {
        if (value == null) return null;
        try {
            return OcrJson.MAPPER.readTree(value.toString());
        } catch (RuntimeException error) {
            return null;
        }
    }
}
