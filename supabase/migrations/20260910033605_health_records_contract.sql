-- 건강 기록의 출처 복원과 요청 중복 방지. 작성자: 김진우
-- 원격 적용 전 검증용 신규 마이그레이션이다.
set local lock_timeout = '5s';
set local statement_timeout = '60s';

create table private.health_record_requests (
    user_id uuid not null references public.users(user_id) on delete cascade,
    operation text not null,
    request_key uuid not null,
    request_hash text not null,
    response_body jsonb not null,
    created_at timestamptz not null default now(),
    primary key(user_id, operation, request_key)
);
alter table private.health_record_requests enable row level security;
revoke all on private.health_record_requests from public, anon, authenticated;

create table private.water_record_origins (
    record_id uuid primary key references public.lifestyle_water_intake(water_intake_id) on delete cascade,
    user_id uuid not null references public.users(user_id) on delete cascade,
    original_values jsonb not null,
    updated_at timestamptz not null default now(),
    check(jsonb_typeof(original_values) = 'object')
);
alter table private.water_record_origins enable row level security;
revoke all on private.water_record_origins from public, anon, authenticated;
create index water_record_origins_owner_idx on private.water_record_origins(user_id);

alter table public.lifestyle_bio add column height_cm numeric;
alter table public.lifestyle_bio add column insulin_micro_iu_ml numeric;
alter table public.lifestyle_bio add constraint lifestyle_bio_extra_nonnegative
    check ((height_cm is null or height_cm > 0) and (insulin_micro_iu_ml is null or insulin_micro_iu_ml >= 0));
alter table public.lifestyle_bio drop constraint lifestyle_bio_value_shape_check;
alter table public.lifestyle_bio add constraint lifestyle_bio_value_shape_check check (
    (bio_type = 'heart_rate' and heart_rate_bpm is not null) or
    (bio_type = 'blood_glucose' and blood_glucose_mg_dl is not null) or
    (bio_type = 'blood_pressure' and systolic_mmhg is not null and diastolic_mmhg is not null) or
    (bio_type = 'weight' and weight_kg is not null) or
    (bio_type = 'bmi' and bmi_value is not null) or
    (bio_type = 'body_composition' and (weight_kg is not null or body_fat_percent is not null or skeletal_muscle_kg is not null))
);

create table private.health_analysis_runs (
    user_id uuid not null references public.users(user_id) on delete cascade,
    analysis_date date not null,
    category text not null check(category in ('bio','activity','nutrition','sleep','checkup','overall')),
    status text not null check(status in ('pending','generating','generated','data_insufficient','failed')),
    started_at timestamptz,
    completed_at timestamptz,
    primary key(user_id, analysis_date, category)
);
alter table private.health_analysis_runs enable row level security;
revoke all on private.health_analysis_runs from public, anon, authenticated;
create index health_analysis_runs_date_idx on private.health_analysis_runs(analysis_date);

-- 작성자: 김진우 — 자정 이전 원본이 바뀐 날짜에는 과거 값을 추정하여 분석하지 않는다.
create table private.health_analysis_invalidations (
    user_id uuid not null references public.users(user_id) on delete cascade,
    analysis_date date not null,
    primary key(user_id, analysis_date)
);
alter table private.health_analysis_invalidations enable row level security;
revoke all on private.health_analysis_invalidations from public, anon, authenticated;
create index health_analysis_invalidations_date_idx on private.health_analysis_invalidations(analysis_date);

create function private.invalidate_health_analysis() returns trigger
language plpgsql security invoker set search_path = '' as $$
declare
    analysis_day date := (clock_timestamp() at time zone 'Asia/Seoul')::date;
begin
    if old.created_at < (analysis_day::timestamp at time zone 'Asia/Seoul')
       and exists (select 1 from public.users where user_id=old.user_id) then
        insert into private.health_analysis_invalidations(user_id, analysis_date)
        values (old.user_id, analysis_day) on conflict do nothing;
    end if;
    if tg_op = 'DELETE' then return old; end if;
    return new;
end;
$$;
revoke all on function private.invalidate_health_analysis() from public, anon, authenticated;

do $$
declare health_table text;
begin
    foreach health_table in array array['lifestyle_activity','lifestyle_bio','lifestyle_exercise',
        'lifestyle_nutrition','lifestyle_water_intake','lifestyle_sleep','health_checkup_records'] loop
        execute format('create trigger invalidate_daily_analysis before update or delete on public.%I for each row execute function private.invalidate_health_analysis()', health_table);
    end loop;
end;
$$;

alter table public.health_sync_runs drop constraint health_sync_runs_mode_check;
alter table public.health_sync_runs add constraint health_sync_runs_mode_check
    check(sync_mode in ('app_open', 'manual_refresh', 'foreground', 'background'));
