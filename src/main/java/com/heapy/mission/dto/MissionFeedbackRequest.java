package com.heapy.mission.dto;

import com.heapy.mission.model.MissionFeedback;
import jakarta.validation.constraints.NotNull;

public record MissionFeedbackRequest(
        @NotNull MissionFeedback difficulty
) {
}
