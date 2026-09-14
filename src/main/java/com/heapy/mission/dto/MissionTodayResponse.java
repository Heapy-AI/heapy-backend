package com.heapy.mission.dto;

import java.time.LocalDate;
import java.util.List;

public record MissionTodayResponse(
        LocalDate date,
        int completedCount,
        int totalCount,
        int completionRate,
        List<MissionItemResponse> missions
) {
}
