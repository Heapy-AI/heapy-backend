-- 작성자: 김진우 — 원격 스키마 읽기 결과에 맞춘 로컬 전용 최소 구조다.
create schema private;
create role anon;
create role authenticated;
create role service_role;
create table users(user_id uuid primary key);
create table ocr_jobs(job_id uuid primary key,user_id uuid references users,document_type text,input_type text,status text,
 page_count smallint,error_code text,idempotency_key uuid,created_at timestamptz,updated_at timestamptz,expires_at timestamptz,
 started_at timestamptz,completed_at timestamptz,unique(user_id,idempotency_key));
create table private.checkup_ocr_requests(user_id uuid,operation text,request_key uuid,job_id uuid,request_hash text,response_body text,
 source_extension text,source_size bigint,source_hash text,source_input_type text,cleanup_pending boolean default false,
 cleanup_attempts integer default 0,cleanup_retry_at timestamptz default now(),primary key(user_id,operation,request_key));
create table user_medications(medication_id uuid primary key default gen_random_uuid(),user_id uuid references users,
 display_name text not null,dose_amount numeric(10,3),dose_unit text,dosage_text text not null,instructions text,
 start_date date not null,end_date date,status text default 'active',registration_source text default 'manual',source_ocr_job_id uuid references ocr_jobs,
 created_at timestamptz default now(),updated_at timestamptz default now(),
 constraint user_medications_status_check check(status in ('active','archived')),check(end_date is null or end_date>=start_date));
create table medication_schedules(schedule_id uuid primary key default gen_random_uuid(),medication_id uuid references user_medications,
 scheduled_time time not null,is_active boolean default true,created_at timestamptz default now(),updated_at timestamptz default now(),unique(medication_id,scheduled_time));
create table medication_intakes(intake_id uuid primary key default gen_random_uuid(),user_id uuid references users,medication_id uuid references user_medications,
 schedule_id uuid references medication_schedules,scheduled_at timestamptz not null,medication_name_snapshot text not null,dosage_snapshot text not null,
 status text default 'pending' check(status in ('pending','taken','skipped','missed')),action_source text,acted_at timestamptz,missed_at timestamptz,idempotency_key text,
 created_at timestamptz default now(),updated_at timestamptz default now(),unique(medication_id,scheduled_at),unique(user_id,idempotency_key));
create table medication_ocr_results(medication_ocr_result_id uuid primary key default gen_random_uuid(),ocr_job_id uuid references ocr_jobs,
 item_order smallint not null check(item_order>0),raw_name text,raw_dosage text,normalized_name text,normalized_dosage jsonb not null default '{}',
 confidence numeric check(confidence is null or (confidence>=0 and confidence<=1)),confirmed_data jsonb,confirmed_at timestamptz,
 medication_id uuid references user_medications,created_at timestamptz default now(),unique(ocr_job_id,item_order));
