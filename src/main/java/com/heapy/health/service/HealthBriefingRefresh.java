package com.heapy.health.service;

import com.heapy.health.analysis.HealthAnalysisSnapshot;
import com.heapy.health.model.HealthBriefing;
import com.heapy.health.model.HealthPeriod;
import com.heapy.health.repository.HealthBriefingStore;
import jakarta.annotation.PreDestroy;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 홈 새로고침은 실패한 브리핑만 비동기로 재시도한다. @author 김진우 */
@Service
public class HealthBriefingRefresh {
    private final HealthBriefingStore store;
    private final HealthBriefingService service;
    private final HealthAnalysisSnapshot snapshots;
    private final boolean enabled;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), Thread.ofPlatform().name("briefing-refresh-", 0).factory());

    public HealthBriefingRefresh(HealthBriefingStore store, HealthBriefingService service,
            HealthAnalysisSnapshot snapshots,
            @Value("${heapy.health.analysis.enabled:false}") boolean enabled) {
        this.store = store;
        this.service = service;
        this.snapshots = snapshots;
        this.enabled = enabled;
    }

    public HealthBriefing today(UUID user) {
        LocalDate date = LocalDate.now(HealthPeriod.ZONE);
        HealthBriefing result = enabled ? store.find(user, date) : null;
        return result != null ? result : new HealthBriefing(date, enabled ? "pending" : "unavailable",
                null, null, null, null, null);
    }

    public HealthBriefing retry(UUID user) {
        LocalDate date = LocalDate.now(HealthPeriod.ZONE);
        // 작성자: 김진우 — 성공·진행 중·데이터 부족은 실행권을 얻지 못하므로 모델을 호출하지 않는다.
        if (enabled && store.retryFailed(user, date)) {
            try {
                executor.execute(() -> generate(user, date));
            } catch (RejectedExecutionException error) {
                fail(user, date);
            }
        }
        return today(user);
    }

    private void generate(UUID user, LocalDate date) {
        try {
            if (!date.equals(LocalDate.now(HealthPeriod.ZONE))) {
                fail(user, date);
                return;
            }
            var snapshot = snapshots.load(user, date);
            if (snapshot == null) {
                fail(user, date);
                return;
            }
            service.generateClaimed(user, date, snapshot);
        } catch (RuntimeException error) {
            fail(user, date);
        }
    }

    private void fail(UUID user, LocalDate date) {
        store.complete(user, date, "failed", null, null, "retry_failed", LifestyleScoreService.VERSION);
    }

    @PreDestroy
    public void close() {
        executor.shutdown();
    }
}
