-- 자동 제목으로 수동 제목을 덮어쓰지 않기 위한 표시. 작성자: 김진우
set local lock_timeout='5s';
set local statement_timeout='60s';
set local idle_in_transaction_session_timeout='60s';
alter table public.chat_sessions add column title_manually_edited boolean not null default true;
-- 기존 세션의 수동 편집 이력은 없으므로 기존 제목을 보호한다. 작성자: 김진우
alter table public.chat_sessions alter column title_manually_edited set default false;
comment on column public.chat_sessions.title_manually_edited is '사용자가 제목 변경 API로 지정했는지 여부. 자동 제목은 덮어쓰지 않는다. 작성자: 김진우';
