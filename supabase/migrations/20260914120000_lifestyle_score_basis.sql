-- 작성자: 고수연 — 점수 계산 근거를 남긴다. 계산이 FastAPI(heapy-health-v1)로 넘어오며
-- 수면이 네 갈래로 나뉘고 대사(혈압·공복혈당)가 더해졌는데, 지금 표에는 "수면 84.5점"만
-- 남아 그게 규칙성 때문인지 수면시간 때문인지 사라진다. AI 해석 문장을 만들 때마다 다시
-- 계산해야 하고, 화면에서 "혈압 때문에 깎였다"를 보여줄 수 없다.
--
-- 판이 바뀌었으므로 옛 판으로 계산된 행을 먼저 비운다. 산출 방식이 다른 점수를 한 그래프에
-- 이어 그리면 사용자에게 원인 모를 계단이 생긴다. 행은 7일치뿐이라 잃을 것이 없다.
delete from public.lifestyle_daily_scores where policy_version <> 'heapy-health-v1';

alter table public.lifestyle_daily_scores
    -- 충분성의 창이 8일이다. 분석일 당일 기록이 빠지고 자정을 넘겨 끝난 잠도 cutoff에
    -- 걸려 빠지기 때문에, 8일을 잡아야 최근 한 주가 온전히 남는다.
    drop constraint lifestyle_daily_scores_sleep_recorded_days_check,
    add constraint lifestyle_daily_scores_sleep_recorded_days_check
        check (sleep_recorded_days between 0 and 8),

    -- 점수의 뼈대. 추이를 그리거나 집계할 값이라 열로 둔다.
    -- 충분성만 필수이고 나머지 셋은 기록이 모자라면 비어 있을 수 있다.
    add column sleep_duration_score   double precision check (sleep_duration_score   between 0 and 100),
    add column sleep_regularity_score double precision check (sleep_regularity_score between 0 and 100),
    add column sleep_stability_score  double precision check (sleep_stability_score  between 0 and 100),
    add column sleep_jetlag_score     double precision check (sleep_jetlag_score     between 0 and 100),
    -- 가중치의 몇 할이 실제로 채워졌는지. 1.0이 아니면 일부만 반영한 점수다.
    add column sleep_coverage         double precision check (sleep_coverage > 0 and sleep_coverage <= 1),

    -- BMI를 생활 기록에서 가져왔는지 검진에서 가져왔는지. 검진 값이면 사용자에게 고지해야
    -- 하므로 조회 응답까지 내려간다. 문구는 계산한 쪽이 만들어 준 것을 그대로 적는다.
    -- Java에 같은 문장을 한 벌 더 두면 문서·테스트로 묶인 원본과 갈라진다.
    add column bmi_source             text check (bmi_source in ('lifestyle', 'checkup')),
    add column bmi_notice             text,

    -- 대사(혈압·공복혈당)는 선택 성분이라 통째로 비어 있을 수 있다.
    add column metabolic_score          double precision check (metabolic_score         between 0 and 100),
    add column metabolic_bp_score       double precision check (metabolic_bp_score      between 0 and 100),
    add column metabolic_glucose_score  double precision check (metabolic_glucose_score between 0 and 100),
    add column metabolic_coverage       double precision check (metabolic_coverage > 0 and metabolic_coverage <= 1),

    -- 총점에서 실제로 채워진 가중치의 비율. 대사가 없으면 0.9다.
    -- 성분이 하나도 안 서면 0이므로 0을 막지 않는다.
    add column score_coverage         double precision check (score_coverage >= 0 and score_coverage <= 1),

    -- 설명에 쓰는 서술 값. 취침·기상 시각, 흔들림(분), 주중·주말 수면 중점, 혈압 수치 등.
    -- 프롬프트를 다듬으며 계속 바뀌는 부분이라 열로 고정하지 않는다. 어느 판으로 만든
    -- 값인지는 policy_version이 말해 준다.
    add column sleep_detail           jsonb,
    add column metabolic_detail       jsonb,

    -- 수면 점수가 있으면 충분성과 coverage는 반드시 함께 있다.
    add constraint lifestyle_daily_scores_sleep_parts_check check (
        sleep_score is null
        or (sleep_duration_score is not null and sleep_coverage is not null)
    ),
    -- 대사 점수가 있으면 둘 중 하나는 있고 coverage도 있다.
    add constraint lifestyle_daily_scores_metabolic_parts_check check (
        metabolic_score is null
        or (metabolic_coverage is not null
            and (metabolic_bp_score is not null or metabolic_glucose_score is not null))
    ),
    -- BMI 점수가 있으면 어디서 가져왔는지도 있어야 한다.
    -- 이름에 bmi_source 를 쓰면 안 된다. 위 열 정의의 check 가 Postgres 자동 이름
    -- lifestyle_daily_scores_bmi_source_check 를 이미 차지한다.
    add constraint lifestyle_daily_scores_bmi_origin_check check (
        bmi_score is null or bmi_source is not null
    );

comment on column public.lifestyle_daily_scores.sleep_detail is
    '고수연: 취침·기상 시각 등 서술 값. 개인 건강정보라 앱 응답에 싣지 않는다. 서버 전용.';
comment on column public.lifestyle_daily_scores.metabolic_detail is
    '고수연: 혈압·공복혈당의 측정일·출처·수치. 서버 전용.';
comment on column public.lifestyle_daily_scores.bmi_notice is
    '고수연: 검진 BMI로 계산했을 때 사용자에게 밝힐 문구. 비어 있으면 고지할 것이 없다.';
