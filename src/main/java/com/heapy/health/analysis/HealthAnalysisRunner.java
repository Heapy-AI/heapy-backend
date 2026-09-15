package com.heapy.health.analysis;

import com.heapy.checkup.OcrJson;
import com.heapy.health.model.HealthPeriod;
import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** 실행권을 먼저 확보하고 결과 유실 시에도 외부 분석을 반복하지 않는다. @author 김진우 */
@Service
public class HealthAnalysisRunner {
    private final JdbcTemplate jdbc;
    private final HealthAnalysisGateway gateway;
    private final HealthAnalysisCache cache;

    public HealthAnalysisRunner(JdbcTemplate jdbc, HealthAnalysisGateway gateway, HealthAnalysisCache cache) {
        this.jdbc = jdbc; this.gateway = gateway; this.cache = cache;
    }

    public void run(UUID user, LocalDate date, String category, Map<String, Object> snapshot) {
        if (!date.equals(LocalDate.now(HealthPeriod.ZONE)) || !HealthAnalysisReader.CATEGORIES.contains(category)) return;
        int claimed = jdbc.update("""
                insert into private.health_analysis_runs(user_id,analysis_date,category,status,started_at)
                values (?,?,?,'generating',clock_timestamp()) on conflict do nothing
                """, user, Date.valueOf(date), category);
        if (claimed == 0) return;
        String status = "failed";
        // 작성자: 고수연 — 문장의 원본을 실행 이력에도 남긴다. Redis는 앞단 캐시로만 쓴다.
        JsonNode generated = null;
        try {
            Map<String, Object> request = new LinkedHashMap<>(snapshot);
            request.put("contractVersion", "1.0"); request.put("category", category);
            request.put("analysisDate", date.toString());
            request.put("cutoff", date.atStartOfDay(HealthPeriod.ZONE).toInstant().toString());
            var result = gateway.generate(request);
            status = result.path("status").asText();
            if ("generated".equals(status)) { cache.put(user, date, category, result); generated = result; }
        } catch (RuntimeException error) {
            // 작성자: 김진우 — 건강 입력·분석 문구·공급자 오류를 로그나 실행 이력에 저장하지 않는다.
            status = "failed"; generated = null;
        }
        jdbc.update("""
                update private.health_analysis_runs set status=?,completed_at=clock_timestamp(),result=?::jsonb
                where user_id=? and analysis_date=? and category=? and status='generating'
                """, status, generated == null ? null : OcrJson.encode(generated),
                user, Date.valueOf(date), category);
    }
}
