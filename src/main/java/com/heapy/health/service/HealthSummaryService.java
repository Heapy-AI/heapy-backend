package com.heapy.health.service;

import com.heapy.health.model.HealthData.Page;
import com.heapy.health.model.HealthMetric;
import com.heapy.health.model.HealthPeriod;
import com.heapy.health.repository.HealthConnectionRepository;
import com.heapy.home.repository.HomeSummaryRepository;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 원천 수치와 분석·점수 상태를 구분해 통합 화면에 제공한다. @author 김진우 */
@Service
public class HealthSummaryService {
    private final HealthQueryService query;
    private final HealthConnectionRepository connections;
    private final HomeSummaryRepository home;
    private final LifestyleScoreService scores;

    public HealthSummaryService(HealthQueryService query, HealthConnectionRepository connections, HomeSummaryRepository home,
                                LifestyleScoreService scores) {
        this.query = query; this.connections = connections; this.home = home; this.scores = scores;
    }

    @Transactional(timeout = 30)
    public Map<String, Object> find(UUID user, String period, LocalDate date) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Page> domains = new LinkedHashMap<>();
        for (HealthMetric metric : HealthMetric.values()) {
            domains.put(metric.code(), query.find(user, metric.code(), period, date, null, null, 100, null));
        }
        result.put("period", HealthPeriod.of(period, date, null));
        result.put("timezone", HealthPeriod.ZONE.getId());
        result.put("domains", domains);
        result.put("connections", connections.findAll(user).stream().map(SamsungPermissionPolicy::effective).toList());
        result.put("latestCheckup", home.find(user).latestCheckup());
        result.put("score", scores.find(user, "7d", null));
        return result;
    }
}
