package com.heapy.home.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 확정 검진과 저장된 활동만 사용자별로 조회한다. @author 김진우 */
@Repository
public class HomeSummaryRepository {
    private final JdbcTemplate jdbc;
    public HomeSummaryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Summary find(UUID userId) {
        List<Checkup> checkups = jdbc.query("""
                select r.record_id, r.measured_at, r.provider_name,
                       (select count(*) from public.health_checkup_results x where x.record_id=r.record_id),
                       (select count(*) from public.health_checkup_findings f where f.record_id=r.record_id)
                from public.health_checkup_records r where r.user_id=?
                order by r.measured_at desc, r.confirmed_at desc, r.record_id limit 1
                """, (rs, row) -> new Checkup(rs.getObject(1, UUID.class), rs.getDate(2).toLocalDate(),
                rs.getString(3), rs.getInt(4), rs.getInt(5)), userId);
        List<Activity> activity = jdbc.query("""
                select record_date,steps,active_time_minutes from public.lifestyle_activity
                where user_id=? order by record_date desc limit 1
                """, (rs, row) -> new Activity(rs.getDate(1).toLocalDate(), rs.getInt(2), rs.getInt(3)), userId);
        return new Summary(checkups.isEmpty() ? null : checkups.getFirst(),
                activity.isEmpty() ? null : activity.getFirst());
    }

    public record Summary(Checkup latestCheckup, Activity latestActivity) {
        public boolean hasData() { return latestCheckup != null || latestActivity != null; }
    }
    public record Checkup(UUID recordId, LocalDate measuredAt, String providerName,
                          int resultCount, int findingCount) { }
    public record Activity(LocalDate recordDate, int steps, int activeTimeMinutes) { }

    public List<Alert> alerts(UUID userId) {
        return jdbc.query("""
                select alert_id,title,message from public.health_alerts
                where user_id=? and status='active' and (expires_at is null or expires_at>current_timestamp)
                order by detected_at desc,alert_id
                """, (rs, row) -> new Alert(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), userId);
    }

    public record Mission(UUID userMissionId, String title, String description, String status) { }
    public record Alert(UUID alertId, String title, String message) { }
}
