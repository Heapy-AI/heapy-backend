package com.heapy.mission.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.heapy.health.model.HealthPeriod;
import com.heapy.mission.model.MissionSuggestion;
import com.heapy.mission.service.MissionFullProgress;
import com.heapy.mission.service.MissionRecommendationEngine.Day;
import com.heapy.mission.service.MissionRecommendationEngine.Definition;
import com.heapy.mission.service.MissionRecommendationEngine.History;
import com.heapy.mission.service.MissionRecommendationEngine.Input;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 기존 카탈로그·규칙·사용자 미션 테이블을 재사용한다. @author 김진우 */
@Repository
public class MissionRecommendationRepository {
    private final JdbcTemplate jdbc;
    private static final ObjectMapper JSON = new ObjectMapper();
    public MissionRecommendationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static Double number(Object value) { return value == null ? null : ((Number)value).doubleValue(); }

    public List<Definition> definitions() {
        return jdbc.query("""
                select c.mission_code,c.version,r.version,r.condition->>'ruleId',
                       r.completion_rule->>'metric',coalesce((r.completion_rule->>'manualAllowed')::boolean,false)
                from public.mission_catalog c join public.mission_rules r on r.mission_code=c.mission_code
                where c.version=3 and r.version=3 and c.is_active
                """, (rs,n) -> new Definition(rs.getString(1),rs.getInt(2),rs.getInt(3),rs.getString(4),rs.getString(5),rs.getBoolean(6)));
    }

    public Input input(UUID user, LocalDate today, Instant now) {
        var statuses = jdbc.query("""
                select item_code,status from public.health_checkup_results where record_id=(
                    select record_id from public.health_checkup_records where user_id=? and measured_at<=?
                    order by measured_at desc,created_at desc,record_id limit 1)
                """, (rs,n) -> List.of(rs.getString(1), rs.getString(2) == null ? "" : rs.getString(2)), user,Date.valueOf(today));
        Set<String> managed = new HashSet<>();
        for (var status : statuses) {
            String code = status.get(0), value = status.get(1).trim();
            if (!Set.of("정상B", "정상(B)", "경계", "주의", "관리", "관리대상", "정상(경계)", "BORDERLINE").contains(value)) continue;
            if (Set.of("SYSTOLIC_BP", "DIASTOLIC_BP").contains(code)) managed.add("BP");
            if (Set.of("BMI", "WEIGHT").contains(code)) managed.add("WEIGHT");
            if (code.equals("FASTING_BLOOD_GLUCOSE") || code.equals("FASTING_GLUCOSE")) managed.add("GLUCOSE");
            if (Set.of("AST","ALT","GGT","GAMMA_GTP").contains(code)) managed.add("LIVER");
            if (Set.of("TOTAL_CHOLESTEROL", "LDL_CHOLESTEROL", "TRIGLYCERIDES").contains(code)) managed.add("LIPID");
        }
        Instant from = today.minusDays(56).atStartOfDay(HealthPeriod.ZONE).toInstant();
        Instant end = today.atStartOfDay(HealthPeriod.ZONE).toInstant();
        var days = jdbc.query("""
                with a as (select record_date as day,max(steps) as steps,max(active_time_minutes) as minutes,max(floors) as floors
                    from public.lifestyle_activity where user_id=? and record_date>=? and record_date<? group by record_date),
                w as (select (consumed_at at time zone 'Asia/Seoul')::date as day,sum(amount_ml) as water
                    from public.lifestyle_water_intake where user_id=? and consumed_at>=? and consumed_at<? group by 1),
                s as (select (end_at at time zone 'Asia/Seoul')::date as day,
                    percentile_cont(.5) within group(order by total_sleep_minutes) as sleep
                    from public.lifestyle_sleep where user_id=? and end_at>=? and end_at<?
                    and total_sleep_minutes>0 and end_at>start_at group by 1),
                b as (select (measured_at at time zone 'Asia/Seoul')::date as day,
                    percentile_cont(.5) within group(order by weight_kg) as weight
                    from public.lifestyle_bio where user_id=? and measured_at>=? and measured_at<? and weight_kg>0 group by 1)
                select day,steps,minutes,floors,water,sleep,weight from a full join w using(day) full join s using(day) full join b using(day)
                order by day
                """, (rs,n) -> new Day(rs.getDate(1).toLocalDate(),number(rs.getObject(2)),number(rs.getObject(3)),
                    number(rs.getObject(4)),number(rs.getObject(5)),number(rs.getObject(6)),number(rs.getObject(7))),
                user,Date.valueOf(today.minusDays(56)),Date.valueOf(today),user,Timestamp.from(from),Timestamp.from(end),
                user,Timestamp.from(from),Timestamp.from(end),user,Timestamp.from(from),Timestamp.from(end));
        var history = jdbc.query("""
                select um.mission_code,coalesce(um.target_value->>'category',mt.category,''),case when um.completed_at is not null then (um.completed_at at time zone 'Asia/Seoul')::date when um.ends_at<=? then (um.ends_at at time zone 'Asia/Seoul')::date else um.mission_date end,
                    um.completed_at is not null,um.status='active' and um.ends_at>?,um.status='abandoned'
                from public.user_missions um left join public.mission_templates mt using(template_id)
                where um.user_id=? and (um.mission_date>=? or (um.status='active' and um.ends_at>?))
                order by um.created_at desc
                """, (rs,n) -> new History(rs.getString(1),rs.getString(2),rs.getDate(3).toLocalDate(),rs.getBoolean(4),rs.getBoolean(5),rs.getBoolean(6)),
                Timestamp.from(now),Timestamp.from(now),user,Date.valueOf(today.minusDays(30)),Timestamp.from(now));
        return new Input(today,true,true,managed,days,history);
    }

    public UUID existing(UUID user, UUID key, String code) {
        var rows = jdbc.query("select mission_id,mission_code from public.user_missions where user_id=? and idempotency_key=?",
                (rs,n) -> List.of(rs.getObject(1,UUID.class).toString(),rs.getString(2)), user,key);
        if (rows.isEmpty()) return null;
        if (!rows.getFirst().get(1).equals(code)) throw new IllegalArgumentException("다른 미션에 사용된 요청 키입니다.");
        return UUID.fromString(rows.getFirst().get(0));
    }

    public UUID accept(UUID user, UUID key, MissionSuggestion suggestion, LocalDate date, Instant now) {
        Instant end = MissionFullProgress.endsAt(suggestion,date);
        try {
            return jdbc.queryForObject("""
                    insert into public.user_missions(user_id,mission_code,catalog_version,rule_version,source,target_value,
                        mission_date,starts_at,ends_at,idempotency_key)
                    values(?,?,?,?,'recommendation',?::jsonb,?,?,?,?) returning mission_id
                    """, UUID.class,user,suggestion.code(),suggestion.catalogVersion(),suggestion.ruleVersion(),
                    JSON.writeValueAsString(suggestion),Date.valueOf(date),Timestamp.from(now),Timestamp.from(end),key);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("미션 목표 저장에 실패했습니다.",exception);
        }
    }

    public int todayCount(UUID user, LocalDate date) {
        return jdbc.queryForObject("select count(*)::int from public.user_missions where user_id=? and mission_date=?",Integer.class,user,Date.valueOf(date));
    }
}
