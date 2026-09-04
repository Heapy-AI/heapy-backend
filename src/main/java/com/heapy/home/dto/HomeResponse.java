package com.heapy.home.dto;

import java.time.LocalDate;
import java.util.List;

public record HomeResponse(
        LocalDate date,
        List<Object> alerts,
        List<HomeModuleResponse> modules
) {
}
