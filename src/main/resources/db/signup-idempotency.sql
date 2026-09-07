-- 작성자: 김진우
-- 회원가입 성공 응답을 재사용하기 위한 서버 전용 저장소. 기존 사용자 데이터는 변경하지 않는다.
create schema if not exists private;
create table if not exists private.signup_requests (
    request_key uuid primary key,
    request_hash text not null,
    user_id uuid,
    verification_required boolean not null default true,
    created_at timestamptz not null default now()
);
revoke all on private.signup_requests from public, anon, authenticated;
alter table private.signup_requests enable row level security;
