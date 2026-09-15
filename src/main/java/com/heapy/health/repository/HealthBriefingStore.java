package com.heapy.health.repository;

import com.heapy.checkup.OcrJson;
import com.heapy.health.model.HealthBriefing;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;

/** 홈 브리핑을 하루 한 행으로 보관한다. 원천 건강 기록에는 쓰지 않는다. @author 고수연 */
@Repository
public class HealthBriefingStore {
    private final JdbcTemplate jdbc;
    public HealthBriefingStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * 오늘 몫을 차지한다. 이미 있으면 false 다.
     *
     * `HealthAnalysisRunner` 가 실행권을 잡는 방식과 같다. 배치가 겹쳐 돌거나 서버가 여럿이어도
     * 같은 날 브리핑을 두 번 만들지 않는다. 모델 호출은 이 뒤에서만 일어난다.
     */
    public boolean claim(UUID user, LocalDate date) {
        return jdbc.update("""
                insert into public.daily_health_briefings(user_id,briefing_date,status)
                values (?,?,'pending') on conflict (user_id,briefing_date) do nothing
                """, user, Date.valueOf(date)) == 1;
    }

    /** 실패한 오늘 브리핑만 원자적으로 재시도한다. @author 김진우 */
    public boolean retryFailed(UUID user, LocalDate date) {
        return jdbc.update("""
                update public.daily_health_briefings
                   set status='pending', failure_code=null, updated_at=current_timestamp
                 where user_id=? and briefing_date=? and status='failed'
                """, user, Date.valueOf(date)) == 1;
    }

    /** 차지해 둔 행을 채운다. 실패해도 그 사실을 남겨 다음 배치가 또 부르지 않게 한다. */
    public void complete(UUID user, LocalDate date, String status, JsonNode briefing,
                         JsonNode evidence, String failureCode, String scoreVersion) {
        jdbc.update("""
                update public.daily_health_briefings
                   set status=?, headline=?, chip=?, body=?,
                       sections=coalesce(?::jsonb,'[]'::jsonb),
                       evidence_snapshot=coalesce(?::jsonb,'{}'::jsonb),
                       metric_policy_versions=?::jsonb,
                       failure_code=?, generated_at=clock_timestamp(), updated_at=clock_timestamp()
                 where user_id=? and briefing_date=? and status='pending'
                """,
                status,
                text(briefing, "headline"), text(briefing, "chip"), text(briefing, "body"),
                json(briefing == null ? null : briefing.get("sections")), json(evidence),
                "{\"score\":\"" + scoreVersion + "\"}", failureCode,
                user, Date.valueOf(date));
    }

    public HealthBriefing find(UUID user, LocalDate date) {
        List<HealthBriefing> rows = jdbc.query("""
                select briefing_date,status,headline,chip,body,sections::text as sections,generated_at
                  from public.daily_health_briefings where user_id=? and briefing_date=?
                """, (rs, row) -> new HealthBriefing(rs.getDate("briefing_date").toLocalDate(),
                        rs.getString("status"), rs.getString("headline"), rs.getString("chip"),
                        rs.getString("body"), tree(rs.getString("sections")),
                        rs.getTimestamp("generated_at") == null ? null : rs.getTimestamp("generated_at").toInstant()),
                user, Date.valueOf(date));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 오래된 브리핑을 지운다. 지난 흐름을 보여주는 화면이 생기면 이 기간을 늘린다. */
    public void pruneAll(LocalDate from) {
        jdbc.update("delete from public.daily_health_briefings where briefing_date<?", Date.valueOf(from));
    }

    private static String text(JsonNode node, String field) {
        return node == null || !node.path(field).isTextual() ? null : node.path(field).asText();
    }

    private static String json(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.toString();
    }

    private static JsonNode tree(String value) {
        if (value == null) return null;
        try {
            return OcrJson.MAPPER.readTree(value);
        } catch (RuntimeException error) {
            return null;
        }
    }
}
