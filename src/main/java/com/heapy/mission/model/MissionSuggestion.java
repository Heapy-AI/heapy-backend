package com.heapy.mission.model;

import java.util.Map;

/** 추천 시 계산한 목표와 근거. 수락 시 서버에서 다시 검증한다. @author 김진우 */
public record MissionSuggestion(String code, String scope, String title, String category,
        String description, String unit, int targetValue, int score, String ruleId,
        int catalogVersion, int ruleVersion, String completion, boolean manualAllowed,
        String missionType, String period, Map<String,Object> parameters) {
    public MissionSuggestion(String code, String scope, String title, String category, String description,
            String unit,int targetValue,int score,String ruleId,int catalogVersion,int ruleVersion,
            String completion,boolean manualAllowed) {
        this(code,scope,title,category,description,unit,targetValue,score,ruleId,catalogVersion,ruleVersion,
                completion,manualAllowed,"HABIT","DAILY",Map.of());
    }
}
