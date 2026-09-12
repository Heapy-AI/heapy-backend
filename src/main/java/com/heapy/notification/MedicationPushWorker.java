package com.heapy.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 앱 실행 여부와 무관하게 복약 알림을 발송한다. @author 김진우 */
@Component
public class MedicationPushWorker {
    private static final Logger log = LoggerFactory.getLogger(MedicationPushWorker.class);
    private final MedicationPushService service;
    public MedicationPushWorker(MedicationPushService service) { this.service = service; }
    @Scheduled(fixedDelay=30000, initialDelay=45000)
    public void run() {
        try { service.tick(); }
        catch (Exception exception) { log.warn("복약 알림 처리 실패: exceptionType={}", exception.getClass().getSimpleName()); }
    }
}
