package com.heapy.mission.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heapy.mission.model.MissionEvidence;
import com.heapy.mission.model.MissionEvidence.Event;
import com.heapy.mission.model.MissionEvidence.Exposure;
import com.heapy.mission.model.MissionOptions;
import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import com.heapy.health.model.HealthPeriod;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 기존 원천 기록·사용자 설정·피드백을 하나의 증거 계약으로 읽는다. @author 김진우 */
@Repository
public class MissionEvidenceRepository {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final JdbcTemplate jdbc;
    public MissionEvidenceRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public MissionOptions options(UUID user) {
        return jdbc.query("select mission_options from public.users where user_id=?",(rs,n)-> {
            try {return JSON.readValue(rs.getString(1),MissionOptions.class);}
            catch(JsonProcessingException error) {throw new IllegalStateException("미션 설정을 읽지 못했습니다.",error);}
        },user).stream().findFirst().orElse(MissionOptions.empty());
    }
    public MissionOptions saveOptions(UUID user,MissionOptions options,LocalDate today) {
        if(options.checkupYear()!=null&&options.checkupYear()>today.getYear()
                || options.sevenDayBloodPressurePlan()&&!options.bloodPressureTracking()
                || options.bloodPressureTracking()&&(options.wakeMinute()==null||options.bedMinute()==null)
                || options.weightTracking()&&(options.weightWeekday()==null||options.weightMinute()==null))
            throw new HeapyException(ErrorCode.INVALID_INPUT);
        try {jdbc.update("update public.users set mission_options=?::jsonb,updated_at=now() where user_id=?",JSON.writeValueAsString(options),user);}
        catch(JsonProcessingException error) {throw new IllegalStateException("미션 설정 저장에 실패했습니다.",error);}
        return options;
    }
    public MissionEvidence load(UUID user,LocalDate today,Instant now) {
        Instant start=today.minusDays(56).atStartOfDay(HealthPeriod.ZONE).toInstant();
        List<Event> events=new ArrayList<>();
        events.addAll(jdbc.query("""
                select 'sleep',start_at,end_at,total_sleep_minutes::double precision,source from public.lifestyle_sleep
                where user_id=? and end_at>=? and end_at<=? and end_at>start_at and total_sleep_minutes>0
                """,MissionEvidenceRepository::event,user,Timestamp.from(start),Timestamp.from(now)));
        events.addAll(jdbc.query("""
                select 'exercise',start_at,end_at,duration_seconds/60.0,exercise_type||':UNKNOWN' from public.lifestyle_exercise
                where user_id=? and end_at>=? and end_at<=? and end_at>start_at
                """,MissionEvidenceRepository::event,user,Timestamp.from(start),Timestamp.from(now)));
        events.addAll(jdbc.query("""
                select 'distance',record_date::timestamp at time zone 'Asia/Seoul',record_date::timestamp at time zone 'Asia/Seoul',max(distance_m),''
                from public.lifestyle_activity where user_id=? and record_date>=? and record_date<=? and distance_m is not null group by record_date
                """,MissionEvidenceRepository::event,user,java.sql.Date.valueOf(today.minusDays(56)),java.sql.Date.valueOf(today)));
        events.addAll(jdbc.query("""
                select 'meal',consumed_at,consumed_at,1,upper(meal_type) from public.lifestyle_nutrition
                where user_id=? and consumed_at>=? and consumed_at<=? and meal_type is not null
                """,MissionEvidenceRepository::event,user,Timestamp.from(start),Timestamp.from(now)));
        events.addAll(jdbc.query("""
                select 'water',consumed_at,consumed_at,amount_ml,'' from public.lifestyle_water_intake
                where user_id=? and consumed_at>=? and consumed_at<=?
                """,MissionEvidenceRepository::event,user,Timestamp.from(start),Timestamp.from(now)));
        events.addAll(jdbc.query("""
                select case when bio_type='blood_pressure' then 'bp' else 'weight' end,measured_at,measured_at,1,source from public.lifestyle_bio
                where user_id=? and measured_at>=? and measured_at<=?
                and ((bio_type='blood_pressure' and systolic_mmhg is not null and diastolic_mmhg is not null) or (bio_type='body_composition' and weight_kg is not null))
                """,MissionEvidenceRepository::event,user,Timestamp.from(start),Timestamp.from(now)));
        var checkups=jdbc.query("""
                select 'checkup',created_at,created_at,(measured_at-date '1970-01-01')::double precision,measured_at::text
                from public.health_checkup_records where user_id=? and measured_at<=? and created_at<=?
                """,MissionEvidenceRepository::event,user,java.sql.Date.valueOf(today),Timestamp.from(now));
        events.addAll(checkups);
        LocalDate latest=checkups.stream().map(e->LocalDate.ofEpochDay((long)e.value())).max(LocalDate::compareTo).orElse(null);
        var exposures=jdbc.query("""
                select coalesce(f.mission_code,m.mission_code),f.event_type,(f.created_at at time zone 'Asia/Seoul')::date
                from public.mission_feedback f left join public.user_missions m on m.user_mission_id=f.user_mission_id
                where f.user_id=? and f.created_at>=? order by f.created_at desc
                """,(rs,n)->new Exposure(rs.getString(1),rs.getString(2),rs.getDate(3).toLocalDate()),user,Timestamp.from(start));
        return new MissionEvidence(options(user),List.copyOf(events),checkups.size(),latest,exposures);
    }
    private static Event event(java.sql.ResultSet rs,int row) throws java.sql.SQLException {
        return new Event(rs.getString(1),rs.getTimestamp(2).toInstant(),rs.getTimestamp(3).toInstant(),rs.getDouble(4),rs.getString(5)==null?"":rs.getString(5));
    }
    public boolean existingFeedback(UUID user,UUID key,String code,String event) {
        var rows=jdbc.query("select mission_code,event_type from public.mission_feedback where user_id=? and event_key=?",(rs,n)->List.of(rs.getString(1),rs.getString(2)),user,key);
        if(rows.isEmpty())return false;
        if(!rows.getFirst().equals(List.of(code,event)))throw new HeapyException(ErrorCode.MISSION_CONFLICT);
        return true;
    }

    public void feedback(UUID user,String code,String event,UUID key,Instant now) {
        jdbc.update("""
                insert into public.mission_feedback(user_id,mission_code,event_type,event_key,created_at)
                values(?,?,?,?,?) on conflict(user_id,event_key) where event_key is not null do nothing
                """,user,code,event,key,Timestamp.from(now));
    }
}
