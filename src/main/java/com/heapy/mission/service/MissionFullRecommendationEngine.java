package com.heapy.mission.service;

import com.heapy.health.model.HealthPeriod;
import com.heapy.mission.model.MissionEvidence;
import com.heapy.mission.model.MissionEvidence.Event;
import com.heapy.mission.model.MissionSuggestion;
import com.heapy.mission.service.MissionRecommendationEngine.Definition;
import com.heapy.mission.service.MissionRecommendationEngine.Input;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/** 시각·주간·식후·기록 미션을 포함하는 전체 카탈로그 실행기. @author 김진우 */
public final class MissionFullRecommendationEngine {
    private MissionFullRecommendationEngine() { }
    private static final Set<String> DAILY=Set.of("ACT-003","ACT-004","ACT-005","ACT-006","ACT-010",
            "ACT-012","ACT-013","ACT-014","ACT-015","SLP-003","WTR-006","WTR-007");

    public static List<MissionSuggestion> recommend(Input input, MissionEvidence evidence, String scope,
            List<Definition> definitions, Instant now) {
        if(scope.equals("SUMMARY")) return List.of();
        var result=new ArrayList<>(MissionRecommendationEngine.recommend(input,scope,
                definitions.stream().filter(d->DAILY.contains(d.code())).toList()));
        var allEvents=evidence.events(); var options=evidence.options(); LocalDate today=input.today();
        var sleep=events(allEvents,"sleep",today.minusDays(7),today);
        var previousSleep=events(allEvents,"sleep",today.minusDays(14),today.minusDays(7));
        var beds=dailyValues(sleep,e->bedMinute(e.start())); var previousBeds=dailyValues(previousSleep,e->bedMinute(e.start()));
        var wakes=dailyValues(sleep,e->minute(e.end())); var lengths=dailyValues(sleep,Event::value);
        var water=MissionRecommendationEngine.values(input,14,0,"water");
        var recentWater=MissionRecommendationEngine.values(input,7,0,"water");
        var previousWater=MissionRecommendationEngine.values(input,7,7,"water");
        boolean waterLow=water.size()>=7 && options.waterGoalMl()!=null && median(water)<=options.waterGoalMl()-250;
        boolean waterDrop=recentWater.size()>=5 && previousWater.size()>=5 && median(previousWater)>0 && median(recentWater)<=median(previousWater)*.8;
        boolean lowActivity=MissionRecommendationEngine.values(input,28,0,"minutes").size()>=14
                && MissionRecommendationEngine.values(input,7,0,"minutes").stream().filter(v->v<30).count()>=5;
        LocalDate monday=today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        for(var definition:definitions) {
            String code=definition.code(); if(DAILY.contains(code)||Set.of("ACT-007","ACT-008").contains(code)) continue;
            boolean recording=code.startsWith("REC-");
            int target=0,need=20,reliability=10; String unit="COUNT",period="DAILY",title="",reason="",category="";
            Map<String,Object> parameters=new HashMap<>();
            switch(code) {
                case "SLP-001","SLP-008" -> {
                    if(beds.size()<5 || previousBeds.size()<5 || median(beds)-median(previousBeds)<30) continue;
                    // 15분 단위 반올림으로 30분 초과 앞당겨지지 않도록 상향 정렬한다.
                    int bedtime=(int)(Math.ceil((median(beds)-30)/15)*15);
                    parameters.put("minute",Math.floorMod(bedtime,1440)); target=1; category="SLEEP"; need=30;
                    title="오늘 "+time(bedtime)+"에 잠들기"; reason="최근 7일과 이전 7일 취침 시각을 비교했어요.";
                }
                case "SLP-004","SLP-005" -> {
                    if(beds.size()<5 || mad(beds)<=45) continue;
                    int bedtime=round15(median(beds)); parameters.put("minute",Math.floorMod(bedtime,1440));
                    category="SLEEP"; target=code.equals("SLP-005")?3:1; period=target==3?"WEEKLY":"DAILY";
                    title=target==3?"이번 주 3일 "+time(bedtime)+"에 잠들기":"오늘 "+time(bedtime)+"까지 취침 준비 끝내기";
                    reason="최근 7일 취침 시각의 차이를 바탕으로 추천했어요.";
                }
                case "SLP-002","SLP-006" -> {
                    if(wakes.size()<5 || mad(wakes)<=45) continue;
                    int wake=round15(median(wakes)); parameters.put("minute",wake);category="SLEEP";
                    target=code.equals("SLP-006")?3:1;period=target==3?"WEEKLY":"DAILY";
                    title=target==3?"이번 주 3일 "+time(wake)+"에 일어나기":"내일 "+time(wake)+"에 일어나기";
                    reason="최근 7일 기상 시각의 차이를 바탕으로 추천했어요.";
                }
                case "SLP-007" -> {
                    if(lengths.size()<5 || median(lengths)>=420) continue;
                    int minutes=(int)Math.min(420,Math.floor(median(lengths)+30)); parameters.put("minutes",minutes);
                    category="SLEEP";target=3;period="WEEKLY";title="이번 주 3일 "+minutes+"분 이상 자기";
                    reason="최근 7일 수면시간을 바탕으로 한 번에 30분 이내로 늘리는 목표예요.";
                }
                case "SLP-009" -> {
                    if(wakes.size()<5) continue;
                    var weekends=dailyValues(sleep.stream().filter(e->date(e.end()).getDayOfWeek().getValue()>=6).toList(),e->minute(e.end()));
                    var weekdays=dailyValues(sleep.stream().filter(e->date(e.end()).getDayOfWeek().getValue()<6).toList(),e->minute(e.end()));
                    if(weekends.size()<2 || weekdays.size()<3 || median(weekends)-median(weekdays)<90) continue;
                    int wake=(int)(Math.floor((median(weekdays)+60)/15)*15); parameters.put("minute",wake);
                    LocalDate weekend=today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
                    if(today.getDayOfWeek()==DayOfWeek.SUNDAY) weekend=today;
                    if(!at(weekend,wake+30).isAfter(now)) continue;
                    parameters.put("targetDate",weekend.toString());category="SLEEP";target=1;period="SCHEDULED";
                    title="주말에도 "+time(wake)+"에 일어나기";reason="최근 평일과 주말 기상 시각의 차이를 반영했어요.";
                }
                case "ACT-001","ACT-002" -> {
                    String meal=code.equals("ACT-001")?"LUNCH":"DINNER";
                    var meals=events(allEvents,"meal",today.minusDays(7),today).stream().filter(e->e.tag().equals(meal)).toList();
                    if(meals.stream().map(e->date(e.start())).distinct().count()<4 || mealWalkDays(allEvents,meals)>=2) continue;
                    target=15;unit="MINUTE";category="ACTIVITY";parameters.put("meal",meal);
                    title=(meal.equals("LUNCH")?"점심":"저녁")+" 식사 후 15분 걷기";
                    reason="최근 7일 식사 시각과 식후 2시간의 걷기 기록을 함께 확인했어요.";
                }
                case "ACT-007","ACT-008" -> {
                    if(MissionRecommendationEngine.values(input,28,0,"minutes").size()<14) continue;
                    var exercise=events(allEvents,"exercise",today.minusDays(7),today);
                    if(exercise.isEmpty() || exercise.stream().anyMatch(e->e.tag().endsWith(":UNKNOWN"))) continue;
                    double moderate=exercise.stream().filter(e->e.tag().endsWith(":MODERATE")).mapToDouble(Event::value).sum();
                    if(moderate>=150) continue;
                    target=code.equals("ACT-007")?(int)Math.min(150,Math.floor(moderate+30)):
                            (int)Math.min(3,exercise.stream().filter(e->isWalk(e)&&e.value()>=30).count()+1);
                    category="ACTIVITY";unit=code.equals("ACT-007")?"MINUTE":"COUNT";period="WEEKLY";
                    title=code.equals("ACT-007")?"이번 주 중강도 활동 "+target+"분 채우기":"이번 주 30분 걷기 "+target+"회 하기";
                    reason="강도가 확인된 최근 7일 운동 기록을 바탕으로 추천했어요.";
                }
                case "ACT-009" -> {
                    if(MissionRecommendationEngine.values(input,28,0,"minutes").size()<14) continue;
                    List<Double> weeks=new ArrayList<>();
                    for(int i=0;i<4;i++) weeks.add((double)events(allEvents,"exercise",today.minusDays((i+1)*7L),today.minusDays(i*7L)).stream().filter(MissionFullRecommendationEngine::isStrength).count());
                    if(median(weeks)>=2) continue;
                    target=(int)Math.min(2,Math.floor(median(weeks)+1));category="ACTIVITY";period="WEEKLY";
                    title="이번 주 근력 운동 "+target+"회 하기";reason="최근 28일 근력 운동 횟수를 바탕으로 추천했어요.";
                }
                case "ACT-011" -> {
                    var current=dailyValues(events(allEvents,"distance",today.minusDays(14),today),Event::value);
                    var previous=dailyValues(events(allEvents,"distance",today.minusDays(28),today.minusDays(14)),Event::value);
                    if(current.size()<7 || previous.size()<7 || median(previous)<=0 || median(current)>median(previous)*.8) continue;
                    target=(int)Math.floor(Math.min(median(previous),median(current)+500));if(target<=median(current)) continue;
                    category="ACTIVITY";unit="METER";need=30;title="오늘 걷기 거리 "+target+"m 채우기";
                    reason="최근 14일과 이전 14일 거리 기록을 비교했어요.";
                }
                case "WTR-001","WTR-002","WTR-003","WTR-004","WTR-005" -> {
                    if(!(waterLow||waterDrop)) continue;
                    if(code.equals("WTR-001") && !waterLow) continue;
                    String meal=code.equals("WTR-003")?"LUNCH":"DINNER";
                    if(Set.of("WTR-003","WTR-005").contains(code)) {
                        if(!waterDrop) continue;
                        var meals=events(allEvents,"meal",today.minusDays(7),today).stream().filter(e->e.tag().equals(meal)).toList();
                        if(meals.stream().map(e->date(e.start())).distinct().count()<4) continue;
                        int goal=round15(median(dailyValues(meals,e->minute(e.start())))); parameters.put("minute",goal);parameters.put("meal",meal);
                    } else if(code.equals("WTR-002")) parameters.put("minute",600);
                    else if(code.equals("WTR-004")) parameters.put("minute",900);
                    if(parameters.containsKey("minute") && !at(today,(int)parameters.get("minute")).isAfter(now)) continue;
                    target=code.equals("WTR-001")?(int)Math.floor(Math.min(options.waterGoalMl(),median(water)+250)):250;
                    category="HYDRATION";unit="ML";need=waterDrop?30:20;
                    title=switch(code) {case "WTR-001"->"오늘 물 "+target+"mL 마시기";case "WTR-002"->"오전 10시까지 물 250mL 마시기";
                        case "WTR-004"->"오후 3시까지 물 250mL 마시기";case "WTR-003"->"점심 식사 전 물 250mL 마시기";default->"저녁 식사 전 물 250mL 마시기";};
                    reason="최근 물 기록"+(waterLow?"과 설정한 개인 목표":"의 감소 흐름")+"를 반영했어요.";
                }
                case "REC-BIO-001","REC-BIO-002","REC-BIO-003" -> {
                    if(!(options.bloodPressureTracking() || input.managed().contains("BP"))) continue;
                    if(options.wakeMinute()==null || options.bedMinute()==null) continue;
                    parameters.put("wakeMinute",options.wakeMinute());parameters.put("bedMinute",options.bedMinute());category="BIO";
                    boolean weekly=code.equals("REC-BIO-003");
                    if(!weekly && options.sevenDayBloodPressurePlan()) continue;
                    if(weekly && (!options.sevenDayBloodPressurePlan() || bpDays(allEvents,today.minusDays(7),today,options.wakeMinute(),options.bedMinute())>=5)) continue;
                    int goal=code.equals("REC-BIO-001")?options.wakeMinute():options.bedMinute();
                    if(!weekly && !at(today,goal+(code.equals("REC-BIO-001")?60:0)).isAfter(now)) continue;
                    if(!weekly && bpPair(allEvents,today,code.equals("REC-BIO-001")?options.wakeMinute():options.bedMinute()-60)) continue;
                    target=weekly?7:1;period=weekly?"SEVEN_DAYS":"DAILY";
                    title=weekly?"7일 동안 아침·저녁 혈압 기록하기":code.equals("REC-BIO-001")?"오늘 아침 혈압 2회 기록하기":"오늘 잠들기 전 혈압 2회 기록하기";
                    reason="선택한 혈압 관리계획에 맞춰 1~2분 간격으로 기록해요. 수치가 아닌 기록 여부만 확인해요.";
                }
                case "REC-BIO-004" -> {
                    if(!options.weightTracking() || options.weightWeekday()==null || options.weightMinute()==null) continue;
                    if(!MissionRecommendationEngine.values(input,30,0,"weight").isEmpty()) continue;
                    LocalDate targetDate=monday.plusDays(options.weightWeekday()-1);
                    if(!at(targetDate,options.weightMinute()+30).isAfter(now)) continue;
                    parameters.put("targetDate",targetDate.toString());parameters.put("minute",options.weightMinute());
                    target=1;category="BIO";period="SCHEDULED";title=targetDate+" "+time(options.weightMinute())+"에 체중 기록하기";
                    reason="설정한 체중 관리 일정에 맞춰 기록 한 건을 남겨요.";
                }
                case "REC-SLP-001" -> {
                    if(lengths.size()>=5 || allEvents.stream().anyMatch(e->e.type().equals("sleep")&&date(e.end()).equals(today))) continue;
                    target=1;category="SLEEP";title="어젯밤 취침·기상 시각 기록하기";reason="수면 기록이 충분하지 않아 어젯밤 기록을 직접 입력하도록 제안해요.";
                }
                case "REC-CHK-001","REC-CHK-002" -> {
                    boolean latest=code.equals("REC-CHK-001");
                    if(latest ? evidence.checkupCount()!=0 || !options.hasCurrentCheckupResult() || options.checkupYear()==null
                            : evidence.checkupCount()!=1 || !options.hasOlderCheckupResult() || evidence.latestCheckupDate()==null) continue;
                    if(latest) parameters.put("year",options.checkupYear()); else parameters.put("beforeDate",evidence.latestCheckupDate().toString());
                    target=1;category="CHECKUP";title=latest?options.checkupYear()+"년 건강검진 결과 등록하기":"이전 건강검진 결과 1건 등록하기";
                    reason="이미 보유한다고 확인한 결과를 등록해요. 새 검진을 받으라는 미션이 아니에요.";
                }
                default -> { continue; }
            }
            if(target<=0) continue;
            if(period.equals("WEEKLY") && 8-today.getDayOfWeek().getValue()<target && unit.equals("COUNT")) continue;
            if(category.equals("SLEEP") && !recording) reliability=beds.size()>=6?20:15;
            boolean match=scope.equals(category) || scope.equals("NUTRITION")&&category.equals("HYDRATION");
            if(!recording && scope.equals("CHECKUP") && category.equals("ACTIVITY")) {
                match=input.managed().contains("GLUCOSE")&&Set.of("ACT-001","ACT-002").contains(code)
                        || input.managed().contains("BP")&&lowActivity&&Set.of("ACT-001","ACT-002").contains(code)
                        || input.managed().contains("LIPID")&&Set.of("ACT-007","ACT-008","ACT-009").contains(code)
                        || input.managed().contains("LIVER")&&input.managed().contains("WEIGHT")&&lowActivity&&Set.of("ACT-007","ACT-008","ACT-009").contains(code);
                if(match) {need=40;reason="최신 검진의 관리 상태와 생활 기록을 함께 확인했어요. "+reason;}
            }
            if(!recording && scope.equals("BIO")) {
                match=input.managed().contains("BP")&&lowActivity&&Set.of("ACT-001","ACT-002").contains(code)
                        ;
            }
            if(!match) continue;
            if(recording) parameters.put("recordAction",category.equals("CHECKUP")?"checkup":code.startsWith("REC-BIO-00")&&!code.endsWith("4")?"blood_pressure":category.equals("BIO")?"body_composition":"sleep");
            result.add(new MissionSuggestion(code,scope,title,category,reason,unit,target,need+reliability+20+10+5,
                    definition.ruleId(),definition.catalogVersion(),definition.ruleVersion(),definition.completion(),definition.manualAllowed(),
                    recording?"RECORDING":"HABIT",period,Map.copyOf(parameters)));
        }
        // 작성자: 김진우 — 개선 후보를 우선하고 기록·유지 후보로 빈 영역을 보완한다.
        for (var candidate : MissionContinuityRecommendations.candidates(input,evidence,scope,definitions)) {
            if (Set.of("REC-SLP-001", "WTR-007").contains(candidate.code())) result.removeIf(s -> s.code().equals(candidate.code()));
            if (result.stream().noneMatch(s -> s.code().equals(candidate.code()))) result.add(candidate);
        }
        var eligible=result.stream().filter(s->MissionFullProgress.endsAt(s,today).isAfter(now))
                .filter(s->!Set.of("SLP-001","SLP-008").contains(s.code())||at(today.plusDays(((Number)s.parameters().get("minute")).intValue()<720?1:0),((Number)s.parameters().get("minute")).intValue()+30).isAfter(now))
                .map(s->withExposureScore(s,input,evidence,today))
                .filter(s->!s.code().equals("ACT-012")||minute(now)<720)
                .filter(s->MissionRepeatPolicy.allows(s,input,evidence))
                .sorted(Comparator.comparing((MissionSuggestion s)->!s.missionType().equals("RECORDING"))
                        .thenComparing(Comparator.comparingInt(MissionSuggestion::score).reversed())
                        .thenComparing(MissionSuggestion::manualAllowed).thenComparing(MissionSuggestion::code)).toList();
        return eligible;
    }

    private static MissionSuggestion withExposureScore(MissionSuggestion s,Input input,MissionEvidence evidence,LocalDate today) {
        LocalDate last=evidence.exposures().stream().filter(e->e.code().equals(s.code())&&e.event().equals("viewed"))
                .map(MissionEvidence.Exposure::date).max(LocalDate::compareTo).orElse(LocalDate.MIN);
        LocalDate accepted=input.history().stream().filter(h->h.code().equals(s.code())).map(MissionRecommendationEngine.History::date).max(LocalDate::compareTo).orElse(LocalDate.MIN);
        int before=accepted.isBefore(today.minusDays(29))?10:accepted.isBefore(today.minusDays(14))?5:0;
        LocalDate effective=last.isAfter(accepted)?last:accepted;
        int after=effective.isBefore(today.minusDays(29))?10:effective.isBefore(today.minusDays(14))?5:0;
        var history=input.history().stream().filter(h->h.category().equals(s.category())&&!h.active()).sorted(Comparator.comparing(MissionRecommendationEngine.History::date).reversed()).limit(3).toList();
        long successes=history.stream().filter(MissionRecommendationEngine.History::completed).count();
        int successScore=successes>=2?10:history.size()==3&&successes==0?0:5;
        int score=s.score()-(DAILY.contains(s.code())?before:10)+after+(DAILY.contains(s.code())?0:successScore-5);
        return new MissionSuggestion(s.code(),s.scope(),s.title(),s.category(),s.description(),s.unit(),s.targetValue(),score,
                s.ruleId(),s.catalogVersion(),s.ruleVersion(),s.completion(),s.manualAllowed(),s.missionType(),s.period(),s.parameters());
    }

    public static List<Event> events(List<Event> events,String type,LocalDate from,LocalDate to) {
        return events.stream().filter(e->e.type().equals(type)&&!date(e.end()).isBefore(from)&&date(e.end()).isBefore(to)).toList();
    }
    public static List<Double> dailyValues(List<Event> events,ToDoubleFunction<Event> value) {
        Map<LocalDate,List<Double>> days=new HashMap<>();
        events.forEach(e->days.computeIfAbsent(date(e.end()),k->new ArrayList<>()).add(value.applyAsDouble(e)));
        return days.values().stream().map(MissionFullRecommendationEngine::median).toList();
    }
    public static int mealWalkDays(List<Event> events,List<Event> meals) {
        return (int)meals.stream().filter(m->walkMinutes(events,m.start(),m.start().plusSeconds(7200))>=15).map(e->date(e.start())).distinct().count();
    }
    public static double walkMinutes(List<Event> events,Instant start,Instant end) {
        return events.stream().filter(MissionFullRecommendationEngine::isWalk).mapToDouble(e->overlap(e,start,end)).sum();
    }
    public static double overlap(Event e,Instant start,Instant end) {
        Instant from=e.start().isAfter(start)?e.start():start,to=e.end().isBefore(end)?e.end():end;
        return from.isBefore(to)&&e.start().isBefore(e.end())?e.value()*Duration.between(from,to).toMillis()/Duration.between(e.start(),e.end()).toMillis():0;
    }
    public static boolean isWalk(Event e) {return e.type().equals("exercise")&&e.tag().split(":")[0].matches("(?i)walking|walk|걷기");}
    public static boolean isStrength(Event e) {return e.type().equals("exercise")&&e.tag().split(":")[0].matches("(?i)strength_training|weight_training|weight_lifting|weight_machine|push_ups|pull_ups|bench_press|squats|lunges|leg_presses|leg_curls|lat_pulldowns|deadlifts|shoulder_presses|plank|arm_curls|근력 운동|근력운동|웨이트 트레이닝");}
    public static boolean bpPair(List<Event> events,LocalDate day,int startMinute) {
        var times=events.stream().filter(e->e.type().equals("bp")&&!e.start().isBefore(at(day,startMinute))&&e.start().isBefore(at(day,startMinute+60))).map(Event::start).distinct().sorted().toList();
        for(int i=0;i<times.size();i++) for(int j=i+1;j<times.size();j++) {long seconds=Duration.between(times.get(i),times.get(j)).toSeconds();if(seconds>=60&&seconds<=120)return true;}
        return false;
    }
    public static int bpDays(List<Event> events,LocalDate from,LocalDate until,int wake,int bed) {
        int result=0; for(LocalDate day=from;day.isBefore(until);day=day.plusDays(1)) if(bpPair(events,day,wake)&&bpPair(events,day,bed-60))result++;return result;
    }
    private static boolean restingChange(List<Event> events,LocalDate today) {
        var previous=dailyValues(events(events,"resting_hr",today.minusDays(14),today.minusDays(7)),Event::value);
        var recent=dailyValues(events(events,"resting_hr",today.minusDays(7),today),Event::value);
        if(previous.size()<5||recent.size()<5)return false;
        double baseline=median(previous),spread=mad(previous);
        return recent.stream().filter(v->v>baseline+spread).count()>=3 || recent.stream().filter(v->v<baseline-spread).count()>=3;
    }
    public static LocalDate date(Instant time) {return time.atZone(HealthPeriod.ZONE).toLocalDate();}
    public static int minute(Instant time) {return time.atZone(HealthPeriod.ZONE).getHour()*60+time.atZone(HealthPeriod.ZONE).getMinute();}
    public static int bedMinute(Instant time) {int m=minute(time);return m<720?m+1440:m;}
    public static Instant at(LocalDate day,int minute) {return day.atStartOfDay(HealthPeriod.ZONE).plusMinutes(minute).toInstant();}
    private static int round15(double value) {return (int)Math.round(value/15)*15;}
    private static String time(int value) {return String.format("%02d:%02d",Math.floorMod(value,1440)/60,Math.floorMod(value,60));}
    private static double median(List<Double> values) {return MissionRecommendationEngine.median(values);}
    private static double mad(List<Double> values) {double mid=median(values);return median(values.stream().map(v->Math.abs(v-mid)).toList());}
}
