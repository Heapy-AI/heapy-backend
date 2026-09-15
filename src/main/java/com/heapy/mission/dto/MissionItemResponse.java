package com.heapy.mission.dto;

import com.heapy.mission.model.MissionStatus;
import java.util.UUID;

public record MissionItemResponse(
        UUID missionId,
        String code,
        String title,
        String category,
        String description,
        String unit,
        int targetValue,
        int currentValue,
        int progressPercent,
        MissionStatus status,
        int displayOrder, String scope
) {
    /** 모든 미션의 신규 완료 보상은 10코인이다. @author 김진우 */
    public int getRewardCoins() { return 10; }
}
