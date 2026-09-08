package com.heapy.checkup;

import com.heapy.checkup.OcrModels.Job;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "heapy.ocr", name = "enabled", havingValue = "true")
public class OcrWorker {
    private static final Logger log = LoggerFactory.getLogger(OcrWorker.class);
    private final OcrRepository repository;
    private final OcrGateway gateway;
    private final OcrService service;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean();

    public OcrWorker(OcrRepository repository, OcrGateway gateway, OcrService service) {
        this.repository = repository;
        this.gateway = gateway;
        this.service = service;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void scan() {
        if (!running.compareAndSet(false, true)) return;
        executor.submit(() -> {
            try {
                repository.expire();
                for (Job job : repository.pending()) {
                    try {
                        var snapshot = gateway.read(job);
                        if ("pending".equals(snapshot.status())) snapshot = gateway.execute(job);
                        repository.progress(job, snapshot, OcrService.publicError(snapshot.errorCode()));
                    } catch (Exception exception) {
                        // 작성자: 김진우 — 미완료 작업은 DB에서 다시 찾아 응답 유실·프로세스 재시작을 복구한다.
                        log.warn("OCR 실행 재시도 필요: jobId={}, exceptionType={}", job.id(), exception.getClass().getSimpleName());
                    }
                }
            } catch (Exception exception) {
                log.error("OCR 작업 조회 실패: exceptionType={}", exception.getClass().getSimpleName());
            } finally { running.set(false); }
        });
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 10000)
    public void cleanup() {
        try {
            for (Job job : repository.cleanup()) service.cleanup(job);
        } catch (Exception exception) {
            log.error("OCR 정리 대상 조회 실패: exceptionType={}", exception.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void close() { executor.shutdownNow(); }
}
