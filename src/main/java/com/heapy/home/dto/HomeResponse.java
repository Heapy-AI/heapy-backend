package com.heapy.home.dto;

import java.time.LocalDate;
import java.util.List;
import com.heapy.home.repository.HomeSummaryRepository;
import com.heapy.home.service.HomeCards;

public record HomeResponse(
        LocalDate date,
        List<HomeSummaryRepository.Alert> alerts,
        List<HomeModuleResponse> modules,
        String name,
        HomeCards.Data cards,
        HomeSummaryRepository.Checkup latestCheckup,
        List<HomeSummaryRepository.Mission> missions
) {
}
