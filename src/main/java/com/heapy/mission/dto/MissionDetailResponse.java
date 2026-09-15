package com.heapy.mission.dto;

import com.heapy.mission.model.MissionFeedback;
import com.heapy.mission.model.MissionStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.Map;

public record MissionDetailResponse(
        UUID missionId,
        String code,
        String title,
        String category,
        String description,
        String unit,
        LocalDate missionDate,
        int targetValue,
        int currentValue,
        int remainingValue,
        int progressPercent,
        MissionStatus status,
        Instant completedAt,
        MissionFeedback feedback, boolean manualAllowed, Instant endsAt,Map<String,Object> parameters
) {
    /** 모든 미션의 신규 완료 보상은 10코인이다. @author 김진우 */
    public int getRewardCoins() { return 10; }
}
