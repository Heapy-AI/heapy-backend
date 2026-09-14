package com.heapy.mission.repository;

import com.heapy.mission.model.MissionFeedback;
import com.heapy.health.model.HealthPeriod;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MissionRepository {

    private final JdbcTemplate jdbc;

    public MissionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void ensureDailyMissions(UUID userId, LocalDate date) {
        lockUser(userId);
        jdbc.update("""
                insert into public.user_missions (user_id, template_id, mission_date,mission_code,
                    catalog_version,rule_version,source,starts_at,ends_at,idempotency_key)
                select ?, t.template_id, ?,t.code,1,1,'system',?, ?,gen_random_uuid()
                from public.mission_templates t
                where is_active = true and not exists(select 1 from public.user_missions m
                    where m.user_id=? and m.mission_date=? and m.template_id=t.template_id)
                order by display_order,code
                limit greatest(0,10-(select count(*) from public.user_missions where user_id=? and mission_date=?))
                on conflict (user_id, template_id, mission_date) do nothing
                """, userId, Date.valueOf(date), Timestamp.from(date.atStartOfDay(HealthPeriod.ZONE).toInstant()),
                Timestamp.from(date.plusDays(1).atStartOfDay(HealthPeriod.ZONE).toInstant()),
                userId, Date.valueOf(date),userId,Date.valueOf(date));
    }

    /** 사용자별 생성·완료 요청을 직렬화해 중복 행과 재시도 경합을 막는다. @author 김진우 */
    public void lockUser(UUID userId) {
        jdbc.queryForList("select user_id from public.users where user_id=? for update",userId);
    }

    public List<MissionRow> findByDate(UUID userId, LocalDate date) {
        return jdbc.query("""
                select um.mission_id, mt.code, mt.title, mt.category, mt.description, mt.unit,
                       mt.target_value, mt.display_order, um.mission_date, um.current_value,
                       um.completed_at, um.feedback
                from public.user_missions um
                join public.mission_templates mt on mt.template_id = um.template_id
                where um.user_id = ? and um.mission_date = ?
                order by mt.display_order, mt.code
                """, MissionRepository::mapMission, userId, Date.valueOf(date));
    }

    public Optional<MissionRow> findById(UUID userId, UUID missionId) {
        try {
            MissionRow row = jdbc.queryForObject("""
                    select um.mission_id, mt.code, mt.title, mt.category, mt.description, mt.unit,
                           mt.target_value, mt.display_order, um.mission_date, um.current_value,
                           um.completed_at, um.feedback
                    from public.user_missions um
                    join public.mission_templates mt on mt.template_id = um.template_id
                    where um.user_id = ? and um.mission_id = ?
                    """, MissionRepository::mapMission, userId, missionId);
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public int updateProgress(UUID userId, UUID missionId, int currentValue) {
        return jdbc.update("""
                update public.user_missions
                   set current_value = ?, updated_at = now()
                 where user_id = ? and mission_id = ? and completed_at is null
                """, currentValue, userId, missionId);
    }

    public int complete(UUID userId, UUID missionId) {
        return jdbc.update("""
                update public.user_missions um
                   set current_value = mt.target_value,
                       completed_at = now(), status = 'completed',
                       updated_at = now()
                  from public.mission_templates mt
                 where um.template_id = mt.template_id
                   and um.user_id = ?
                   and um.mission_id = ?
                   and um.completed_at is null
                   and um.current_value >= mt.target_value
                """, userId, missionId);
    }

    public int saveFeedback(UUID userId, UUID missionId, MissionFeedback feedback) {
        return jdbc.update("""
                update public.user_missions
                   set feedback = ?, updated_at = now()
                 where user_id = ? and mission_id = ? and completed_at is not null
                """, feedback.name(), userId, missionId);
    }

    public List<CalendarRow> findCalendar(UUID userId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                select mission_date,
                       count(*)::int as total_count,
                       (count(*) filter (where completed_at is not null))::int as completed_count
                  from public.user_missions
                 where user_id = ? and template_id is not null and mission_date between ? and ?
                 group by mission_date
                 order by mission_date
                """, MissionRepository::mapCalendar,
                userId, Date.valueOf(from), Date.valueOf(to));
    }

    private static MissionRow mapMission(ResultSet rs, int rowNum) throws SQLException {
        Timestamp completedAt = rs.getTimestamp("completed_at");
        String feedback = rs.getString("feedback");
        return new MissionRow(
                rs.getObject("mission_id", UUID.class),
                rs.getString("code"),
                rs.getString("title"),
                rs.getString("category"),
                rs.getString("description"),
                rs.getString("unit"),
                rs.getInt("target_value"),
                rs.getInt("display_order"),
                rs.getDate("mission_date").toLocalDate(),
                rs.getInt("current_value"),
                completedAt == null ? null : completedAt.toInstant(),
                feedback == null ? null : MissionFeedback.valueOf(feedback)
        );
    }

    private static CalendarRow mapCalendar(ResultSet rs, int rowNum) throws SQLException {
        return new CalendarRow(
                rs.getDate("mission_date").toLocalDate(),
                rs.getInt("completed_count"),
                rs.getInt("total_count")
        );
    }

    public record MissionRow(
            UUID missionId,
            String code,
            String title,
            String category,
            String description,
            String unit,
            int targetValue,
            int displayOrder,
            LocalDate missionDate,
            int currentValue,
            Instant completedAt,
            MissionFeedback feedback
    ) {
    }

    public record CalendarRow(
            LocalDate date,
            int completedCount,
            int totalCount
    ) {
    }
}
