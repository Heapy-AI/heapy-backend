package com.heapy.health.analysis;

import com.heapy.health.model.HealthPeriod;
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

    public HealthAnalysisScheduler(DataSource dataSource, JdbcTemplate jdbc, HealthAnalysisSnapshot snapshots,
                                    HealthAnalysisRunner runner) {
        this.dataSource = dataSource; this.jdbc = jdbc; this.snapshots = snapshots; this.runner = runner;
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
        UUID cursor = new UUID(0, 0);
        while (day.equals(LocalDate.now(HealthPeriod.ZONE))) {
            var users = jdbc.query("""
                select user_id from public.users u where onboarding_completed_at<?
                and (select count(*) from private.health_analysis_runs r where r.user_id=u.user_id and r.analysis_date=?)<6
                and user_id>?
                order by user_id limit 100
                """, (rs, row) -> rs.getObject(1, UUID.class),
                Timestamp.from(day.atStartOfDay(HealthPeriod.ZONE).toInstant()), date, cursor);
            if (users.isEmpty()) return;
            cursor = users.getLast();
        for (UUID user : users) {
            if (!day.equals(LocalDate.now(HealthPeriod.ZONE))) return;
            try {
                var snapshot = snapshots.load(user, day);
                if (snapshot == null) continue;
                for (String category : CATEGORIES) runner.run(user, day, category, snapshot);
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
