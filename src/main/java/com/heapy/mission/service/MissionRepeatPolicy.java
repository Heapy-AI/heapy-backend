package com.heapy.mission.service;

import com.heapy.mission.model.MissionEvidence;
import com.heapy.mission.model.MissionSuggestion;
import com.heapy.mission.service.MissionRecommendationEngine.Input;

/** 한국 날짜 기준 수행 이력 제한과 기록 확보 우선 정책. @author 김진우 */
public final class MissionRepeatPolicy {
    private MissionRepeatPolicy() { }

    public static boolean allows(MissionSuggestion mission, Input input, MissionEvidence evidence) {
        var history=input.history().stream().filter(h->h.code().equals(mission.code())).toList();
        if(history.stream().anyMatch(MissionRecommendationEngine.History::active)) return false;
        if(mission.missionType().equals("RECORDING")) return needsRecords(mission,input,evidence);
        return history.stream().noneMatch(h->h.completed()&&h.date().isAfter(input.today().minusDays(7))
                ||h.abandoned()&&h.date().isAfter(input.today().minusDays(1)))
                && evidence.exposures().stream().noneMatch(e->e.code().equals(mission.code())
                &&e.event().equals("rejected")&&e.date().isAfter(input.today().minusDays(1)));
    }

    private static boolean needsRecords(MissionSuggestion mission,Input input,MissionEvidence evidence) {
        String code=mission.code();
        if(code.equals("REC-SLP-001")) return MissionRecommendationEngine.values(input,7,0,"sleep").size()<5;
        if(code.equals("WTR-007")) return MissionRecommendationEngine.values(input,14,0,"water").size()<7;
        if(code.equals("REC-BIO-004")) return MissionRecommendationEngine.values(input,28,0,"weight").size()<2;
        if(code.startsWith("REC-BIO-")) {
            var options=evidence.options();
            if(options.wakeMinute()!=null&&options.bedMinute()!=null)
                return MissionFullRecommendationEngine.bpDays(evidence.events(),input.today().minusDays(7),input.today(),options.wakeMinute(),options.bedMinute())<5;
            return MissionFullRecommendationEngine.events(evidence.events(),"bp",input.today().minusDays(7),input.today())
                    .stream().map(e->MissionFullRecommendationEngine.date(e.start())).distinct().count()<5;
        }
        if(code.equals("REC-CHK-001")) return evidence.checkupCount()==0;
        if(code.equals("REC-CHK-002")) return evidence.checkupCount()<2;
        return false;
    }
}
