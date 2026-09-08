-- 작성자: 김진우
-- 원본·전체 OCR 결과·파일 경로 없이 요청 해시와 재시도·정리 메타데이터만 보관한다.
create table private.checkup_ocr_requests (
    user_id uuid not null references public.users(user_id) on delete cascade,
    operation text not null check (operation in ('upload', 'confirm')),
    request_key uuid not null,
    job_id uuid not null references public.ocr_jobs(job_id) on delete cascade,
    request_hash text not null,
    response_body text not null,
    source_extension text,
    source_size bigint,
    source_hash text,
    source_input_type text,
    cleanup_pending boolean not null default false,
    cleanup_attempts integer not null default 0,
    cleanup_retry_at timestamptz not null default now(),
    created_at timestamptz not null default now(),
    primary key (user_id, operation, request_key)
);
create unique index checkup_ocr_job_operation_uq on private.checkup_ocr_requests(job_id, operation);
create index ocr_jobs_active_expiry_idx on public.ocr_jobs(expires_at)
    where document_type = 'health_checkup' and status in ('pending', 'processing', 'review');
revoke all on private.checkup_ocr_requests from public, anon, authenticated;
alter table private.checkup_ocr_requests enable row level security;
