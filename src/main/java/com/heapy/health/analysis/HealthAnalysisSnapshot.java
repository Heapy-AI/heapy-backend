package com.heapy.health.analysis;

import com.heapy.checkup.OcrRepository;
import com.heapy.health.model.HealthMetric;
import com.heapy.health.model.HealthPeriod;
import com.heapy.health.repository.HealthRecordRepository;
import com.heapy.terms.repository.UserTermsConsentRepository;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 한 사용자의 전 영역 입력을 같은 DB 스냅샷에서 읽고 외부 호출 전에 트랜잭션을 끝낸다. @author 김진우 */
@Service
public class HealthAnalysisSnapshot {
    private final JdbcTemplate jdbc;
    private final HealthRecordRepository records;
    private final OcrRepository checkups;
    private final UserTermsConsentRepository consents;

    public HealthAnalysisSnapshot(JdbcTemplate jdbc, HealthRecordRepository records, OcrRepository checkups,
                                  UserTermsConsentRepository consents) {
        this.jdbc = jdbc; this.records = records; this.checkups = checkups; this.consents = consents;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 20)
    public Map<String, Object> load(UUID user, LocalDate date) {
        Timestamp cutoff = Timestamp.from(date.atStartOfDay(HealthPeriod.ZONE).toInstant());
        var profiles = jdbc.queryForList("select birth_date,sex from public.users where user_id=? and onboarding_completed_at<?", user, cutoff);
        if (profiles.isEmpty() || consents.countMissingCurrentRequiredConsents(user) > 0) return null;
        Integer invalidated = jdbc.queryForObject("select count(*) from private.health_analysis_invalidations where user_id=? and analysis_date=?",
                Integer.class, user, Date.valueOf(date));
        if (invalidated != null && invalidated > 0) throw new IllegalStateException("자정 당시 원본 확인 불가");
        Map<String, Object> snapshot = new LinkedHashMap<>();
        Object sex = profiles.getFirst().get("sex");
        snapshot.put("sex", "male".equals(sex) || "female".equals(sex) ? sex : null);
        Object birthday = profiles.getFirst().get("birth_date");
        snapshot.put("age", birthday instanceof Date birth ? Period.between(birth.toLocalDate(), date).getYears() : null);
        Map<String, Object> domains = new LinkedHashMap<>();
        int total = 0;
        for (HealthMetric metric : HealthMetric.values()) {
            var period = HealthPeriod.of("1y", date.minusDays(1), "raw");
            var rows = records.find(user, metric, period, null, 10001, null, null);
            total += rows.size();
            if (total > 10000) throw new IllegalStateException("분석 입력 한도 초과");
            // 작성자: 김진우 — 자정 이후 입력된 기록은 당일 분석에 섞지 않는다.
            var values = rows.stream().filter(row -> {
                Object created = row.values().get("created_at");
                return created != null && Instant.parse(created.toString()).isBefore(cutoff.toInstant());
            }).map(row -> row.values()).toList();
            domains.put(metric.code(), values);
        }
        snapshot.put("records", domains);
        var ids = jdbc.query("select record_id from public.health_checkup_records where user_id=? and created_at<? order by measured_at,record_id limit 101",
                (rs, row) -> rs.getObject(1, UUID.class), user, cutoff);
        if (ids.size() > 100) throw new IllegalStateException("검진 이력 한도 초과");
        List<Map<String, Object>> history = new ArrayList<>();
        for (UUID id : ids) {
            var detail = checkups.detail(user, id).orElseThrow();
            List<Map<String, Object>> results = new ArrayList<>();
            for (var item : detail.results()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("item_code", item.itemCode()); result.put("item_name", item.itemName());
                result.put("value", item.numericValue() == null ? item.value() : item.numericValue());
                result.put("unit", item.unit()); result.put("status", item.status()); results.add(result);
            }
            history.add(Map.of("date", detail.measuredAt().toString(), "results", results));
        }
        snapshot.put("checkups", history);
        return snapshot;
    }
}
