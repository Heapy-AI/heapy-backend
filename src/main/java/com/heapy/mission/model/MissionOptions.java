package com.heapy.mission.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** 미션 자격·개인 목표에 필요한 명시적 사용자 설정. @author 김진우 */
public record MissionOptions(
        @Min(250) @Max(10000) Integer waterGoalMl,
        boolean bloodPressureTracking, boolean sevenDayBloodPressurePlan,
        @Min(0) @Max(1439) Integer wakeMinute, @Min(0) @Max(1439) Integer bedMinute,
        boolean weightTracking, @Min(1) @Max(7) Integer weightWeekday,
        @Min(0) @Max(1439) Integer weightMinute,
        boolean noSyncableSleep, boolean hasCurrentCheckupResult,
        @Min(1900) @Max(2100) Integer checkupYear, boolean hasOlderCheckupResult) {
    public static MissionOptions empty() {
        return new MissionOptions(null,false,false,null,null,false,null,null,false,false,null,false);
    }
}
