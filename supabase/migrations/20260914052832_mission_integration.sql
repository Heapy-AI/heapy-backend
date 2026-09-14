-- 작성자: 김진우 — 팀원 DEV 미션을 통합하며 배포 전 기존 홈 조회 계약을 보존한다.
set lock_timeout = '5s';

create table public.mission_templates (
    template_id uuid primary key default gen_random_uuid(),
    code text not null unique,
    title text not null,
    category text not null,
    description text not null,
    unit text not null,
    target_value integer not null check (target_value > 0),
    display_order smallint not null check (display_order > 0),
    is_active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
insert into public.mission_templates(code,title,category,description,unit,target_value,display_order) values
('WALK_AFTER_LUNCH','점심 후 20분 걷기','ACTIVITY','정오부터 오후 6시 사이에 기록된 걷기 시간을 모아요.','MINUTE',20,1),
('DRINK_WATER','물 8잔 마시기','HYDRATION','오늘의 물 섭취 기록을 모아요. 1잔은 250mL예요.','CUP',8,2),
('EASY_CYCLING','자전거 천천히 타기','ACTIVITY','오늘 기록된 자전거 운동 시간을 모아요.','MINUTE',30,3),
('MORNING_STRETCH','아침 스트레칭 10분','ACTIVITY','정오 전 기록된 스트레칭 시간을 모아요.','MINUTE',10,4),
('SLEEP_BEFORE_MIDNIGHT','자정 전 취침하기','SLEEP','오늘 일어난 수면 중 전날 저녁 자정 전에 잠든 기록을 확인해요.','COUNT',1,5);

-- 기존 사용자 미션 ID와 상담 FK를 그대로 보존한다. 이전 컬럼은 구버전 서버 호환용이다.
alter table public.user_missions
    add column mission_id uuid generated always as (user_mission_id) stored,
    add column template_id uuid references public.mission_templates(template_id),
    add column current_value integer not null default 0 check (current_value >= 0),
    add column completed_at timestamptz,
    add column feedback text check (feedback in ('EASY','JUST_RIGHT','HARD')),
    add constraint mission_feedback_completed check (feedback is null or completed_at is not null),
    add constraint mission_id_unique unique(mission_id),
    add constraint mission_template_day_unique unique(user_id,template_id,mission_date);
create index user_missions_template_idx on public.user_missions(template_id);
create index mission_user_date_idx on public.user_missions(user_id,mission_date);

-- 빈 구설계 테이블만 정리한다. 데이터가 생겼다면 삭제 대신 전체 트랜잭션을 중단한다.
do $$
declare relation text; populated boolean;
begin
    foreach relation in array array['equipped_items','user_inventory','shop_purchases','shop_items','coin_ledger'] loop
        if to_regclass('public.' || relation) is not null then
            execute format('lock table public.%I in access exclusive mode',relation);
            execute format('select exists(select 1 from public.%I)',relation) into populated;
            if populated then raise exception '데이터가 있는 게이미피케이션 테이블은 자동 삭제하지 않습니다: %',relation; end if;
            execute format('drop table public.%I',relation);
        end if;
    end loop;
end $$;

alter table public.mission_templates enable row level security;
revoke all on public.mission_templates from public,anon,authenticated;
grant select,insert,update on public.mission_templates to service_role;
alter table public.user_missions enable row level security;
revoke insert,update,delete on public.user_missions from anon,authenticated;
grant select,insert,update on public.user_missions to service_role;
comment on table public.mission_templates is '코인 보상 없는 일일 건강 미션 정의. 사용 중인 목표는 덮어쓰지 않는다.';
