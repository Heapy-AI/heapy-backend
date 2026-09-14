package com.heapy.mission.service;

import com.heapy.mission.model.MissionEvidence;
import com.heapy.mission.model.MissionSuggestion;
import com.heapy.mission.service.MissionRecommendationEngine.Definition;
import com.heapy.mission.service.MissionRecommendationEngine.Input;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/** 기록 확보와 습관 유지 후보를 제공한다. 자격 보유는 제품 전제로 취급한다. @author 김진우 */
public final class MissionContinuityRecommendations {
    private MissionContinuityRecommendations() { }

    public static List<MissionSuggestion> candidates(Input input, MissionEvidence evidence, String scope,
            List<Definition> definitions) {
        var result = new ArrayList<MissionSuggestion>();
        switch (scope) {
            case "BIO" -> {
                add(result, definitions, "REC-BIO-001", scope, "오늘 혈압 측정값 직접 기록하기", "BIO",
                        "측정한 혈압을 앱에 직접 입력해 오늘의 기록을 남겨요.", "manual_bp", "blood_pressure");
                add(result, definitions, "REC-BIO-004", scope, "오늘 체중 직접 기록하기", "BIO",
                        "측정한 체중을 앱에 입력해 변화 추이를 이어가요.", "manual_weight", "body_composition");
            }
            case "SLEEP" -> {
                if (MissionRecommendationEngine.values(input, 7, 0, "sleep").size() < 5) {
                    add(result, definitions, "REC-SLP-001", scope, "어젯밤 수면 직접 기록하기", "SLEEP",
                            "수면 기록이 충분하지 않아요. 어젯밤 취침·기상 시각을 앱에 입력해 주세요.", "manual_sleep", "sleep");
                } else if (input.eligible()) {
                    var sleeps = evidence.events().stream().filter(e -> e.type().equals("sleep")
                            && MissionFullRecommendationEngine.date(e.end()).isBefore(input.today())
                            && !MissionFullRecommendationEngine.date(e.end()).isBefore(input.today().minusDays(7))).toList();
                    if (!sleeps.isEmpty()) {
                        int minute = Math.floorMod((int) MissionRecommendationEngine.median(sleeps.stream()
                                .map(e -> (double) MissionFullRecommendationEngine.bedMinute(e.start())).toList()), 1440);
                        String time = String.format("%02d:%02d", minute / 60, minute % 60);
                        addHabit(result, definitions, "SLP-004", scope, "평소 " + time + " 취침에 맞춰 잠자리 준비하기",
                                "SLEEP", "최근 7일 취침 시각을 기준으로 일정한 수면 습관을 유지해요.",
                                "manual", "COUNT", 1, Map.of("policy", "continuity", "minute", minute));
                    }
                }
            }
            case "NUTRITION" -> add(result, definitions, "WTR-007", scope, "오늘 마신 물 직접 기록하기", "HYDRATION",
                    "이미 마신 물의 양을 앱에 입력해 수분 기록을 이어가요. 추가로 마시는 목표는 아니에요.", "manual_water", "water");
            case "CHECKUP" -> {
                if (evidence.checkupCount() == 0) {
                    add(result, definitions, "REC-CHK-001", scope, "건강검진 결과 등록하기", "CHECKUP",
                            "보유한 건강검진 결과를 등록해 맞춤 분석에 활용해요.", "record_checkup_any", "checkup");
                } else if (evidence.checkupCount() == 1) {
                    add(result, definitions, "REC-CHK-002", scope, "이전 건강검진 결과 등록하기", "CHECKUP",
                            "이전 회차 결과를 등록해 검진 수치의 변화를 비교해요.", "record_old_checkup", "checkup",
                            Map.of("beforeDate", evidence.latestCheckupDate().toString()));
                }
                if (input.eligible()) activity(result, definitions, scope);
            }
            case "ACTIVITY" -> {
                if (input.eligible()) activity(result, definitions, scope);
            }
            default -> { }
        }
        return result;
    }

    private static void activity(List<MissionSuggestion> result, List<Definition> definitions, String scope) {
        addHabit(result, definitions, "ACT-015", scope, "오늘 10분 가볍게 걷기", "ACTIVITY",
                "기록을 이어가며 부담이 적은 걷기 습관을 유지해요.", "walk", "MINUTE", 10, Map.of("policy", "continuity"));
        addHabit(result, definitions, "ACT-014", scope, "오늘 10분 스트레칭하기", "ACTIVITY",
                "일상에서 가볍게 몸을 움직이는 습관을 이어가요.", "stretch", "MINUTE", 10, Map.of("policy", "continuity"));
    }

    private static void add(List<MissionSuggestion> result, List<Definition> definitions, String code, String scope,
            String title, String category, String description, String completion, String action) {
        add(result, definitions, code, scope, title, category, description, completion, action, Map.of());
    }

    private static void add(List<MissionSuggestion> result, List<Definition> definitions, String code, String scope,
            String title, String category, String description, String completion, String action, Map<String,Object> extra) {
        var parameters = new HashMap<String,Object>(extra);
        parameters.put("recordAction", action);
        parameters.put("policy", "continuity");
        definitions.stream().filter(d -> d.code().equals(code)).findFirst().ifPresent(d -> result.add(
                new MissionSuggestion(code, scope, title, category, description, "COUNT", 1, 15,
                        "HEAPY_RECORD_CONTINUITY", d.catalogVersion(), d.ruleVersion(), completion, false,
                        "RECORDING", "DAILY", Map.copyOf(parameters))));
    }

    private static void addHabit(List<MissionSuggestion> result, List<Definition> definitions, String code, String scope,
            String title, String category, String description, String completion, String unit, int target, Map<String,Object> parameters) {
        definitions.stream().filter(d -> d.code().equals(code)).findFirst().ifPresent(d -> result.add(
                new MissionSuggestion(code, scope, title, category, description, unit, target, 10,
                        "HEAPY_HABIT_CONTINUITY", d.catalogVersion(), d.ruleVersion(), completion, true,
                        "HABIT", "DAILY", parameters)));
    }
}
