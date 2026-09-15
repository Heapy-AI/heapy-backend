package com.heapy.health.service;

import com.heapy.health.analysis.HealthAnalysisGateway;
import com.heapy.health.model.HealthBriefing;
import com.heapy.health.model.HealthPeriod;
import com.heapy.health.repository.HealthBriefingStore;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 홈 화면의 하루 한 건 브리핑을 만든다.
 *
 * 자정 배치가 사용자마다 스냅샷을 한 번 읽고 여섯 카테고리를 돌린 뒤, 같은 스냅샷으로 여기를
 * 부른다. 스냅샷을 두 번 읽을 이유가 없다.
 *
 * 저장은 `public.daily_health_briefings` 다. `health_analysis_runs` 를 타지 않는다. 브리핑은
 * 하루 한 건이고 탭 분석은 카테고리별이라 알갱이가 다르다.
 *
 * @author 고수연
 */
@Service
public class HealthBriefingService {
    private final HealthBriefingStore store;
    private final HealthAnalysisGateway gateway;

    public HealthBriefingService(HealthBriefingStore store, HealthAnalysisGateway gateway) {
        this.store = store; this.gateway = gateway;
    }

    /**
     * 오늘 몫을 만든다. 이미 있으면 아무것도 하지 않는다.
     *
     * 트랜잭션을 걸지 않는다. 가운데에 모델 호출이 있다. 먼저 행을 차지하고, 밖에서 부르고,
     * 돌아와 채운다. 실패해도 그 사실을 행에 남겨 같은 날 또 부르지 않는다.
     */
    public void run(UUID user, LocalDate date, Map<String, Object> snapshot) {
        if (!date.equals(LocalDate.now(HealthPeriod.ZONE)) || snapshot == null) return;
        if (!store.claim(user, date)) return;
        String status = "failed";
        String failure = "provider_error";
        JsonNode briefing = null;
        JsonNode evidence = null;
        try {
            Map<String, Object> request = new LinkedHashMap<>(snapshot);
            request.put("contractVersion", "1.0");
            request.put("category", "briefing");
            request.put("analysisDate", date.toString());
            request.put("cutoff", date.atStartOfDay(HealthPeriod.ZONE).toInstant().toString());
            JsonNode result = gateway.briefing(request);
            status = result.path("status").asText();
            if ("generated".equals(status)) {
                briefing = result.path("briefing");
                evidence = result.path("evidence");
                failure = null;
            } else if ("data_insufficient".equals(status)) {
                // 실패가 아니다. 아직 보여 줄 기록이 모자란 것뿐이라 사유를 남기지 않는다.
                failure = null;
            } else {
                status = "failed";
                failure = "contract_violation";
            }
        } catch (RuntimeException error) {
            // 작성자: 고수연 — 건강 입력이나 공급자 오류를 행에 적지 않는다. 갈래만 남긴다.
            status = "failed";
            failure = "provider_error";
        }
        store.complete(user, date, status, briefing, evidence, failure, LifestyleScoreService.VERSION);
    }

    /** 오늘 브리핑. 아직 없거나 못 만들었으면 null 이다. */
    public HealthBriefing today(UUID user) {
        HealthBriefing briefing = store.find(user, LocalDate.now(HealthPeriod.ZONE));
        return briefing != null && briefing.ready() ? briefing : null;
    }
}
