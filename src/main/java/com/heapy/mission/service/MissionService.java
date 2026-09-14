package com.heapy.mission.service;

import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import com.heapy.health.model.HealthPeriod;
import com.heapy.mission.dto.MissionCalendarDayResponse;
import com.heapy.mission.dto.MissionCalendarResponse;
import com.heapy.mission.dto.MissionDetailResponse;
import com.heapy.mission.dto.MissionItemResponse;
import com.heapy.mission.dto.MissionTodayResponse;
import com.heapy.mission.model.MissionFeedback;
import com.heapy.mission.model.MissionStatus;
import com.heapy.mission.repository.MissionRepository;
import com.heapy.mission.repository.MissionHealthRepository;
import com.heapy.mission.repository.MissionRepository.CalendarRow;
import com.heapy.mission.repository.MissionRepository.MissionRow;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MissionService {

    private final MissionRepository repository;
    private final Clock clock;
    private final MissionHealthRepository health;

    public MissionService(MissionRepository repository, Clock clock, MissionHealthRepository health) {
        this.repository = repository;
        this.clock = clock;
        this.health = health;
    }

    @Transactional
    public MissionTodayResponse today(UUID userId) {
        LocalDate today = LocalDate.now(clock.withZone(HealthPeriod.ZONE));
        repository.ensureDailyMissions(userId, today);
        refresh(userId,today);
        List<MissionRow> rows = repository.findByDate(userId, today);
        List<MissionItemResponse> missions = rows.stream().map(this::toItem).toList();
        int completed = (int) rows.stream().filter(row -> row.completedAt() != null).count();
        return new MissionTodayResponse(today, completed, rows.size(), rate(completed, rows.size()), missions);
    }

    @Transactional
    public MissionTodayResponse byDate(UUID userId, LocalDate date) {
        LocalDate today=LocalDate.now(clock.withZone(HealthPeriod.ZONE));
        if(date.isAfter(today)) throw new HeapyException(ErrorCode.INVALID_INPUT);
        if(date.equals(today)) return today(userId);
        var rows=repository.findByDate(userId,date);
        int completed=(int)rows.stream().filter(r->r.completedAt()!=null).count();
        return new MissionTodayResponse(date,completed,rows.size(),rate(completed,rows.size()),rows.stream().map(this::toItem).toList());
    }

    @Transactional
    public MissionDetailResponse detail(UUID userId, UUID missionId) {
        repository.lockUser(userId);
        var row=find(userId,missionId);
        refresh(userId,row.missionDate());
        return toDetail(find(userId, missionId));
    }

    /** 외부에서 전달한 진행값 대신 실제 건강 기록으로 갱신한다. @author 김진우 */
    @Transactional
    public MissionDetailResponse updateProgress(UUID userId, UUID missionId) {
        return detail(userId,missionId);
    }

    @Transactional
    public MissionDetailResponse complete(UUID userId, UUID missionId) {
        repository.lockUser(userId);
        MissionRow mission = find(userId, missionId);
        if (mission.completedAt() != null) return toDetail(mission);
        if(!mission.missionDate().equals(LocalDate.now(clock.withZone(HealthPeriod.ZONE))))
            throw new HeapyException(ErrorCode.MISSION_EXPIRED);
        refresh(userId,mission.missionDate());
        mission=find(userId,missionId);
        if (mission.currentValue() < mission.targetValue()) {
            throw new HeapyException(ErrorCode.MISSION_NOT_COMPLETABLE);
        }
        if (repository.complete(userId, missionId) != 1) {
            throw new HeapyException(ErrorCode.MISSION_CONFLICT);
        }
        return toDetail(find(userId, missionId));
    }

    private void refresh(UUID userId, LocalDate date) {
        if(!date.equals(LocalDate.now(clock.withZone(HealthPeriod.ZONE)))) return;
        var rows=repository.findByDate(userId,date);
        if(rows.stream().noneMatch(r->r.completedAt()==null)) return;
        var values=health.progress(userId,date,clock.instant());
        for(var row:rows) {
            if(row.completedAt()!=null) continue;
            int value=Math.min(values.getOrDefault(row.code(),0),row.targetValue());
            if(row.currentValue()!=value) repository.updateProgress(userId,row.missionId(),value);
        }
    }

    @Transactional
    public MissionDetailResponse feedback(UUID userId, UUID missionId, MissionFeedback feedback) {
        repository.lockUser(userId);
        MissionRow mission = find(userId, missionId);
        if (mission.completedAt() == null) {
            throw new HeapyException(ErrorCode.MISSION_NOT_COMPLETED);
        }
        if (repository.saveFeedback(userId, missionId, feedback) != 1) {
            throw new HeapyException(ErrorCode.MISSION_CONFLICT);
        }
        return toDetail(find(userId, missionId));
    }

    @Transactional
    public MissionCalendarResponse calendar(UUID userId, int year, int month) {
        if (year < 2000 || year > 2100 || month < 1 || month > 12) {
            throw new HeapyException(ErrorCode.INVALID_INPUT);
        }
        YearMonth requested = YearMonth.of(year, month);
        YearMonth current = YearMonth.from(LocalDate.now(clock.withZone(HealthPeriod.ZONE)));
        if (requested.isAfter(current)) {
            throw new HeapyException(ErrorCode.INVALID_INPUT);
        }

        LocalDate today = LocalDate.now(clock.withZone(HealthPeriod.ZONE));
        if (requested.equals(current)) {
            today(userId);
        }

        LocalDate from = requested.atDay(1);
        LocalDate to = requested.atEndOfMonth();
        List<CalendarRow> saved = repository.findCalendar(userId, from, to);
        Map<LocalDate, CalendarRow> byDate = new HashMap<>();
        saved.forEach(row -> byDate.put(row.date(), row));

        List<MissionCalendarDayResponse> days = new ArrayList<>();
        int monthCompleted = 0;
        int monthTotal = 0;
        for (int day = 1; day <= requested.lengthOfMonth(); day++) {
            LocalDate date = requested.atDay(day);
            CalendarRow row = byDate.get(date);
            if (row == null) {
                days.add(new MissionCalendarDayResponse(date, 0, 0, null));
                continue;
            }
            monthCompleted += row.completedCount();
            monthTotal += row.totalCount();
            days.add(new MissionCalendarDayResponse(
                    date,
                    row.completedCount(),
                    row.totalCount(),
                    rate(row.completedCount(), row.totalCount())
            ));
        }
        return new MissionCalendarResponse(year, month, monthCompleted, monthTotal,
                rate(monthCompleted, monthTotal), List.copyOf(days));
    }

    private MissionRow find(UUID userId, UUID missionId) {
        return repository.findById(userId, missionId)
                .orElseThrow(() -> new HeapyException(ErrorCode.MISSION_NOT_FOUND));
    }

    private MissionItemResponse toItem(MissionRow row) {
        return new MissionItemResponse(
                row.missionId(),
                row.code(),
                row.title(),
                row.category(),
                row.description(),
                row.unit(),
                row.targetValue(),
                row.currentValue(),
                progress(row),
                status(row),
                row.displayOrder()
        );
    }

    private MissionDetailResponse toDetail(MissionRow row) {
        return new MissionDetailResponse(
                row.missionId(),
                row.code(),
                row.title(),
                row.category(),
                row.description(),
                row.unit(),
                row.missionDate(),
                row.targetValue(),
                row.currentValue(),
                Math.max(0, row.targetValue() - row.currentValue()),
                progress(row),
                status(row),
                row.completedAt(),
                row.feedback()
        );
    }

    private MissionStatus status(MissionRow row) {
        if (row.completedAt() != null) return MissionStatus.COMPLETED;
        if (row.missionDate().isBefore(LocalDate.now(clock.withZone(HealthPeriod.ZONE)))) return MissionStatus.EXPIRED;
        if (row.currentValue() >= row.targetValue()) return MissionStatus.COMPLETABLE;
        if (row.currentValue() > 0) return MissionStatus.IN_PROGRESS;
        return MissionStatus.READY;
    }

    private int progress(MissionRow row) {
        if (row.targetValue() <= 0) return 0;
        return Math.min(100, (int) Math.round(row.currentValue() * 100.0 / row.targetValue()));
    }

    private static int rate(int completed, int total) {
        if (total <= 0) return 0;
        return Math.min(100, (int) Math.round(completed * 100.0 / total));
    }
}
