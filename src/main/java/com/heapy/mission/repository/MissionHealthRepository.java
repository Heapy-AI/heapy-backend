package com.heapy.mission.repository;

import com.heapy.health.model.HealthPeriod;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.List;
import java.util.HashMap;
import java.util.Set;
import com.heapy.mission.model.MissionEvidence;
import java.util.UUID;
import com.heapy.mission.repository.MissionRepository.MissionRow;
import com.heapy.mission.service.MissionFullProgress;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 저장된 원천 기록을 다시 합산하여 재동기화·물 수정 시 이중 가산하지 않는다. @author 김진우 */
@Repository
public class MissionHealthRepository {
    private final JdbcTemplate jdbc;
    private final MissionEvidenceRepository evidence;
    @Autowired
    public MissionHealthRepository(JdbcTemplate jdbc,MissionEvidenceRepository evidence) {this.jdbc=jdbc;this.evidence=evidence;}
    public MissionHealthRepository(JdbcTemplate jdbc) {this(jdbc,new MissionEvidenceRepository(jdbc));}

    private static final Set<String> DIRECT=Set.of("steps","active_time_minutes","floors","water","water_after_accept","sleep","walk","morning_walk","afternoon_walk","stretch","continuous_walk");
    public Map<UUID,Integer> recommendedProgress(UUID user,List<MissionRow> rows,Instant now) {
        MissionEvidence shared=rows.stream().anyMatch(r->!DIRECT.contains(r.completion()))
                ? evidence.load(user,now.atZone(HealthPeriod.ZONE).toLocalDate(),now):null;
        Map<UUID,Integer> values=new HashMap<>();
        for(var row:rows) values.put(row.missionId(),recommendedProgress(user,row,now,shared));
        return values;
    }
    private int recommendedProgress(UUID user, MissionRow row, Instant now,MissionEvidence shared) {
        LocalDate date=row.missionDate();
        Instant start=date.atStartOfDay(HealthPeriod.ZONE).toInstant();
        Instant end=now.isBefore(row.endsAt()) ? now : row.endsAt();
        return switch(row.completion()) {
            case "steps", "active_time_minutes", "floors" -> {
                String column=switch(row.completion()) { case "steps" -> "steps"; case "floors" -> "floors"; default -> "active_time_minutes"; };
                yield jdbc.queryForObject("select coalesce(max("+column+"),0)::int from public.lifestyle_activity where user_id=? and record_date=?",
                        Integer.class,user,java.sql.Date.valueOf(date));
            }
            case "water", "water_after_accept" -> jdbc.queryForObject("""
                    select least(2147483647,floor(coalesce(sum(amount_ml),0)))::int from public.lifestyle_water_intake
                    where user_id=? and consumed_at>=? and consumed_at<?
                    """,Integer.class,user,Timestamp.from(row.completion().equals("water_after_accept")?row.startsAt():start),Timestamp.from(end));
            case "sleep" -> jdbc.queryForObject("""
                    select coalesce(max(total_sleep_minutes),0)::int from public.lifestyle_sleep
                    where user_id=? and start_at>=? and start_at<? and end_at<=? and end_at>start_at
                    """,Integer.class,user,Timestamp.from(row.startsAt()),
                    Timestamp.from(date.plusDays(1).atTime(12,0).atZone(HealthPeriod.ZONE).toInstant()),Timestamp.from(end));
            case "walk" -> exercise(user,date,0,24,"^(walking|walk|걷기)$",end);
            case "morning_walk" -> exercise(user,date,0,12,"^(walking|walk|걷기)$",end);
            case "afternoon_walk" -> exercise(user,date,12,24,"^(walking|walk|걷기)$",end);
            case "stretch" -> exercise(user,date,0,24,"^(stretching|stretch|스트레칭)$",end);
            case "continuous_walk" -> jdbc.queryForObject("""
                    select least(2147483647,floor(coalesce(max(duration_seconds *
                        extract(epoch from(least(end_at,?)-greatest(start_at,?))) /
                        nullif(extract(epoch from(end_at-start_at)),0)),0)/60))::int
                    from public.lifestyle_exercise where user_id=? and start_at<? and end_at>?
                        and end_at>start_at and lower(trim(exercise_type)) ~ '^(walking|walk|걷기)$'
                    """,Integer.class,Timestamp.from(end),Timestamp.from(start),user,Timestamp.from(end),Timestamp.from(start));
            default -> MissionFullProgress.progress(row,shared,now);
        };
    }

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
