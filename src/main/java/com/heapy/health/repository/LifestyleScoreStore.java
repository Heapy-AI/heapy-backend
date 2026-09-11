package com.heapy.health.repository;

import com.heapy.health.model.HealthPeriod;
import com.heapy.health.model.LifestyleScore.Component;
import com.heapy.health.model.LifestyleScore.Day;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 계산 결과만 보관하며 원천 건강 기록에는 쓰지 않는다. @author 김진우 */
@Repository
public class LifestyleScoreStore {
    private final JdbcTemplate jdbc;
    public LifestyleScoreStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void lock(UUID user) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))", rs -> { }, "lifestyle-score:" + user);
    }
    public List<Day> find(UUID user, HealthPeriod period) {
        return jdbc.query("""
                select * from public.lifestyle_daily_scores
                where user_id=? and score_date>=? and score_date<=? order by score_date
                """, (rs, row) -> new Day(rs.getDate("score_date").toLocalDate(), rs.getObject("total_score", Integer.class),
                new Component(rs.getObject("sleep_score", Double.class), rs.getInt("sleep_recorded_days"), 5),
                new Component(rs.getObject("activity_score", Double.class), rs.getInt("activity_recorded_days"), 7),
                rs.getObject("bmi_score", Double.class), rs.getDate("bmi_date") == null ? null : rs.getDate("bmi_date").toLocalDate(),
                List.copyOf(Arrays.asList((String[]) rs.getArray("reasons").getArray()))),
                user, Date.valueOf(period.from()), Date.valueOf(period.to()));
    }
    public void insert(UUID user, Day day, String version) {
        jdbc.update("""
                insert into public.lifestyle_daily_scores
                  (user_id,score_date,policy_version,total_score,sleep_score,sleep_recorded_days,
                   activity_score,activity_recorded_days,bmi_score,bmi_date,reasons)
                values (?,?,?,?,?,?,?,?,?,?,?::text[]) on conflict (user_id,score_date) do nothing
                """, user, Date.valueOf(day.date()), version, day.score(), day.sleep().score(), day.sleep().recordedDays(),
                day.activity().score(), day.activity().recordedDays(), day.bmiScore(),
                day.bmiDate() == null ? null : Date.valueOf(day.bmiDate()), "{" + String.join(",", day.reasons()) + "}");
    }
    public void prune(UUID user, LocalDate from) {
        jdbc.update("delete from public.lifestyle_daily_scores where user_id=? and score_date<?", user, Date.valueOf(from));
    }
    public void pruneAll(LocalDate from) {
        jdbc.update("delete from public.lifestyle_daily_scores where score_date<?", Date.valueOf(from));
    }
    public List<UUID> pendingUsers(HealthPeriod period, UUID after) {
        return jdbc.query("""
                select user_id from public.users u where onboarding_completed_at is not null and user_id>?
                and (select count(*) from public.lifestyle_daily_scores s
                     where s.user_id=u.user_id and s.score_date>=? and s.score_date<=?)<7
                order by user_id limit 100
                """, (rs, row) -> rs.getObject(1, UUID.class), after, Date.valueOf(period.from()), Date.valueOf(period.to()));
    }
}
