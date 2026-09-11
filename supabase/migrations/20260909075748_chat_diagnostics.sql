-- 상담 진단 최소 정보 저장. 작성자: 김진우
set local lock_timeout='5s';
set local statement_timeout='60s';
set local idle_in_transaction_session_timeout='60s';
create table private.chat_diagnostics (
    attempt_id uuid primary key,
    request_id uuid not null,
    session_id uuid not null references public.chat_sessions(session_id) on delete cascade,
    status text not null check (status in ('started','completed','partial','failed','disconnected')),
    stage text not null check (stage in ('accepted','load_conversation','load_health_context','internal_request',
        'prepare_query','classify_intent','search_evidence','generate_answer','verify_answer',
        'summarize_conversation','validate_response','save_conversation','deliver','done')),
    error_code text not null check (error_code in ('none','internal_failure','database_failure','timeout',
        'connection_failed','invalid_response','upstream_http_error','upstream_failure','incomplete_stream')),
    elapsed_ms bigint not null check (elapsed_ms >= 0),
    http_status integer check (http_status between 100 and 599),
    details jsonb not null default '{}'::jsonb check (jsonb_typeof(details)='object' and octet_length(details::text)<=4096
        and details - array['personalContextAvailable','personalContextUsed','grounded','uncertain','guardTriggered','emergency',
        'documentCount','citationCount','failedCollectionCount','searchedCollectionCount','confidence',
        'intent','modelVersion','verificationMethod','evidenceStatus','auditStatus','sqlState']::text[] = '{}'::jsonb),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create index chat_diagnostics_request_idx on private.chat_diagnostics(request_id);
create index chat_diagnostics_created_idx on private.chat_diagnostics(created_at);
create index chat_diagnostics_session_idx on private.chat_diagnostics(session_id);
alter table private.chat_diagnostics enable row level security;
revoke all on private.chat_diagnostics from public, anon, authenticated, service_role;
comment on table private.chat_diagnostics is '상담 진단 정보. 본문 없이 14일 보관, 세션 삭제 시 함께 삭제. 작성자: 김진우';
