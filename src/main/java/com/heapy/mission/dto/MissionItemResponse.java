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
        int displayOrder
) {
}
