package com.heapy.mission.repository;

import com.heapy.health.model.HealthPeriod;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 저장된 원천 기록을 다시 합산하여 재동기화·물 수정 시 이중 가산하지 않는다. @author 김진우 */
@Repository
public class MissionHealthRepository {
    private final JdbcTemplate jdbc;
    public MissionHealthRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public Map<String,Integer> progress(UUID user, LocalDate date, Instant now) {
        Instant start=date.atStartOfDay(HealthPeriod.ZONE).toInstant();
        Instant end=date.plusDays(1).atStartOfDay(HealthPeriod.ZONE).toInstant();
        Instant until=now.isBefore(end)?now:end;
        int water=jdbc.queryForObject("""
                select least(2147483647,floor(coalesce(sum(amount_ml),0)/250))::int from public.lifestyle_water_intake
                where user_id=? and consumed_at>=? and consumed_at<?
                """,Integer.class,user,Timestamp.from(start),Timestamp.from(until));
        int sleep=jdbc.queryForObject("""
                select case when exists(select 1 from public.lifestyle_sleep
                where user_id=? and end_at>=? and end_at<=? and total_sleep_minutes>=180
                and start_at>=? and start_at<?) then 1 else 0 end
                """,Integer.class,user,Timestamp.from(start),Timestamp.from(until),
                Timestamp.from(date.minusDays(1).atTime(18,0).atZone(HealthPeriod.ZONE).toInstant()),Timestamp.from(start));
        return Map.of("DRINK_WATER",water,"SLEEP_BEFORE_MIDNIGHT",sleep,
                "WALK_AFTER_LUNCH",exercise(user,date,12,18,"^(walking|walk|걷기)$",until),
                "EASY_CYCLING",exercise(user,date,0,24,"^(cycling|biking|bike|자전거|자전거 타기|실내 자전거|stationary_bike|stationary_biking|indoor_cycling)$",until),
                "MORNING_STRETCH",exercise(user,date,0,12,"^(stretching|stretch|스트레칭)$",until));
    }

    private int exercise(UUID user,LocalDate date,int fromHour,int toHour,String types,Instant now) {
        Instant from=date.atStartOfDay(HealthPeriod.ZONE).plusHours(fromHour).toInstant();
        Instant to=date.atStartOfDay(HealthPeriod.ZONE).plusHours(toHour).toInstant();
        if(now.isBefore(to)) to=now;
        if(!from.isBefore(to)) return 0;
        return jdbc.queryForObject("""
                select least(2147483647,floor(coalesce(sum(duration_seconds *
                    extract(epoch from (least(end_at,?)-greatest(start_at,?))) /
                    nullif(extract(epoch from(end_at-start_at)),0)),0)/60))::int
                from public.lifestyle_exercise where user_id=? and start_at<? and end_at>?
                    and end_at>start_at and lower(trim(exercise_type)) ~ ?
                """,Integer.class,Timestamp.from(to),Timestamp.from(from),user,Timestamp.from(to),Timestamp.from(from),types);
    }
}
