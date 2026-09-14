package com.heapy.health.repository;

import com.heapy.health.model.HealthPeriod;
import com.heapy.health.model.LifestyleScore.Component;
import com.heapy.health.model.LifestyleScore.Day;
import com.heapy.health.model.LifestyleScore.Metabolic;
import com.heapy.health.model.LifestyleScore.Saved;
import com.heapy.health.model.LifestyleScore.SleepParts;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
                """, LifestyleScoreStore::day, user, Date.valueOf(period.from()), Date.valueOf(period.to()));
    }

    // 작성자: 고수연 — sleep_detail·metabolic_detail 은 읽지 않는다. 조회 응답에 나가지 않는
    // 값이라 꺼낼 이유가 없다. 서술 값이 필요하면 그때 이 표를 직접 읽으면 된다.
    private static Day day(ResultSet rs, int row) throws SQLException {
        return new Day(rs.getDate("score_date").toLocalDate(), rs.getObject("total_score", Integer.class),
                new Component(rs.getObject("sleep_score", Double.class), rs.getInt("sleep_recorded_days"), 5),
                new Component(rs.getObject("activity_score", Double.class), rs.getInt("activity_recorded_days"), 7),
                rs.getObject("bmi_score", Double.class),
                rs.getDate("bmi_date") == null ? null : rs.getDate("bmi_date").toLocalDate(),
                List.copyOf(Arrays.asList((String[]) rs.getArray("reasons").getArray())),
                new SleepParts(rs.getObject("sleep_duration_score", Double.class),
                        rs.getObject("sleep_regularity_score", Double.class),
                        rs.getObject("sleep_stability_score", Double.class),
                        rs.getObject("sleep_jetlag_score", Double.class),
                        rs.getObject("sleep_coverage", Double.class)),
                rs.getString("bmi_source"), rs.getString("bmi_notice"),
                new Metabolic(rs.getObject("metabolic_score", Double.class),
                        rs.getObject("metabolic_bp_score", Double.class),
                        rs.getObject("metabolic_glucose_score", Double.class),
                        rs.getObject("metabolic_coverage", Double.class)),
                rs.getObject("score_coverage", Double.class));
    }

    public void insert(UUID user, Saved saved, String version) {
        Day day = saved.day();
        SleepParts parts = day.sleepParts();
        Metabolic metabolic = day.metabolic();
        jdbc.update("""
                insert into public.lifestyle_daily_scores
                  (user_id,score_date,policy_version,total_score,sleep_score,sleep_recorded_days,
                   activity_score,activity_recorded_days,bmi_score,bmi_date,reasons,
                   sleep_duration_score,sleep_regularity_score,sleep_stability_score,sleep_jetlag_score,
                   sleep_coverage,bmi_source,bmi_notice,metabolic_score,metabolic_bp_score,
                   metabolic_glucose_score,metabolic_coverage,score_coverage,sleep_detail,metabolic_detail)
                values (?,?,?,?,?,?,?,?,?,?,?::text[],?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)
                on conflict (user_id,score_date) do nothing
                """, user, Date.valueOf(day.date()), version, day.score(), day.sleep().score(),
                day.sleep().recordedDays(), day.activity().score(), day.activity().recordedDays(),
                day.bmiScore(), day.bmiDate() == null ? null : Date.valueOf(day.bmiDate()),
                "{" + String.join(",", day.reasons()) + "}",
                parts == null ? null : parts.duration(), parts == null ? null : parts.regularity(),
                parts == null ? null : parts.stability(), parts == null ? null : parts.jetlag(),
                parts == null ? null : parts.coverage(),
                day.bmiSource(), day.bmiNotice(),
                metabolic == null ? null : metabolic.score(), metabolic == null ? null : metabolic.bloodPressure(),
                metabolic == null ? null : metabolic.glucose(), metabolic == null ? null : metabolic.coverage(),
                day.coverage(), saved.sleepDetail(), saved.metabolicDetail());
    }

    /**
     * 잠금·정리·저장을 한 트랜잭션에서 끝낸다.
     *
     * `pg_advisory_xact_lock`은 트랜잭션이 끝나면 풀린다. 잠금과 저장이 서로 다른
     * 트랜잭션에 있으면 잠그자마자 놓는 셈이라 아무것도 막지 못한다.
     *
     * 외부 분석 호출은 이 메서드 밖에서 이미 끝나 있어야 한다. 여기서는 DB만 만진다.
     *
     * @author 고수연
     */
    @Transactional(timeout = 20)
    public List<Day> save(UUID user, HealthPeriod period, List<Saved> rows, String version) {
        lock(user);
        prune(user, period.from());
        var known = new HashSet<LocalDate>();
        find(user, period).forEach(day -> known.add(day.date()));
        for (Saved row : rows) {
            LocalDate date = row.day().date();
            if (date.isBefore(period.from()) || date.isAfter(period.to())) continue;
            if (!known.contains(date)) insert(user, row, version);
        }
        return find(user, period);
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
