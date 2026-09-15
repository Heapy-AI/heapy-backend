package com.heapy.home.service;

import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import com.heapy.home.domain.UserHomeModule;
import com.heapy.home.dto.HomeModuleResponse;
import com.heapy.home.dto.HomeResponse;
import com.heapy.home.repository.UserHomeModuleRepository;
import com.heapy.home.repository.HomeSummaryRepository;
import com.heapy.health.model.HealthBriefing;
import com.heapy.health.service.HealthBriefingService;
import com.heapy.user.domain.User;
import com.heapy.mission.service.MissionService;
import com.heapy.user.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
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
    private final HomeCards cards;
    private final MissionService missionService;
    private final HealthBriefingService briefings;

    public HomeService(
            UserRepository userRepository,
            UserHomeModuleRepository homeModuleRepository,
            HomeSummaryRepository summaryRepository,
            HomeCards cards,
            MissionService missionService,
            HealthBriefingService briefings
    ) {
        this.userRepository = userRepository;
        this.homeModuleRepository = homeModuleRepository;
        this.summaryRepository = summaryRepository;
        this.cards = cards;
        this.missionService=missionService;
        this.briefings=briefings;
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
        HomeCards.Data cardData = cards.find(userId);
        LocalDate today = cardData == null ? LocalDate.now(SERVICE_ZONE) : cardData.date();
        var missions = missionService.today(userId).missions().stream().map(m -> new HomeSummaryRepository.Mission(
                m.missionId(),m.title(),m.description(),m.status().name().toLowerCase(Locale.ROOT))).toList();
        boolean hasMetrics = (summary != null && summary.hasData())
                || (cardData != null && !cardData.metrics().isEmpty());
        // 작성자: 고수연 — 홈 브리핑은 자정 배치가 미리 만들어 둔 것을 읽기만 한다.
        // 홈을 여는 순간 모델을 부르면 첫 화면이 그만큼 늦어진다.
        HealthBriefing briefing = briefings.today(userId);
        List<HomeModuleResponse> moduleResponses = modules.stream()
                .map(module -> module(module, briefing, missions, summary, hasMetrics))
                .toList();
        return new HomeResponse(today, summaryRepository.alerts(userId), moduleResponses, user.getName(),
                cardData, summary == null ? null : summary.latestCheckup(), missions);
    }

    /** 모듈 한 칸. 내용이 있으면 ready, 없으면 empty 와 함께 무엇을 하면 되는지 알린다. */
    private HomeModuleResponse module(UserHomeModule module, HealthBriefing briefing,
                                      List<HomeSummaryRepository.Mission> missions,
                                      HomeSummaryRepository.Summary summary, boolean hasMetrics) {
        String code = module.getModuleCode();
        boolean ready = switch (code) {
            case "daily_briefing" -> briefing != null;
            case "missions" -> !missions.isEmpty();
            case "key_metrics" -> hasMetrics;
            default -> false;
        };
        Object content = switch (code) {
            case "daily_briefing" -> briefing;
            case "missions" -> missions;
            case "key_metrics" -> hasMetrics ? summary : null;
            default -> null;
        };
        return new HomeModuleResponse(code, module.isVisible(), module.getDisplayOrder(),
                ready ? "ready" : "empty", content, ready ? null : EMPTY_ACTIONS.get(code));
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
