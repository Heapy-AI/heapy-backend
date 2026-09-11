package com.heapy.health.service;

import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import com.heapy.health.model.HealthPeriod;
import com.heapy.health.model.LifestyleScore.Report;
import com.heapy.health.model.LifestyleScore.Day;
import com.heapy.health.repository.LifestyleScoreRepository;
import com.heapy.health.repository.LifestyleScoreStore;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 최초 또는 누락 날짜만 계산하고 저장된 결과는 그대로 재사용한다. @author 김진우 */
@Service
public class LifestyleScoreService {
    private final LifestyleScoreRepository repository;
    private final Clock clock;
    private final LifestyleScoreStore store;
    public LifestyleScoreService(LifestyleScoreRepository repository, Clock clock, LifestyleScoreStore store) {
        this.repository = repository; this.clock = clock; this.store = store;
    }
    @Transactional(timeout = 20)
    public Report find(UUID user, String code, LocalDate baseDate) {
        if (!"7d".equals(code)) throw new HeapyException(ErrorCode.INVALID_INPUT);
        LocalDate today = LocalDate.now(clock.withZone(HealthPeriod.ZONE));
        if (baseDate != null && !baseDate.equals(today) && !baseDate.equals(today.minusDays(1))) {
            throw new HeapyException(ErrorCode.INVALID_INPUT);
        }
        LocalDate end = today.minusDays(1);
        HealthPeriod period = HealthPeriod.of(code, end, "day");
        List<Day> saved = store.find(user, period);
        if (saved.size() == 7) return report(period, saved);
        store.lock(user);
        store.prune(user, period.from());
        saved = store.find(user, period);
        if (saved.size() < 7) {
            var known = new HashSet<LocalDate>();
            saved.forEach(day -> known.add(day.date()));
            LocalDate first = period.from();
            while (known.contains(first)) first = first.plusDays(1);
            HealthPeriod missingWindow = new HealthPeriod("7d", first, end, "day");
            var input = repository.find(user, missingWindow);
            // 이미 저장된 날짜는 계산기에도 넘기지 않는다. 원천 조회는 누락 구간에 대해 한 번만 한다.
            for (LocalDate day = first; !day.isAfter(end); day = day.plusDays(1)) {
                if (known.contains(day)) continue;
                Day calculated = LifestyleScoreCalculator.calculate(input, new HealthPeriod("7d", day, day, "day")).latest();
                if (!today.equals(LocalDate.now(clock.withZone(HealthPeriod.ZONE)))) {
                    throw new IllegalStateException("점수 저장 중 날짜가 변경되어 다음 요청에서 다시 처리합니다.");
                }
                store.insert(user, calculated, LifestyleScoreCalculator.VERSION);
            }
            saved = store.find(user, period);
        }
        return report(period, saved);
    }
    private Report report(HealthPeriod period, List<Day> saved) {
        return new Report(LifestyleScoreCalculator.VERSION, HealthPeriod.ZONE.getId(), period, saved.getLast(), saved);
    }
}
