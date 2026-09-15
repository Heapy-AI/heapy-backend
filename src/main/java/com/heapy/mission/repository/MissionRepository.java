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
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MissionRepository {

    private final JdbcTemplate jdbc;
    private static final ObjectMapper JSON=new ObjectMapper();

    public MissionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 사용자별 생성·완료 요청을 직렬화해 중복 행과 재시도 경합을 막는다. @author 김진우 */
    public void lockUser(UUID userId) {
        jdbc.queryForList("select user_id from public.users where user_id=? for update",userId);
    }

    public List<MissionRow> findByDate(UUID userId, LocalDate date) {
        return findByDate(userId,date,false);
    }

    public List<MissionRow> findByDate(UUID userId, LocalDate date, boolean includeOngoing) {
        return jdbc.query("""
                select um.mission_id, um.mission_code as code,coalesce(um.target_value->>'title',mt.title) as title,
                       coalesce(um.target_value->>'category',mt.category) as category,
                       coalesce(um.target_value->>'description',mt.description) as description,
                       coalesce(um.target_value->>'unit',mt.unit) as unit,
                       coalesce((um.target_value->>'targetValue')::int,mt.target_value) as target_value,
                       coalesce(mt.display_order,100) as display_order,um.mission_date,um.current_value,
                       um.completed_at,um.feedback,um.starts_at,um.ends_at,um.status as mission_state,
                       um.target_value->>'completion' as completion,(coalesce(um.target_value->'parameters','{}'::jsonb)||jsonb_build_object('_manualDays',coalesce(um.progress_value->'manualDays','[]'::jsonb)))::text as parameters,um.target_value->>'scope' as scope,
                       coalesce((um.target_value->>'manualAllowed')::boolean,false) as manual_allowed
                from public.user_missions um
                left join public.mission_templates mt on mt.template_id = um.template_id
                where um.user_id = ? and (um.mission_date = ? or
                    (? and um.mission_date<? and um.ends_at>?))
                    and (um.template_id is not null or um.source='recommendation')
                order by display_order,um.created_at,um.mission_code
                """, MissionRepository::mapMission, userId, Date.valueOf(date), includeOngoing, Date.valueOf(date),
                Timestamp.from(date.atStartOfDay(HealthPeriod.ZONE).toInstant()));
    }

    public Optional<MissionRow> findById(UUID userId, UUID missionId) {
        try {
            MissionRow row = jdbc.queryForObject("""
                select um.mission_id, um.mission_code as code,coalesce(um.target_value->>'title',mt.title) as title,
                       coalesce(um.target_value->>'category',mt.category) as category,
                       coalesce(um.target_value->>'description',mt.description) as description,
                       coalesce(um.target_value->>'unit',mt.unit) as unit,
                       coalesce((um.target_value->>'targetValue')::int,mt.target_value) as target_value,
                       coalesce(mt.display_order,100) as display_order,um.mission_date,um.current_value,
                       um.completed_at,um.feedback,um.starts_at,um.ends_at,um.status as mission_state,
                       um.target_value->>'completion' as completion,(coalesce(um.target_value->'parameters','{}'::jsonb)||jsonb_build_object('_manualDays',coalesce(um.progress_value->'manualDays','[]'::jsonb)))::text as parameters,um.target_value->>'scope' as scope,
                       coalesce((um.target_value->>'manualAllowed')::boolean,false) as manual_allowed
                    from public.user_missions um
                    left join public.mission_templates mt on mt.template_id = um.template_id
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
                 where user_id = ? and mission_id = ? and completed_at is null and status='active'
                """, currentValue, userId, missionId);
    }

    public int complete(UUID userId, UUID missionId) {
        int completed = jdbc.update("""
                update public.user_missions um
                   set completed_at=now(),status='completed',updated_at=now()
                 where um.user_id=? and um.mission_id=? and um.completed_at is null and um.status='active'
                   and um.current_value>=coalesce((um.target_value->>'targetValue')::int,
                       (select target_value from public.mission_templates where template_id=um.template_id))
                """, userId, missionId);
        // 작성자: 김진우 — 미션 완료와 보상은 호출 서비스의 동일 트랜잭션으로 확정한다.
        if (completed == 1) {
            jdbc.update("insert into public.coin_ledger(user_id,amount,kind,mission_id) values (?,10,'mission_reward',?)",
                    userId, missionId);
        }
        return completed;
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
                 where user_id = ? and (template_id is not null or source='recommendation') and mission_date between ? and ?
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
                feedback == null ? null : MissionFeedback.valueOf(feedback),
                rs.getTimestamp("starts_at").toInstant(),rs.getTimestamp("ends_at").toInstant(),
                rs.getString("completion"),rs.getBoolean("manual_allowed"),rs.getString("scope"),parameters(rs.getString("parameters")),rs.getString("mission_state")
        );
    }

    private static Map<String,Object> parameters(String raw) throws SQLException {
        try {return JSON.readValue(raw,new TypeReference<Map<String,Object>>() { });}
        catch(Exception error) {throw new SQLException("미션 목표 파라미터를 읽지 못했습니다.",error);}
    }

    public void abandon(UUID user,UUID id,Instant now) {
        jdbc.update("update public.user_missions set status='abandoned',ends_at=greatest(starts_at+interval '1 microsecond',?),updated_at=now() where user_id=? and mission_id=? and completed_at is null",Timestamp.from(now),user,id);
    }

    public int manualDay(UUID user,UUID id,String date) {
        jdbc.update("""
                update public.user_missions set progress_value=jsonb_set(progress_value,'{manualDays}',
                    coalesce(progress_value->'manualDays','[]'::jsonb)||to_jsonb(?::text)),updated_at=now()
                where user_id=? and mission_id=? and not coalesce(progress_value->'manualDays','[]'::jsonb) @> to_jsonb(array[?]::text[])
                """,date,user,id,date);
        return jdbc.queryForObject("select jsonb_array_length(coalesce(progress_value->'manualDays','[]'::jsonb)) from public.user_missions where user_id=? and mission_id=?",Integer.class,user,id);
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
            MissionFeedback feedback, Instant startsAt, Instant endsAt, String completion, boolean manualAllowed, String scope, Map<String,Object> parameters,String state
    ) {
    }

    public record CalendarRow(
            LocalDate date,
            int completedCount,
            int totalCount
    ) {
    }
}
