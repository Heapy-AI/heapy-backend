package com.heapy.health.service;

import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import com.heapy.health.analysis.HealthAnalysisGateway;
import com.heapy.health.analysis.HealthAnalysisSnapshot;
import com.heapy.health.model.HealthPeriod;
import com.heapy.health.model.LifestyleScore.Component;
import com.heapy.health.model.LifestyleScore.Day;
import com.heapy.health.model.LifestyleScore.Metabolic;
import com.heapy.health.model.LifestyleScore.Report;
import com.heapy.health.model.LifestyleScore.Saved;
import com.heapy.health.model.LifestyleScore.SleepParts;
import com.heapy.health.repository.LifestyleScoreStore;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 오늘의 건강 종합 점수를 조회한다. 최초 또는 누락 날짜만 계산하고 저장된 결과는 재사용한다.
 *
 * 계산 자체는 FastAPI가 한다. 수면의 규칙성·안정성·사회적 시차는 시각의 원형 통계라
 * Java로 다시 구현하면 두 벌이 되어 갈라진다. 여기서는 스냅샷을 실어 보내고 돌아온
 * 날짜별 점수를 저장·조회하는 일만 한다.
 *
 * @author 김진우, 고수연
 */
@Service
public class LifestyleScoreService {
    /**
     * 응답이 알리는 정책 판. FastAPI의 `POLICY_VERSION`과 같은 값이어야 한다.
     *
     * 저장된 행에는 그때 실제로 계산한 판이 각각 적히므로, 판이 바뀌면 이 상수만 올릴 것이
     * 아니라 옛 판으로 계산된 행을 지워야 한다. 산출 방식이 다른 점수를 한 그래프에 이어
     * 그리면 사용자에게 원인 모를 계단이 생긴다.
     */
    public static final String VERSION = "heapy-health-v1";
    private static final int WINDOW_DAYS = 7;
    // 저장된 행에는 required_days가 없어 조회할 때 이 값으로 되살린다. FastAPI의
    // _DURATION_MIN_DAYS·_ACTIVITY_MIN_DAYS와 같은 값이다.
    private static final int SLEEP_REQUIRED_DAYS = 5;
    private static final int ACTIVITY_REQUIRED_DAYS = 7;

    private final Clock clock;
    private final LifestyleScoreStore store;
    private final HealthAnalysisSnapshot snapshots;
    private final HealthAnalysisGateway gateway;

    public LifestyleScoreService(Clock clock, LifestyleScoreStore store,
                                 HealthAnalysisSnapshot snapshots, HealthAnalysisGateway gateway) {
        this.clock = clock; this.store = store; this.snapshots = snapshots; this.gateway = gateway;
    }

    /**
     * 트랜잭션을 걸지 않는다. 가운데에 외부 호출이 있기 때문이다.
     *
     * 스냅샷 읽기와 저장은 각각 제 트랜잭션에서 끝나고, 그 사이의 FastAPI 호출은 어떤
     * 트랜잭션에도 들어가지 않는다. 읽기 90초를 기다리는 호출을 20초짜리 트랜잭션 안에
     * 두면 커넥션을 그만큼 붙잡는다.
     */
    public Report find(UUID user, String code, LocalDate baseDate) {
        if (!"7d".equals(code)) throw new HeapyException(ErrorCode.INVALID_INPUT);
        LocalDate today = LocalDate.now(clock.withZone(HealthPeriod.ZONE));
        if (baseDate != null && !baseDate.equals(today) && !baseDate.equals(today.minusDays(1))) {
            throw new HeapyException(ErrorCode.INVALID_INPUT);
        }
        HealthPeriod period = HealthPeriod.of(code, today.minusDays(1), "day");
        List<Day> saved = store.find(user, period);
        if (saved.size() == WINDOW_DAYS) return report(period, saved);
        try {
            JsonNode result = ask(user, today);
            List<Saved> calculated = result == null ? List.of() : points(result, period);
            if (!calculated.isEmpty() && today.equals(LocalDate.now(clock.withZone(HealthPeriod.ZONE)))) {
                // 판은 응답이 알린 값을 그대로 적는다. 행마다 무엇으로 계산했는지 남아야 한다.
                String version = result.path("score").path("policy_version").asText();
                saved = store.save(user, period, calculated, version.isBlank() ? VERSION : version);
            }
        } catch (RuntimeException error) {
            // 작성자: 고수연 — 스냅샷을 못 읽거나 분석 호출이 실패해도 화면은 떠야 한다.
            // 저장된 날짜만으로 응답하고 나머지는 사유가 붙은 빈 점수로 남긴다. 행이 모자란
            // 채로 남으므로 배치가 다음 주기에 다시 집어 든다.
        }
        return report(period, saved);
    }

    /** 스냅샷을 한 번 읽어 FastAPI에 한 번만 묻는다. 하루씩 나눠 부를 이유가 없다. */
    private JsonNode ask(UUID user, LocalDate today) {
        Map<String, Object> snapshot = snapshots.load(user, today);
        if (snapshot == null) return null;
        Map<String, Object> request = new LinkedHashMap<>(snapshot);
        request.put("contractVersion", "1.0");
        request.put("category", "score");
        request.put("analysisDate", today.toString());
        request.put("cutoff", today.atStartOfDay(HealthPeriod.ZONE).toInstant().toString());
        // 계열의 마지막 날은 언제나 분석일이다. 우리 창은 어제까지이므로 한 칸 더 받아
        // 오늘 것만 버린다. 오늘 점수를 저장하면 어제까지의 기록으로 굳어 버려, 오늘 하루
        // 걸은 것이 영영 반영되지 않는다.
        request.put("scoreDays", WINDOW_DAYS + 1);
        return gateway.score(request);
    }

    /** 창 밖의 날짜는 버린다. 마지막 점은 언제나 오늘이고 오늘은 아직 저장하지 않는다. */
    private static List<Saved> points(JsonNode result, HealthPeriod period) {
        List<Saved> rows = new ArrayList<>();
        for (JsonNode point : result.path("points")) {
            Saved row = saved(point);
            LocalDate date = row.day().date();
            if (!date.isBefore(period.from()) && !date.isAfter(period.to())) rows.add(row);
        }
        return rows;
    }

    /**
     * 한 점을 저장할 모양으로 옮긴다.
     *
     * 서술 값은 성분 마디를 통째로 담는다. 열이 된 값만 골라 빼면 응답 모양이 바뀔 때마다
     * 여기가 조용히 어긋난다. 행은 7일이면 지워지므로 조금 겹쳐도 잃을 것이 없다.
     */
    private static Saved saved(JsonNode point) {
        JsonNode components = point.path("components");
        JsonNode sleep = components.path("sleep");
        JsonNode bmi = components.path("bmi");
        JsonNode metabolic = components.path("metabolic");
        List<String> reasons = new ArrayList<>();
        for (JsonNode reason : point.path("reasons")) reasons.add(reason.asText());
        JsonNode parts = sleep.path("parts");
        Day day = new Day(LocalDate.parse(point.path("score_date").asText()),
                point.path("total_score").isNumber() ? point.path("total_score").asInt() : null,
                component(sleep, SLEEP_REQUIRED_DAYS),
                component(components.path("activity"), ACTIVITY_REQUIRED_DAYS),
                number(bmi.path("score")),
                bmi.path("measured_date").isTextual() ? LocalDate.parse(bmi.path("measured_date").asText()) : null,
                List.copyOf(reasons),
                sleep.isObject() ? new SleepParts(number(parts.path("duration").path("score")),
                        number(parts.path("regularity").path("score")),
                        number(parts.path("stability").path("score")),
                        number(parts.path("social_jetlag").path("score")),
                        number(sleep.path("coverage"))) : null,
                bmi.path("source").isTextual() ? bmi.path("source").asText() : null,
                bmi.path("notice").isTextual() ? bmi.path("notice").asText() : null,
                metabolic.isObject() ? new Metabolic(number(metabolic.path("score")),
                        number(metabolic.path("parts").path("blood_pressure").path("score")),
                        number(metabolic.path("parts").path("glucose").path("score")),
                        number(metabolic.path("coverage"))) : null,
                number(point.path("coverage")));
        return new Saved(day, sleep.isObject() ? sleep.toString() : null,
                metabolic.isObject() ? metabolic.toString() : null);
    }

    private static Double number(JsonNode node) {
        return node.isNumber() ? node.asDouble() : null;
    }

    private static Component component(JsonNode node, int required) {
        return new Component(number(node.path("score")),
                node.path("recorded_days").isNumber() ? node.path("recorded_days").asInt() : 0,
                node.path("required_days").isNumber() ? node.path("required_days").asInt() : required);
    }

    /**
     * 저장된 날짜로 창을 채운다. 아직 못 낸 날짜는 사유만 담은 빈 점수로 메운다.
     *
     * 예전에는 저장된 목록의 마지막을 그대로 `latest`로 썼다. 중간이나 끝이 비면 오늘의
     * 점수 자리에 며칠 전 값이 앉는다. 날짜를 채워 두면 그런 일이 없다.
     */
    private static Report report(HealthPeriod period, List<Day> saved) {
        Map<LocalDate, Day> byDate = new LinkedHashMap<>();
        for (Day day : saved) byDate.put(day.date(), day);
        List<Day> points = new ArrayList<>();
        for (LocalDate day = period.from(); !day.isAfter(period.to()); day = day.plusDays(1)) {
            points.add(byDate.getOrDefault(day, blank(day)));
        }
        return new Report(VERSION, HealthPeriod.ZONE.getId(), period, points.getLast(), List.copyOf(points));
    }

    private static Day blank(LocalDate date) {
        return new Day(date, null, new Component(null, 0, SLEEP_REQUIRED_DAYS),
                new Component(null, 0, ACTIVITY_REQUIRED_DAYS), null, null, List.of("no_record"),
                null, null, null, null, null);
    }
}
