-- 작성자: 김진우 — 원본 재수신·삭제 재생 방지와 설치별 완료 지점을 보존한다.
create table private.health_sync_versions (
    user_id uuid not null references public.users(user_id) on delete cascade,
    metric text not null check (metric in ('sleep','bio','activity','exercise','nutrition','water')),
    external_record_id text not null,
    source_updated_at timestamptz not null,
    deleted boolean not null default false,
    primary key (user_id,metric,external_record_id)
);
create table private.health_sync_checkpoints (
    connection_id uuid not null references public.health_data_connections(connection_id) on delete cascade,
    data_type text not null check (data_type in ('sleep','heart_rate','blood_glucose','blood_pressure','body_composition','exercise','activity','water','nutrition')),
    through_at timestamptz not null,
    primary key (connection_id,data_type)
);
alter table private.health_sync_versions enable row level security;
alter table private.health_sync_checkpoints enable row level security;
revoke all on private.health_sync_versions,private.health_sync_checkpoints from public,anon,authenticated;
grant select,insert,update,delete on private.health_sync_versions,private.health_sync_checkpoints to service_role;
alter table public.health_sync_runs add column deleted_count integer not null default 0 check(deleted_count>=0);
-- 작성자: 김진우 — SDK가 제공하지 않은 항목을 활동량 0으로 오인하지 않는다.
alter table public.lifestyle_activity
    alter column steps drop not null, alter column steps drop default,
    alter column floors drop not null, alter column floors drop default,
    alter column active_time_minutes drop not null, alter column active_time_minutes drop default,
    alter column distance_m drop not null, alter column distance_m drop default,
    alter column active_calories_kcal drop not null, alter column active_calories_kcal drop default;
-- 작성자: 김진우 — 기존 자료를 삭제하거나 병합하지 않으며 조회 인덱스만 추가한다.
create index if not exists idx_sleep_external on public.lifestyle_sleep(user_id,external_record_id) where external_record_id is not null;
create index if not exists idx_bio_external on public.lifestyle_bio(user_id,external_record_id) where external_record_id is not null;
create index if not exists idx_exercise_external on public.lifestyle_exercise(user_id,external_record_id) where external_record_id is not null;
create index if not exists idx_nutrition_external on public.lifestyle_nutrition(user_id,external_record_id) where external_record_id is not null;
create index if not exists idx_water_external on public.lifestyle_water_intake(user_id,external_record_id) where external_record_id is not null;

-- 작성자: 김진우 — 같은 수치를 다시 받은 동기화 메타데이터만으로 당일 분석을 무효화하지 않는다.
create or replace function private.invalidate_health_analysis() returns trigger
language plpgsql security invoker set search_path = '' as $$
declare analysis_day date := (clock_timestamp() at time zone 'Asia/Seoul')::date;
begin
    if tg_op = 'UPDATE' then
        if (to_jsonb(old) - array['updated_at','source_updated_at','sync_run_id','external_record_id','is_user_override'])
           is not distinct from
           (to_jsonb(new) - array['updated_at','source_updated_at','sync_run_id','external_record_id','is_user_override']) then
            return new;
        end if;
    end if;
    if old.created_at < (analysis_day::timestamp at time zone 'Asia/Seoul')
       and exists (select 1 from public.users where user_id=old.user_id) then
        insert into private.health_analysis_invalidations(user_id,analysis_date)
        values(old.user_id,analysis_day) on conflict do nothing;
    end if;
    if tg_op = 'DELETE' then return old; end if;
    return new;
end;
$$;
revoke all on function private.invalidate_health_analysis() from public,anon,authenticated;
