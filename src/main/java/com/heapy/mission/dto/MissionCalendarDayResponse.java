package com.heapy.mission.dto;

import java.time.LocalDate;

public record MissionCalendarDayResponse(
        LocalDate date,
        int completedCount,
        int totalCount,
        Integer completionRate
) {
}
