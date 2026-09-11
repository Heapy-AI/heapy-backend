"""대화 자동 제목 보조 컬럼과 이력을 함께 적용한다. 작성자: 김진우."""
import argparse
import hashlib
import json
from verify_findings_release import ROOT, target_env, sql

VERSION = '20260909135147'
PATH = ROOT / '.worktrees/backend-chat/supabase/migrations/20260909135147_chat_session_title.sql'
CHECK = """select json_build_object('role',current_user,
'columnExists',exists(select 1 from information_schema.columns where table_schema='public' and table_name='chat_sessions' and column_name='title_manually_edited'),
'history',(select count(*) from supabase_migrations.schema_migrations where version='20260909135147'),
'owner',(select pg_get_userbyid(relowner)=current_user from pg_class where oid='public.chat_sessions'::regclass),
'historyInsert',has_table_privilege(current_user,'supabase_migrations.schema_migrations','INSERT'))"""

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--apply',action='store_true')
    parser.add_argument('--sha256',required=True)
    args=parser.parse_args()
    data=PATH.read_bytes()
    if hashlib.sha256(data).hexdigest()!=args.sha256.lower(): raise ValueError('해시 불일치')
    env=target_env()
    state=json.loads(sql(env,CHECK))
    print(json.dumps(state))
    if state!=dict(role='postgres',columnExists=False,history=0,owner=True,historyInsert=True):
        raise ValueError('사전 조건 불일치')
    if args.apply:
        body=data.decode('utf-8')
        delim='$chat_title_migration$'
        if delim in body: raise ValueError('구분자 충돌')
        query="""begin;
set local lock_timeout='5s';
set local statement_timeout='60s';
set local idle_in_transaction_session_timeout='60s';
lock table public.chat_sessions in access exclusive mode;
do $$ begin
if exists(select 1 from information_schema.columns where table_schema='public' and table_name='chat_sessions' and column_name='title_manually_edited')
or exists(select 1 from supabase_migrations.schema_migrations where version='20260909135147') then
raise exception 'precondition_changed'; end if;
end $$;
"""+body+"\ninsert into supabase_migrations.schema_migrations(version,name,statements) values ('20260909135147','chat_session_title',ARRAY["+delim+body+delim+"]);\ncommit;"
        sql(env,query)
        print('단일 트랜잭션 적용 완료')

if __name__=='__main__':
    try: main()
    except Exception: raise SystemExit('제목 마이그레이션 실패: 원문 비공개. 재실행 전 상태를 확인하세요.') from None
