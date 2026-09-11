package com.heapy.health.repository;

import com.heapy.health.model.HealthPeriod;
import com.heapy.health.model.LifestyleScore.Bmi;
import com.heapy.health.model.LifestyleScore.Input;
import com.heapy.health.model.LifestyleScore.Session;
import com.heapy.health.model.LifestyleScore.Steps;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 날짜마다 재조회하지 않고 계산에 필요한 사용자 기록만 한 번씩 읽는다. @author 김진우 */
@Repository
public class LifestyleScoreRepository {
    private static final int LIMIT = 20000;
    private final JdbcTemplate jdbc;
    public LifestyleScoreRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Input find(UUID user, HealthPeriod period) {
        List<LocalDate> births = jdbc.query("select birth_date from public.users where user_id=?",
                (rs, row) -> rs.getDate(1) == null ? null : rs.getDate(1).toLocalDate(), user);
        LocalDate from = period.from().minusDays(13), until = period.to().plusDays(1);
        List<Session> sleep = sessions(user, "lifestyle_sleep", "total_sleep_minutes", from.minusDays(1), until);
        List<Session> exercise = sessions(user, "lifestyle_exercise", "duration_seconds / 60.0", from.minusDays(1), until);
        List<Steps> steps = jdbc.query("""
                select record_date, max(steps) from public.lifestyle_activity
                where user_id=? and record_date>=? and record_date<? group by record_date
                """, (rs, row) -> new Steps(rs.getDate(1).toLocalDate(), rs.getObject(2, Double.class)),
                user, Date.valueOf(from), Date.valueOf(until));
        List<Bmi> bmi = jdbc.query("""
                select measured_at,bmi_value from public.lifestyle_bio
                where user_id=? and measured_at>=? and measured_at<? and bmi_value>0
                order by measured_at,bio_id limit ?
                """, (rs, row) -> new Bmi(rs.getTimestamp(1).toInstant(), rs.getObject(2, Double.class)),
                user, timestamp(period.from().minusDays(89)), timestamp(until), LIMIT + 1);
        return new Input(births.isEmpty() ? null : births.getFirst(), sleep, exercise, steps, bmi,
                sleep.size() > LIMIT || exercise.size() > LIMIT || bmi.size() > LIMIT);
    }

    private List<Session> sessions(UUID user, String table, String minutes, LocalDate from, LocalDate until) {
        // 식별자는 위의 내부 상수만 사용한다. 종료일로 주 수면과 낮잠을 같은 날짜에 배치한다.
        return jdbc.query("select start_at,end_at," + minutes + " from public." + table
                        + " where user_id=? and start_at>=? and start_at<? and end_at>=? and end_at<? order by end_at,start_at limit ?",
                (rs, row) -> new Session(rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(),
                        rs.getTimestamp(2).toInstant(), rs.getObject(3, Double.class)),
                user, timestamp(from.minusDays(1)), timestamp(until), timestamp(from), timestamp(until), LIMIT + 1);
    }
    private static Timestamp timestamp(LocalDate day) {
        return Timestamp.from(day.atStartOfDay(HealthPeriod.ZONE).toInstant());
    }
}
