package com.heapy.health.analysis;

import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import com.heapy.health.model.HealthPeriod;
import jakarta.annotation.PreDestroy;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 수동 새로고침은 본인의 당일 실패 분석만 재시도한다. @author 김진우 */
@Service
public class HealthAnalysisRefresh {
    private final JdbcTemplate jdbc;
    private final HealthAnalysisReader reader;
    private final HealthAnalysisRunner runner;
    private final HealthAnalysisSnapshot snapshots;
    private final boolean enabled;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), Thread.ofPlatform().name("analysis-refresh-", 0).factory());

    public HealthAnalysisRefresh(JdbcTemplate jdbc, HealthAnalysisReader reader, HealthAnalysisRunner runner,
            HealthAnalysisSnapshot snapshots, @Value("${heapy.health.analysis.enabled:false}") boolean enabled) {
        this.jdbc = jdbc; this.reader = reader; this.runner = runner;
        this.snapshots = snapshots; this.enabled = enabled;
    }

    public Map<String, Object> retry(UUID user, String category) {
        if (!HealthAnalysisReader.CATEGORIES.contains(category)) throw new HeapyException(ErrorCode.INVALID_INPUT);
        LocalDate date = LocalDate.now(HealthPeriod.ZONE);
        // 작성자: 김진우 — 조건부 갱신으로 동시 요청과 자정 배치의 중복 실행을 막는다.
        if (enabled && jdbc.update("""
                update private.health_analysis_runs set status='generating',started_at=clock_timestamp(),completed_at=null
                where user_id=? and analysis_date=? and category=? and status='failed'
                """, user, Date.valueOf(date), category) == 1) {
            try { executor.execute(() -> generate(user, date, category)); }
            catch (RejectedExecutionException error) { fail(user, date, category); }
        }
        return reader.today(user, category);
    }

    private void generate(UUID user, LocalDate date, String category) {
        try {
            if (!date.equals(LocalDate.now(HealthPeriod.ZONE))) {
                fail(user, date, category);
                return;
            }
            var snapshot = snapshots.load(user, date);
            if (snapshot == null) { fail(user, date, category); return; }
            runner.generateClaimed(user, date, category, snapshot);
        } catch (RuntimeException error) { fail(user, date, category); }
    }

    private void fail(UUID user, LocalDate date, String category) {
        jdbc.update("""
                update private.health_analysis_runs set status='failed',completed_at=clock_timestamp()
                where user_id=? and analysis_date=? and category=? and status='generating'
                """, user, Date.valueOf(date), category);
    }

    @PreDestroy
    public void close() { executor.shutdown(); }
}
