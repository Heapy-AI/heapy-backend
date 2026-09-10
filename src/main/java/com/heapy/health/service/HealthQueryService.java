package com.heapy.health.service;

import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import com.heapy.health.model.HealthData.Page;
import com.heapy.health.model.HealthData.Record;
import com.heapy.health.model.HealthMetric;
import com.heapy.health.model.HealthPeriod;
import com.heapy.health.repository.HealthRecordRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 건강 조회의 허용값과 사용자·기간별 커서를 검증한다. @author 김진우 */
@Service
public class HealthQueryService {
    private static final int GRAPH_LIMIT = 20000;
    private static final Set<String> BIO_TYPES = Set.of("heart_rate", "blood_glucose", "blood_pressure", "body_composition", "weight", "bmi");
    private final HealthRecordRepository repository;

    public HealthQueryService(HealthRecordRepository repository) { this.repository = repository; }

    @Transactional(readOnly = true, timeout = 15)
    public Page find(UUID user, String metricCode, String periodCode, LocalDate baseDate, String aggregation,
                     String bioType, int limit, String cursor) {
        HealthMetric metric = HealthMetric.of(metricCode);
        if (limit < 1 || limit > 200 || (bioType != null && (metric != HealthMetric.BIO || !BIO_TYPES.contains(bioType)))) {
            throw new HeapyException(ErrorCode.INVALID_INPUT);
        }
        // 작성자: 김진우 — 날짜를 지정하지 않은 그래프는 데모처럼 해당 지표의 마지막 기록일을 기준으로 한다.
        HealthPeriod period = HealthPeriod.of(periodCode,
                baseDate == null ? repository.latestDate(user, metric, bioType) : baseDate, aggregation);
        boolean raw = "raw".equals(period.aggregation());
        if (cursor != null && !raw) throw new HeapyException(ErrorCode.INVALID_INPUT);
        String scope = user + "|" + metricCode + "|" + period.from() + "|" + period.to() + "|" + bioType;
        String[] position = cursor == null ? null : decode(cursor, scope, metric.dateOnly());
        int maximum = raw ? limit : GRAPH_LIMIT;
        List<Record> rows = repository.find(user, metric, period, bioType, maximum + 1,
                position == null ? null : position[0], position == null ? null : UUID.fromString(position[1]));
        boolean truncated = rows.size() > maximum;
        List<Record> records = List.copyOf(rows.subList(0, Math.min(maximum, rows.size())));
        String next = null;
        if (raw && truncated) {
            Record last = records.getLast();
            String time = metric.dateOnly() ? last.date().toString() : last.measuredAt().toString();
            next = Base64.getUrlEncoder().withoutPadding().encodeToString((scope + "|" + time + "|" + last.recordId()).getBytes(StandardCharsets.UTF_8));
        }
        // 작성자: 김진우 — 상한으로 잘린 자료를 완전한 그래프 평균으로 제시하지 않는다.
        return new Page(metricCode, period, HealthPeriod.ZONE.getId(), records,
                raw || truncated ? List.of() : HealthAggregation.aggregate(metric, records, period), truncated, next);
    }

    private String[] decode(String value, String scope, boolean dateOnly) {
        try {
            if (value.length() > 512) throw new IllegalArgumentException();
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            if (!decoded.startsWith(scope + "|")) throw new IllegalArgumentException();
            String[] parts = decoded.substring(scope.length() + 1).split("\\|", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            if (dateOnly) LocalDate.parse(parts[0]); else Instant.parse(parts[0]);
            UUID.fromString(parts[1]);
            return parts;
        } catch (RuntimeException error) { throw new HeapyException(ErrorCode.INVALID_INPUT); }
    }
}
