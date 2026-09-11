-- 작성자: 김진우 — 복약 쓰기 재시도를 사용자·작업·내용별로 검증한다.
create table private.medication_requests (
    user_id uuid not null references public.users(user_id) on delete cascade,
    request_key uuid not null,
    request_hash text not null,
    response_body text not null,
    created_at timestamptz not null default now(),
    primary key(user_id, request_key)
);
alter table private.medication_requests enable row level security;
revoke all on private.medication_requests from public, anon, authenticated;
grant select, insert on private.medication_requests to service_role;

-- 작성자: 김진우 — 실제 DB의 active/archived 제약에 설계된 기간 종료 상태를 추가한다.
alter table public.user_medications drop constraint user_medications_status_check;
alter table public.user_medications add constraint user_medications_status_check
    check(status in ('active','completed','archived'));
