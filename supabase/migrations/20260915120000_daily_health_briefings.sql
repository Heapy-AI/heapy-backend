-- 작성자: 고수연 — 홈 '오늘의 AI 건강 브리핑'을 담는다.
--
-- 이 표는 Supabase에서 직접 만들어져 레포의 마이그레이션 이력에 없다. 다른 환경에는 아예
-- 없을 수 있어 여기서 없으면 만들고 있으면 맞춘다. 이미 있는 환경에서도 끝까지 통과한다.
--
-- 계약은 docs/홈_건강_브리핑_계약.md 를 볼 것. (heapy-ai-health)
create table if not exists public.daily_health_briefings (
    briefing_id uuid not null default gen_random_uuid(),
    user_id uuid not null references public.users(user_id) on delete cascade,
    briefing_date date not null,
    status text not null,
    headline text,
    body text,
    sections jsonb not null default '[]'::jsonb,
    evidence_snapshot jsonb not null default '{}'::jsonb,
    metric_policy_versions jsonb not null default '{}'::jsonb,
    model_name text,
    model_version text,
    prompt_version text,
    failure_code text,
    generated_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint daily_health_briefings_pkey primary key (briefing_id),
    constraint daily_health_briefings_user_date_uq unique (user_id, briefing_date)
);

-- 카드의 칩 한 줄. sections 에서 되만들 수 없어 열로 둔다. 칩은 갈래의 label 과 change 를
-- 붙인 것인데 change 는 sections 에 담기지 않기 때문이다.
alter table public.daily_health_briefings
    add column if not exists chip text;

-- 상태 표기를 나머지 시스템과 맞춘다. 원래 'insufficient_data' 였는데 FastAPI의
-- AnalysisRequest, HealthAnalysisGateway, health_analysis_runs, 앱의 Analysis 타입이 전부
-- 'data_insufficient' 를 쓴다. 이 표만 반대였다.
update public.daily_health_briefings set status = 'data_insufficient' where status = 'insufficient_data';
alter table public.daily_health_briefings
    drop constraint if exists daily_health_briefings_status_check,
    add constraint daily_health_briefings_status_check check (
        status in ('pending', 'generated', 'data_insufficient', 'failed')
    );

create index if not exists idx_daily_health_briefings_user_date
    on public.daily_health_briefings using btree (user_id, briefing_date desc);

-- 개인 건강정보다. 다른 건강 표와 같게 서버만 읽고 쓴다.
alter table public.daily_health_briefings enable row level security;
revoke all on public.daily_health_briefings from public, anon, authenticated;
grant select, insert, update, delete on public.daily_health_briefings to service_role;

comment on table public.daily_health_briefings is
    '고수연: 홈 화면의 하루 한 건 AI 건강 브리핑. 앱은 /api/home 을 통해 읽기만 한다. 서버 전용.';
comment on column public.daily_health_briefings.evidence_snapshot is
    '고수연: 문장이 주장한 숫자. 나중에 틀린 수치를 되짚기 위한 것이라 앱 응답에 싣지 않는다.';
