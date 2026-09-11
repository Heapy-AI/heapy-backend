package com.heapy.health.service;

import com.heapy.health.model.HealthPeriod;
import com.heapy.health.repository.LifestyleScoreStore;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 자정 계산과 재시작 후 누락 복구를 수행한다. 저장된 날짜는 재계산하지 않는다. @author 김진우 */
@Component
public class LifestyleScoreScheduler {
    private static final Logger log = LoggerFactory.getLogger(LifestyleScoreScheduler.class);
    private final LifestyleScoreStore store;
    private final LifestyleScoreService service;
    private final Clock clock;
    public LifestyleScoreScheduler(LifestyleScoreStore store, LifestyleScoreService service, Clock clock) {
        this.store = store; this.service = service; this.clock = clock;
    }
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    @Scheduled(fixedDelay = 300000, initialDelay = 30000)
    public synchronized void process() {
        LocalDate today = LocalDate.now(clock.withZone(HealthPeriod.ZONE));
        HealthPeriod period = HealthPeriod.of("7d", today.minusDays(1), "day");
        try {
            store.pruneAll(period.from());
            UUID cursor = new UUID(0, 0);
            while (today.equals(LocalDate.now(clock.withZone(HealthPeriod.ZONE)))) {
                var users = store.pendingUsers(period, cursor);
                if (users.isEmpty()) return;
                cursor = users.getLast();
                for (UUID user : users) {
                    if (!today.equals(LocalDate.now(clock.withZone(HealthPeriod.ZONE)))) return;
                    try { service.find(user, "7d", null); }
                    catch (RuntimeException error) { log.warn("생활습관 점수 누락 날짜를 다음 주기에 재시도합니다."); }
                }
            }
        } catch (RuntimeException error) {
            log.warn("생활습관 점수 배치를 완료하지 못했습니다.");
        }
    }
}
