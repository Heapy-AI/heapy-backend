-- 작성자: 김진우
-- 삼성 헬스 연결 등록 API의 사용자별 최초 성공 응답을 보관한다.
create table private.health_connection_requests (
    user_id uuid not null references public.users(user_id) on delete cascade,
    request_key uuid not null,
    request_hash text not null,
    response_status smallint not null check (response_status in (200, 201)),
    response_body text not null,
    created_at timestamptz not null default now(),
    primary key (user_id, request_key)
);
revoke all on private.health_connection_requests from public, anon, authenticated;
alter table private.health_connection_requests enable row level security;
