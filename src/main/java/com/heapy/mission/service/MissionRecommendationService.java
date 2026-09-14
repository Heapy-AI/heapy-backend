package com.heapy.mission.service;

import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import com.heapy.health.model.HealthPeriod;
import com.heapy.mission.dto.MissionDetailResponse;
import com.heapy.mission.model.MissionSuggestion;
import com.heapy.mission.repository.MissionRecommendationRepository;
import com.heapy.mission.repository.MissionRepository;
import com.heapy.mission.repository.MissionEvidenceRepository;
import com.heapy.mission.model.MissionOptions;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 클라이언트가 보낸 목표값을 신뢰하지 않고 수락 직전 재계산한다. @author 김진우 */
@Service
public class MissionRecommendationService {
    private final MissionRecommendationRepository recommendations;
    private final MissionRepository missions;
    private final MissionService service;
    private final Clock clock;
    private final MissionEvidenceRepository evidence;
    @Autowired
    public MissionRecommendationService(MissionRecommendationRepository recommendations, MissionRepository missions,
            MissionService service, Clock clock,MissionEvidenceRepository evidence) {
        this.recommendations=recommendations; this.missions=missions; this.service=service; this.clock=clock; this.evidence=evidence;
    }
    public record Suggestions(String scope, String state, List<MissionSuggestion> suggestions) { }
    @Transactional(readOnly=true)
    public Suggestions suggestions(UUID user, String scope) {return suggestions(user,scope,false);}
    private Suggestions suggestions(UUID user,String scope,boolean all) {
        if(!Set.of("SUMMARY","ACTIVITY","SLEEP","NUTRITION","BIO","CHECKUP").contains(scope))
            throw new HeapyException(ErrorCode.INVALID_INPUT);
        if(scope.equals("SUMMARY")) return new Suggestions(scope,"NO_CANDIDATE",List.of());
        var now=clock.instant();
        var input=recommendations.input(user,LocalDate.now(clock.withZone(HealthPeriod.ZONE)),now);
        var records=evidence.load(user,input.today(),now);
        var definitions=recommendations.definitions();
        var list=MissionFullRecommendationEngine.recommend(input,records,scope,definitions,now).stream()
                .limit(all?100:3).toList();
        return new Suggestions(scope,!list.isEmpty()?"RECOMMENDED":!input.eligible() || scope.equals("NUTRITION") && !input.waterSafe() ? "SAFETY_NOTICE"
                : list.isEmpty() ? "NO_CANDIDATE" : "RECOMMENDED",list);
    }
    @Transactional(readOnly=true)
    public MissionOptions options(UUID user) {return evidence.options(user);}

    @Transactional
    public MissionOptions saveOptions(UUID user,MissionOptions options) {
        missions.lockUser(user);
        return evidence.saveOptions(user,options,LocalDate.now(clock.withZone(HealthPeriod.ZONE)));
    }

    @Transactional
    public void exposure(UUID user,String scope,String code,String event,UUID key) {
        missions.lockUser(user);
        if(!Set.of("viewed","rejected").contains(event)) throw new HeapyException(ErrorCode.INVALID_INPUT);
        if(evidence.existingFeedback(user,key,code,event)) return;
        if(suggestions(user,scope,true).suggestions().stream().noneMatch(s->s.code().equals(code)))
            throw new HeapyException(ErrorCode.MISSION_CONFLICT);
        evidence.feedback(user,code,event,key,clock.instant());
    }

    @Transactional
    public MissionDetailResponse accept(UUID user, String scope, String code, UUID key) {
        missions.lockUser(user);
        UUID existing;
        try { existing=recommendations.existing(user,key,code); }
        catch(IllegalArgumentException exception) { throw new HeapyException(ErrorCode.MISSION_CONFLICT); }
        if(existing!=null) return service.detail(user,existing);
        LocalDate today=LocalDate.now(clock.withZone(HealthPeriod.ZONE));
        if(recommendations.todayCount(user,today)>=10) throw new HeapyException(ErrorCode.MISSION_CONFLICT);
        var suggestion=suggestions(user,scope,true).suggestions().stream().filter(s -> s.code().equals(code))
                .findFirst().orElseThrow(() -> new HeapyException(ErrorCode.MISSION_CONFLICT));
        UUID id=recommendations.accept(user,key,suggestion,today,clock.instant());
        return service.detail(user,id);
    }
}
