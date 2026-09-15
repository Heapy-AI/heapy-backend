package com.heapy.health.analysis;

import com.heapy.health.model.HealthPeriod;
import com.heapy.health.repository.HealthBriefingStore;
import com.heapy.health.service.HealthBriefingService;
import java.sql.Connection;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 자정 작업과 같은 날 복구를 직렬 실행하며 실행권 없는 요청은 생성하지 않는다. @author 김진우 */
@Component
@ConditionalOnProperty(name = "heapy.health.analysis.enabled", havingValue = "true")
public class HealthAnalysisScheduler {
    private static final Logger log = LoggerFactory.getLogger(HealthAnalysisScheduler.class);
    private static final long LOCK = 724390110L;
    private static final List<String> CATEGORIES = List.of("bio", "activity", "nutrition", "sleep", "checkup", "overall");
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final HealthAnalysisSnapshot snapshots;
    private final HealthAnalysisRunner runner;
    private final HealthBriefingService briefings;
    private final HealthBriefingStore briefingStore;

    public HealthAnalysisScheduler(DataSource dataSource, JdbcTemplate jdbc, HealthAnalysisSnapshot snapshots,
                                    HealthAnalysisRunner runner, HealthBriefingService briefings,
                                    HealthBriefingStore briefingStore) {
        this.dataSource = dataSource; this.jdbc = jdbc; this.snapshots = snapshots; this.runner = runner;
        this.briefings = briefings; this.briefingStore = briefingStore;
    }

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    public synchronized void process() {
        // 작성자: 김진우 — 연결의 세션 잠금만 유지하며 외부 호출 동안 DB 트랜잭션은 열지 않는다.
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            try (var statement = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
                statement.setLong(1, LOCK);
                try (var result = statement.executeQuery()) { if (!result.next() || !result.getBoolean(1)) return; }
            }
            try { processToday(); }
            finally {
                try (var statement = connection.prepareStatement("select pg_advisory_unlock(?)")) {
                    statement.setLong(1, LOCK); statement.execute();
                } catch (Exception error) {
                    connection.abort(Runnable::run);
                    throw error;
                }
            }
        } catch (Exception error) {
            log.warn("건강 일일 분석 작업을 완료하지 못했습니다.");
        }
    }

    private void processToday() {
        LocalDate day = LocalDate.now(HealthPeriod.ZONE);
        Date date = Date.valueOf(day);
        jdbc.update("delete from private.health_analysis_runs where analysis_date<?", Date.valueOf(day.minusDays(6)));
        jdbc.update("delete from private.health_analysis_invalidations where analysis_date<?", Date.valueOf(day.minusDays(6)));
        jdbc.update("update private.health_analysis_runs set status='failed',completed_at=clock_timestamp() where status='generating' and started_at<clock_timestamp()-interval '10 minutes'");
        // 작성자: 고수연 — 홈 브리핑도 같은 기간만 남긴다. 지난 흐름을 보여주는 화면이 생기면 늘린다.
        briefingStore.pruneAll(day.minusDays(6));
        UUID cursor = new UUID(0, 0);
        while (day.equals(LocalDate.now(HealthPeriod.ZONE))) {
            // 작성자: 고수연 — 브리핑이 없는 사용자도 집어 든다. 여섯 분석이 이미 다 찬 날에
            // 브리핑 기능을 처음 켜면, 이 조건이 없으면 아무도 브리핑을 받지 못한다.
            var users = jdbc.query("""
                select user_id from public.users u where onboarding_completed_at<?
                and ((select count(*) from private.health_analysis_runs r where r.user_id=u.user_id and r.analysis_date=?)<6
                     or not exists (select 1 from public.daily_health_briefings b
                                     where b.user_id=u.user_id and b.briefing_date=?))
                and user_id>?
                order by user_id limit 100
                """, (rs, row) -> rs.getObject(1, UUID.class),
                Timestamp.from(day.atStartOfDay(HealthPeriod.ZONE).toInstant()), date, date, cursor);
            if (users.isEmpty()) return;
            cursor = users.getLast();
        for (UUID user : users) {
            if (!day.equals(LocalDate.now(HealthPeriod.ZONE))) return;
            try {
                var snapshot = snapshots.load(user, day);
                if (snapshot == null) continue;
                for (String category : CATEGORIES) runner.run(user, day, category, snapshot);
                // 같은 스냅샷으로 홈 브리핑까지 만든다. 두 번 읽을 이유가 없다.
                briefings.run(user, day, snapshot);
            } catch (RuntimeException error) {
                for (String category : CATEGORIES) {
                    jdbc.update("""
                            insert into private.health_analysis_runs(user_id,analysis_date,category,status,completed_at)
                            values (?,?,?,'failed',clock_timestamp()) on conflict do nothing
                            """, user, date, category);
                }
            }
        }
        }
    }
}
