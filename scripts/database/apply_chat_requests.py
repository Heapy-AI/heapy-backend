"""승인된 상담 요청 테이블 한 건의 적용·이력을 묶는다. 작성자: 김진우."""
import argparse
import hashlib
import json
from pathlib import Path
from verify_findings_release import ROOT, target_env, sql

VERSION = '20260909043000'
EXPECTED = 'c17316084cbe29e5a216d96c83b44a335b58d108519cf9dc371ade61e00164a5'

def transaction(body):
    marker = '$heapy_chat_migration$'
    if marker in body:
        raise ValueError('인용 구분자 충돌')
    return "begin;\n" + body + "\ninsert into supabase_migrations.schema_migrations(version,name,statements) values ('20260909043000','chat_requests',ARRAY[" + marker + body + marker + "]);\ncommit;"

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    data = (ROOT / 'supabase/migrations/20260909043000_chat_requests.sql').read_bytes()
    if hashlib.sha256(data).hexdigest() != EXPECTED:
        raise ValueError('승인 SQL 해시 불일치')
    env = target_env()
    state = json.loads(sql(env, """select json_build_object('role',current_user,'session',session_user,
      'privateUsage',has_schema_privilege(current_user,'private','USAGE'),
      'privateCreate',has_schema_privilege(current_user,'private','CREATE'),
      'historyInsert',has_table_privilege(current_user,'supabase_migrations.schema_migrations','INSERT'),
      'bypass',(select rolbypassrls from pg_roles where rolname=current_user),
      'exists',to_regclass('private.chat_requests') is not null,
      'history',(select count(*) from supabase_migrations.schema_migrations where version='20260909043000' or name='chat_requests'),
      'waitingLocks',(select count(*) from pg_locks where not granted and relation in ('public.users'::regclass,'public.chat_sessions'::regclass)))"""))
    expected = dict(role='postgres',session='postgres',privateUsage=True,privateCreate=True,historyInsert=True,bypass=True,exists=False,history=0,waitingLocks=0)
    print(json.dumps(state))
    if state != expected:
        raise ValueError('사전 조건 불일치. 자동 재시도하지 않는다.')
    if args.apply:
        sql(env,transaction(data.decode('utf-8')))
        print('승인 SQL 및 이력 단일 트랜잭션 완료. 원격 재조회 필요.')

if __name__ == '__main__':
    try:
        main()
    except Exception:
        raise SystemExit('상담 마이그레이션 점검·적용 실패. 비밀값 비공개. 원격 상태 확인 전 재시도 금지.') from None
