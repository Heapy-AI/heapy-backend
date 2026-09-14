package com.heapy.mission.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 시간 구간과 출처를 유지한 추천·진행률 공통 증거. @author 김진우 */
public record MissionEvidence(MissionOptions options, List<Event> events, int checkupCount,
        LocalDate latestCheckupDate, List<Exposure> exposures) {
    public record Event(String type, Instant start, Instant end, double value, String tag) { }
    public record Exposure(String code, String event, LocalDate date) { }
    public static MissionEvidence empty() { return new MissionEvidence(MissionOptions.empty(),List.of(),0,null,List.of()); }
}
