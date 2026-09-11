-- 작성자: 김진우 — 최근 7일의 날짜별 계산 결과만 저장한다. 원천 건강 기록은 삭제하지 않는다.
create table public.lifestyle_daily_scores (
    user_id uuid not null references public.users(user_id) on delete cascade,
    score_date date not null,
    policy_version text not null,
    total_score smallint check (total_score between 0 and 100),
    sleep_score double precision check (sleep_score between 0 and 100),
    sleep_recorded_days smallint not null check (sleep_recorded_days between 0 and 7),
    activity_score double precision check (activity_score between 0 and 100),
    activity_recorded_days smallint not null check (activity_recorded_days between 0 and 14),
    bmi_score double precision check (bmi_score between 0 and 100),
    bmi_date date,
    reasons text[] not null,
    calculated_at timestamptz not null default now(),
    primary key (user_id, score_date),
    constraint lifestyle_daily_scores_result_check check (
        (total_score is null and cardinality(reasons)>0) or
        (total_score is not null and cardinality(reasons)=0 and sleep_score is not null
         and activity_score is not null and bmi_score is not null and sleep_recorded_days>=5
         and activity_recorded_days>=7 and bmi_date is not null)
    ),
    constraint lifestyle_daily_scores_bmi_date_check check (bmi_date<=score_date)
);
create index idx_lifestyle_daily_scores_date on public.lifestyle_daily_scores(score_date);
alter table public.lifestyle_daily_scores enable row level security;
revoke all on public.lifestyle_daily_scores from public, anon, authenticated, service_role;
grant select, insert, delete on public.lifestyle_daily_scores to service_role;
comment on table public.lifestyle_daily_scores is '김진우: 하루 한 번 확정하는 HEAPY 생활습관 관리 점수. 최근 7일 유지, 점수 부족 상태도 저장. 서버 전용.';
