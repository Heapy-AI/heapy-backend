-- 작성자: 김진우 — 상담 요청의 재시도·취소·삭제 상태만 보관한다.
-- 공유 DB 미적용. 기존 상담 본문이나 검진 데이터를 이동하지 않는다.
-- 실행기가 단일 트랜잭션을 관리해야 한다. BEGIN/COMMIT을 중첩하지 않는다.
set local lock_timeout = '5s';
set local statement_timeout = '60s';
set local idle_in_transaction_session_timeout = '60s';
create table private.chat_requests (
    user_id uuid not null references public.users(user_id) on delete cascade,
    scope text not null,
    request_key uuid not null,
    request_hash text not null check (length(request_hash) = 64),
    state text not null check (state in ('started', 'completed', 'failed', 'deleted')),
    lease_id uuid not null,
    session_id uuid not null,
    user_message_id uuid,
    assistant_message_id uuid,
    expires_at timestamptz not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (user_id, scope, request_key)
);
create index chat_requests_active_session_idx
    on private.chat_requests(user_id, session_id, expires_at) where state = 'started';
revoke all on private.chat_requests from public, anon, authenticated;
alter table private.chat_requests enable row level security;
comment on table private.chat_requests is '상담 멱등성 식별자·상태. 건강 본문 미보관. 작성자: 김진우';
