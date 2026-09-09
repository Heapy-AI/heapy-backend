package com.heapy.home.service;

import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import com.heapy.home.domain.UserHomeModule;
import com.heapy.home.dto.HomeModuleResponse;
import com.heapy.home.dto.HomeResponse;
import com.heapy.home.repository.UserHomeModuleRepository;
import com.heapy.home.repository.HomeSummaryRepository;
import com.heapy.user.domain.User;
import com.heapy.user.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HomeService {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    private static final List<String> DEFAULT_MODULES = List.of(
            "daily_briefing",
            "key_metrics",
            "medication",
            "missions"
    );
    private static final Map<String, String> EMPTY_ACTIONS = Map.of(
            "daily_briefing", "record_health_data",
            "key_metrics", "connect_samsung_health",
            "medication", "register_medication",
            "missions", "explore_missions"
    );

    private final UserRepository userRepository;
    private final UserHomeModuleRepository homeModuleRepository;
    private final HomeSummaryRepository summaryRepository;

    public HomeService(
            UserRepository userRepository,
            UserHomeModuleRepository homeModuleRepository,
            HomeSummaryRepository summaryRepository
    ) {
        this.userRepository = userRepository;
        this.homeModuleRepository = homeModuleRepository;
        this.summaryRepository = summaryRepository;
    }

    @Transactional
    public HomeResponse getHome(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new HeapyException(ErrorCode.ONBOARDING_INCOMPLETE));
        if (user.getOnboardingCompletedAt() == null) {
            throw new HeapyException(ErrorCode.ONBOARDING_INCOMPLETE);
        }

        List<UserHomeModule> modules = homeModuleRepository.findByUserIdOrderByDisplayOrder(userId);
        if (modules.isEmpty()) {
            modules = createDefaultModules(userId);
        }
        HomeSummaryRepository.Summary summary = summaryRepository.find(userId);
        boolean hasMetrics = summary != null && summary.hasData();
        List<HomeModuleResponse> moduleResponses = modules.stream()
                .map(module -> new HomeModuleResponse(
                        module.getModuleCode(),
                        module.isVisible(),
                        module.getDisplayOrder(),
                        "key_metrics".equals(module.getModuleCode()) && hasMetrics ? "ready" : "empty",
                        "key_metrics".equals(module.getModuleCode()) && hasMetrics ? summary : null,
                        "key_metrics".equals(module.getModuleCode()) && hasMetrics
                                ? null : EMPTY_ACTIONS.get(module.getModuleCode())
                ))
                .toList();
        return new HomeResponse(LocalDate.now(SERVICE_ZONE), List.of(), moduleResponses);
    }

    private List<UserHomeModule> createDefaultModules(UUID userId) {
        Instant now = Instant.now();
        List<UserHomeModule> modules = new ArrayList<>();
        for (int index = 0; index < DEFAULT_MODULES.size(); index++) {
            modules.add(new UserHomeModule(userId, DEFAULT_MODULES.get(index), index + 1, now));
        }
        return homeModuleRepository.saveAll(modules);
    }
}
