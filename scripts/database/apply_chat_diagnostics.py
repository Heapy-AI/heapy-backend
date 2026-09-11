"""승인된 상담 진단 테이블만 트랜잭션으로 적용한다. 작성자: 김진우."""
import argparse
import hashlib
import json
from verify_findings_release import ROOT, target_env, sql

VERSION = '20260909075748'

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--sha256', required=True)
    args = parser.parse_args()
    data = (ROOT / f'supabase/migrations/{VERSION}_chat_diagnostics.sql').read_bytes()
    if hashlib.sha256(data).hexdigest() != args.sha256.lower():
        raise ValueError('해시 불일치')
    env = target_env()
    state = json.loads(sql(env, """select json_build_object('role',current_user,
        'exists',to_regclass('private.chat_diagnostics') is not null,
        'history',(select count(*) from supabase_migrations.schema_migrations where version='20260909075748'),
        'create',has_schema_privilege(current_user,'private','CREATE'),
        'historyInsert',has_table_privilege(current_user,'supabase_migrations.schema_migrations','INSERT'),
        'sessions',to_regclass('public.chat_sessions') is not null)"""))
    print(json.dumps(state))
    if state != dict(role='postgres', exists=False, history=0, create=True, historyInsert=True, sessions=True):
        raise ValueError('사전 조건 불일치')
    if args.apply:
        body = data.decode('utf-8')
        delimiter = '$chat_diagnostics_migration$'
        if delimiter in body:
            raise ValueError('구분자 충돌')
        query = 'begin;\n' + body + "\ninsert into supabase_migrations.schema_migrations(version,name,statements) values ('20260909075748','chat_diagnostics',ARRAY[" + delimiter + body + delimiter + "]);\ncommit;"
        sql(env, query)
        print('단일 마이그레이션 및 이력 트랜잭션 완료')

if __name__ == '__main__':
    try:
        main()
    except Exception:
        raise SystemExit('진단 마이그레이션 실패: 원문 비공개. 상태 재확인 전 재실행 금지.') from None
