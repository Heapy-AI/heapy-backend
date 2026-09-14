package com.heapy.mission.service;

import com.heapy.mission.model.MissionSuggestion;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** HEAPY 미션 추천 규칙 v0.2의 데이터가 매핑된 일일 행동 규칙. @author 김진우 */
public final class MissionRecommendationEngine {
    private MissionRecommendationEngine() { }

    public record Day(LocalDate date, Double steps, Double minutes, Double floors, Double water,
            Double sleep, Double weight) { }
    public record Definition(String code, int catalogVersion, int ruleVersion, String ruleId,
            String completion, boolean manualAllowed) { }
    public record History(String code, String category, LocalDate date, boolean completed,
            boolean active, boolean abandoned) { }
    public record Input(LocalDate today, boolean eligible, boolean waterSafe, Set<String> managed,
            List<Day> days, List<History> history) { }

    public static List<MissionSuggestion> recommend(Input input, String scope, List<Definition> definitions) {
        if (!input.eligible() || scope.equals("SUMMARY")) return List.of();
        List<MissionSuggestion> result = new ArrayList<>();
        var steps = values(input, 14, 0, "steps");
        var previousSteps = values(input, 14, 14, "steps");
        var minutes = values(input, 28, 0, "minutes");
        var recentMinutes = values(input, 7, 0, "minutes");
        var water = values(input, 14, 0, "water");
        var sleep = values(input, 7, 0, "sleep");
        boolean stepDrop = steps.size() >= 7 && previousSteps.size() >= 7
                && median(previousSteps) > 0 && median(steps) <= median(previousSteps) * .85;
        boolean lowTime = minutes.size() >= 14 && recentMinutes.stream().filter(v -> v < 30).count() >= 5;
        for (Definition d : definitions) {
            int target = 0, need = 20, reliability = 10, achievable = 20;
            String title = "", reason = "", unit = "MINUTE", category = "ACTIVITY";
            switch (d.ruleId()) {
                case "ACT_STEP_DROP_01" -> {
                    if (!stepDrop) continue;
                    double baseline = median(steps);
                    double increase = Math.min(1000, Math.max(500, Math.round(baseline * .1 / 100) * 100));
                    target = (int) (Math.floor((baseline + increase) / 100) * 100);
                    if (target <= baseline || target - baseline > Math.min(1000, baseline * .15)) continue;
                    title = "오늘 " + target + "보 걷기";
                    if (d.code().equals("ACT-004")) title = "오늘 평소보다 " + (int)(target - baseline) + "보 더 걷기";
                    unit = "STEP"; need = 30; achievable = (target - baseline) / baseline <= .1 ? 20 : 15;
                    reliability = reliability(Math.min(steps.size(), previousSteps.size()), 14);
                    reason = "최근 14일 걸음 수가 이전 14일보다 줄어든 흐름을 반영했어요.";
                }
                case "ACT_DAILY_TIME_LOW_01" -> {
                    if (!lowTime) continue;
                    target = switch (d.code()) { case "ACT-005" -> 20; case "ACT-012", "ACT-013" -> 10;
                        default -> (int)Math.min(30, Math.floor(median(recentMinutes) + 10)); };
                    title = switch (d.code()) { case "ACT-005" -> "오늘 20분 연속 걷기";
                        case "ACT-012" -> "오전에 10분 걷기"; case "ACT-013" -> "오후에 10분 걷기";
                        default -> "오늘 활동 시간 " + target + "분 채우기"; };
                    reliability = reliability(minutes.size(), 28); achievable = target <= 15 ? 20 : 15;
                    reason = "최근 7일 활동시간과 28일 기록을 바탕으로 추천했어요.";
                }
                case "ACT_FLOOR_DROP_01" -> {
                    var current = values(input, 14, 0, "floors");
                    var previous = values(input, 14, 14, "floors");
                    if (current.size() < 7 || previous.size() < 7 || median(previous) <= 0
                            || median(current) > median(previous) * .8) continue;
                    target = (int)Math.floor(Math.min(median(previous), median(current) + 1));
                    if (target <= median(current)) continue;
                    unit = "FLOOR"; title = "오늘 계단 " + target + "층 오르기"; need = 30;
                    reliability = reliability(Math.min(current.size(), previous.size()), 14);
                    reason = "최근 14일 오른 층수와 이전 14일 기록을 비교해 추천했어요.";
                }
                case "ACT_DATA_FALLBACK_01" -> {
                    if (steps.size() >= 7 && minutes.size() >= 14) continue;
                    target = 10; need = 10; reliability = 5;
                    title = d.code().equals("ACT-014") ? "오늘 10분 스트레칭하기" : "오늘 10분 걷기";
                    reason = "아직 활동 기록이 충분하지 않아 가볍게 시작할 수 있는 미션을 추천했어요.";
                }
                case "SLEEP_SHORT_01" -> {
                    if (sleep.size() < 5 || median(sleep) >= 420) continue;
                    category = "SLEEP"; target = (int)Math.floor(Math.min(420, median(sleep) + 30));
                    title = "오늘 " + target + "분 이상 자기"; reliability = reliability(sleep.size(), 7);
                    achievable = 30 / median(sleep) <= .1 ? 20 : 15;
                    reason = "최근 7일 수면시간을 바탕으로 오늘 밤 목표를 추천했어요. 내일 수면 기록으로 확인해요.";
                }
                case "WATER_DATA_FALLBACK_01" -> {
                    if (!input.waterSafe() || water.size() >= 7) continue;
                    category = "HYDRATION"; target = 250; unit = "ML"; need = 10; reliability = 5;
                    title = "오늘 물 250mL 마시기";
                    reason = "아직 물 기록이 충분하지 않아 한 잔부터 시작해요. 미션 추가 이후의 기록으로 확인해요.";
                }
                case "WATER_DROP_01" -> {
                    var current = values(input, 7, 0, "water"); var previous = values(input, 7, 7, "water");
                    if (!input.waterSafe() || current.size() < 5 || previous.size() < 5
                            || median(previous) <= 0 || median(current) > median(previous) * .8) continue;
                    category = "HYDRATION"; unit = "ML"; target = (int)Math.floor(median(current) + 250);
                    title = "오늘 평소보다 물 250mL 더 마시기"; need = 30;
                    reliability = reliability(Math.min(current.size(), previous.size()), 7);
                    achievable = 250 / median(current) <= .1 ? 20 : 250 / median(current) <= .15 ? 15 : 5;
                    reason = "최근 7일과 이전 7일 물 기록을 비교했어요. 오늘 목표는 " + target + "mL예요.";
                }
                default -> { continue; }
            }
            if (target <= 0) continue;
            boolean match = scope.equals(category) || scope.equals("NUTRITION") && category.equals("HYDRATION");
            if (scope.equals("BIO") || scope.equals("CHECKUP")) {
                boolean bp = input.managed().contains("BP") && lowTime
                        && Set.of("ACT-012", "ACT-013").contains(d.code());
                var weights = values(input, 28, 0, "weight"); var previousWeights = values(input, 28, 28, "weight");
                boolean weightRise = weights.size() >= 2 && previousWeights.size() >= 2
                        && median(weights) >= median(previousWeights) * 1.02;
                match = bp || scope.equals("BIO") && weightRise && stepDrop && Set.of("ACT-003", "ACT-004").contains(d.code())
                        || scope.equals("CHECKUP") && category.equals("ACTIVITY") && (
                            input.managed().contains("WEIGHT") && (stepDrop || lowTime) && !d.ruleId().contains("FALLBACK") && !d.code().equals("ACT-010")
                            || input.managed().contains("LIPID") && lowTime && d.code().equals("ACT-006"));
                if (match) { need = scope.equals("CHECKUP") || bp ? 40 : 30;
                    reason = scope.equals("BIO") ? "최근 생체·검진 상태와 활동 기록을 함께 보고 추천했어요. " + reason
                            : "최근 검진의 관리 상태와 활동 기록을 함께 보고 추천했어요. " + reason; }
            }
            if (!match) continue;
            var history = input.history().stream().filter(h -> h.code().equals(d.code())).toList();
            if (history.stream().anyMatch(h -> h.active() || h.completed() && !h.date().isBefore(input.today().minusDays(14))
                    || h.abandoned() && !h.date().isBefore(input.today().minusDays(7)))) continue;
            var last = history.stream().map(History::date).max(Comparator.naturalOrder()).orElse(LocalDate.MIN);
            int novelty = last.isBefore(input.today().minusDays(29)) ? 10 : last.isBefore(input.today().minusDays(14)) ? 5 : 0;
            String selectedCategory = category;
            var previous = input.history().stream().filter(h -> h.category().equals(selectedCategory) && !h.active())
                    .sorted(Comparator.comparing(History::date).reversed()).limit(3).toList();
            long success = previous.stream().filter(History::completed).count();
            int historyScore = success >= 2 ? 10 : previous.size() == 3 && success == 0 ? 0 : 5;
            result.add(new MissionSuggestion(d.code(), scope, title, category, reason, unit, target,
                    need + reliability + achievable + novelty + historyScore, d.ruleId(),
                    d.catalogVersion(), d.ruleVersion(), d.completion(), d.manualAllowed()));
        }
        return result.stream().sorted(Comparator.comparingInt(MissionSuggestion::score).reversed()
                .thenComparing(MissionSuggestion::manualAllowed).thenComparing(MissionSuggestion::code)).toList();
    }

    static List<Double> values(Input input, int days, int offset, String field) {
        LocalDate end = input.today().minusDays(offset), start = end.minusDays(days);
        return input.days().stream().filter(d -> !d.date().isBefore(start) && d.date().isBefore(end))
                .map(d -> switch(field) { case "steps" -> d.steps(); case "minutes" -> d.minutes();
                    case "floors" -> d.floors(); case "water" -> d.water(); case "weight" -> d.weight(); default -> d.sleep(); })
                .filter(v -> v != null && Double.isFinite(v) && v >= 0).toList();
    }
    static double median(List<Double> values) {
        var sorted = values.stream().sorted().toList(); int size = sorted.size();
        return size == 0 ? 0 : size % 2 == 1 ? sorted.get(size / 2) : (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2;
    }
    private static int reliability(int valid, int days) { return valid >= days * .8 ? 20 : valid >= days * .6 ? 15 : 10; }
}
