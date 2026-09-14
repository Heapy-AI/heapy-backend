package com.heapy.health.model;

import java.time.LocalDate;
import java.util.List;

/**
 * 오늘의 건강 종합 점수의 조회 결과.
 *
 * 계산은 FastAPI가 하므로 원천 기록을 담던 Session·Steps·Bmi·Input 은 없앴다. 여기에는
 * 저장하고 내려줄 모양만 남는다.
 *
 * @author 김진우, 고수연
 */
public final class LifestyleScore {
    private LifestyleScore() { }
    public record Component(Double score, int recordedDays, int requiredDays) { }

    /** 수면 하위 점수. 충분성만 필수이고 나머지 셋은 기록이 모자라면 빈다. */
    public record SleepParts(Double duration, Double regularity, Double stability,
                             Double jetlag, Double coverage) { }

    /** 대사. 혈압계·혈당계가 없으면 쌓이지 않는 값이라 통째로 빌 수 있다. */
    public record Metabolic(Double score, Double bloodPressure, Double glucose, Double coverage) { }

    /**
     * 하루치 점수. 앞의 일곱은 예전 계약 그대로 두고 근거를 뒤에 붙였다.
     *
     * 필드 이름을 바꾸면 앱이 읽던 자리가 사라진다. 새 필드는 앱이 아직 안 읽어도 그만이다.
     */
    public record Day(LocalDate date, Integer score, Component sleep, Component activity,
                      Double bmiScore, LocalDate bmiDate, List<String> reasons,
                      SleepParts sleepParts, String bmiSource, String bmiNotice,
                      Metabolic metabolic, Double coverage) { }

    /**
     * 저장할 한 줄. `detail` 은 취침·기상 시각 같은 서술 값의 jsonb 원문이다.
     *
     * 개인 건강정보라 서버에만 둔다. {@link Day} 에 넣으면 조회 응답에 그대로 실려 나간다.
     */
    public record Saved(Day day, String sleepDetail, String metabolicDetail) { }

    public record Report(String policyVersion, String timezone, HealthPeriod period,
                         Day latest, List<Day> points) { }
}
