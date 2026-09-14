package com.heapy.mission.dto;

import java.util.List;

public record MissionCalendarResponse(
        int year,
        int month,
        int completedCount,
        int totalCount,
        int completionRate,
        List<MissionCalendarDayResponse> days
) {
}
