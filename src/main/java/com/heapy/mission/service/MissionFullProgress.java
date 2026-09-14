package com.heapy.mission.service;

import com.heapy.mission.model.MissionEvidence;
import com.heapy.mission.model.MissionEvidence.Event;
import com.heapy.mission.model.MissionSuggestion;
import com.heapy.mission.repository.MissionRepository.MissionRow;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import static com.heapy.mission.service.MissionFullRecommendationEngine.*;

/** 카탈로그별 시간·주간·기록 완료 조건. 목표는 수락 당시 스냅샷을 사용한다. @author 김진우 */
public final class MissionFullProgress {
    private MissionFullProgress() { }
    public static Instant endsAt(MissionSuggestion suggestion, LocalDate day) {
        var p=suggestion.parameters();
        return switch(suggestion.period()) {
            case "WEEKLY" -> at(day.with(TemporalAdjusters.next(DayOfWeek.MONDAY)),suggestion.category().equals("SLEEP")?720:0);
            case "SEVEN_DAYS" -> at(day.plusDays(8),0);
            case "SCHEDULED" -> at(LocalDate.parse((String)p.get("targetDate")),1440);
            default -> suggestion.completion().equals("bed_prepare")?bedAt(day,number(p,"minute")):
                    at(day.plusDays(suggestion.category().equals("SLEEP")&&!suggestion.missionType().equals("RECORDING")?2:1),0);
        };
    }
    public static int progress(MissionRow row,MissionEvidence evidence,Instant now) {
        var p=row.parameters(); var all=evidence.events(); LocalDate day=row.missionDate();
        Instant end=now.isBefore(row.endsAt())?now:row.endsAt(),start=at(day,0);
        LocalDate monday=day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var sleeps=all.stream().filter(e->e.type().equals("sleep")&&!e.start().isBefore(row.startsAt())&&!e.end().isAfter(end)).toList();
        return switch(row.completion()) {
            case "manual_bp", "manual_weight", "manual_sleep", "manual_water" -> (int) all.stream()
                    .filter(e -> e.type().equals(row.completion()) && !e.start().isBefore(row.startsAt())
                            && !e.start().isAfter(end) && e.tag().equals(day.toString())).count();
            case "record_checkup_any" -> (int) all.stream().filter(e -> e.type().equals("checkup")
                    && !e.start().isBefore(row.startsAt()) && !e.start().isAfter(end)).count();
            case "manual" -> row.currentValue();
            case "bedtime" -> countSleep(sleeps,row,number(p,"minute"),false,false,0);
            case "wake" -> countSleep(sleeps,row,number(p,"minute"),true,false,0);
            case "weekend_wake" -> (int)sleeps.stream().filter(e->date(e.end()).equals(LocalDate.parse((String)p.get("targetDate")))&&near(minute(e.end()),number(p,"minute"))).map(e->date(e.end())).distinct().count();
            case "weekly_bed" -> countSleep(sleeps,row,number(p,"minute"),false,true,0);
            case "weekly_wake" -> countSleep(sleeps,row,number(p,"minute"),true,true,0);
            case "weekly_sleep" -> countSleep(sleeps,row,0,false,true,number(p,"minutes"));
            case "bed_prepare" -> row.currentValue();
            case "weekly_moderate" -> (int)Math.floor(all.stream().filter(e->e.type().equals("exercise")&&e.tag().endsWith(":MODERATE")).mapToDouble(e->overlap(e,at(monday,0),end)).sum());
            case "weekly_walk" -> (int)all.stream().filter(MissionFullRecommendationEngine::isWalk).filter(e->overlap(e,at(monday,0),end)>=30).count();
            case "weekly_strength" -> {
                var dates=new java.util.HashSet<String>();
                all.stream().filter(MissionFullRecommendationEngine::isStrength).filter(e->!e.start().isBefore(row.startsAt())&&!e.end().isAfter(end))
                    .forEach(e->dates.add(date(e.start()).toString()));
                if(p.get("_manualDays") instanceof List<?> manual) manual.forEach(d->dates.add(d.toString()));
                yield dates.size();
            }
            case "distance" -> (int)Math.floor(all.stream().filter(e->e.type().equals("distance")&&date(e.start()).equals(day)).mapToDouble(Event::value).max().orElse(0));
            case "meal_walk" -> {
                var meals=all.stream().filter(e->e.type().equals("meal")&&e.tag().equals(p.get("meal"))&&date(e.start()).equals(day)).toList();
                yield (int)Math.floor(meals.stream().mapToDouble(m->walkMinutes(all,m.start(),m.start().plusSeconds(7200).isBefore(end)?m.start().plusSeconds(7200):end)).max().orElse(0));
            }
            case "water_before_time","water_before_meal" -> {
                Instant cutoff=at(day,number(p,"minute"));
                if(row.completion().equals("water_before_meal")) {
                    if(all.stream().noneMatch(e->e.type().equals("meal")&&e.tag().equals(p.get("meal"))&&date(e.start()).equals(day))) yield 0;
                    cutoff=all.stream().filter(e->e.type().equals("meal")&&e.tag().equals(p.get("meal"))&&date(e.start()).equals(day)).map(Event::start).min(Instant::compareTo).orElse(cutoff);
                }
                Instant until=cutoff.isBefore(end)?cutoff:end;
                yield (int)Math.floor(all.stream().filter(e->e.type().equals("water")&&!e.start().isBefore(start)&&e.start().isBefore(until)).mapToDouble(Event::value).sum());
            }
            case "bp_am" -> bpPair(all,day,number(p,"wakeMinute"))?1:0;
            case "bp_pm" -> bpPair(all,day,number(p,"bedMinute")-60)?1:0;
            case "bp_week" -> bpDays(all,day.plusDays(1),date(end).plusDays(1),number(p,"wakeMinute"),number(p,"bedMinute"));
            case "record_weight" -> (int)all.stream().filter(e->e.type().equals("weight")&&date(e.start()).equals(LocalDate.parse((String)p.get("targetDate")))&&near(minute(e.start()),number(p,"minute"))).count();
            case "record_sleep" -> (int)all.stream().filter(e->e.type().equals("sleep")&&e.tag().equals("manual")&&date(e.end()).equals(day)&&e.start().isBefore(e.end())).count();
            case "record_checkup" -> (int)all.stream().filter(e->e.type().equals("checkup")&&!e.start().isBefore(row.startsAt())&&LocalDate.ofEpochDay((long)e.value()).getYear()==number(p,"year")).count();
            case "record_old_checkup" -> (int)all.stream().filter(e->e.type().equals("checkup")&&!e.start().isBefore(row.startsAt())&&LocalDate.ofEpochDay((long)e.value()).isBefore(LocalDate.parse((String)p.get("beforeDate")))).count();
            default -> 0;
        };
    }
    private static int countSleep(List<Event> sleeps,MissionRow row,int goal,boolean wake,boolean weekly,int minutes) {
        LocalDate until=row.missionDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        return (int)sleeps.stream().filter(e->{
            LocalDate night=date(e.start()).minusDays(minute(e.start())<720?1:0);
            if(weekly ? night.isBefore(row.missionDate())||!night.isBefore(until) : !night.equals(row.missionDate()))return false;
            return minutes>0?e.value()>=minutes:near(minute(wake?e.end():e.start()),goal);
        }).map(e->date(e.start()).minusDays(minute(e.start())<720?1:0)).distinct().count();
    }
    private static boolean near(int actual,int goal) {int difference=Math.abs(actual-goal);return Math.min(difference,1440-difference)<=30;}
    private static int number(Map<String,Object> p,String key) {return ((Number)p.get(key)).intValue();}
    private static Instant bedAt(LocalDate day,int minute) {return at(day.plusDays(minute<720?1:0),minute);}
}
