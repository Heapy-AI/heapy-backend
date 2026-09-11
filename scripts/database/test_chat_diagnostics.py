"""합성 전용 로컬 DB에서 진단 저장 제약을 검증한다. 작성자: 김진우."""
import os
import uuid
from verify_findings_release import ROOT, sql

def main():
    env = dict(os.environ, PGHOST='127.0.0.1', PGPORT='55439', PGUSER='postgres', PGPASSWORD='postgres',
               PGDATABASE='postgres', PGCONNECT_TIMEOUT='5')
    database = 'heapy_findings_cli_diagnostics_' + uuid.uuid4().hex[:8]
    sql(env, 'create database ' + database)
    env['PGDATABASE'] = database
    sql(env, 'create schema private; create table public.chat_sessions(session_id uuid primary key);')
    body = (ROOT / 'supabase/migrations/20260909075748_chat_diagnostics.sql').read_text(encoding='utf-8')
    sql(env, 'begin;\n'+body+'\nrollback;')
    assert sql(env,"select to_regclass('private.chat_diagnostics') is null") == 't'
    sql(env, 'begin;\n'+body+'\ncommit;')
    session, attempt = str(uuid.uuid4()), str(uuid.uuid4())
    sql(env, f"insert into public.chat_sessions values ('{session}')")
    insert = f"insert into private.chat_diagnostics(attempt_id,request_id,session_id,status,stage,error_code,elapsed_ms,details) values ('{attempt}','{attempt}','{session}','failed','save_conversation','database_failure',15,"
    sql(env, insert + "'{\"sqlState\":\"23502\"}');")
    for invalid in ['{"question":"synthetic-secret"}', '[1,2]']:
        try:
            sql(env, insert.replace(attempt, str(uuid.uuid4())) + "'" + invalid + "');")
        except RuntimeError:
            pass
        else:
            raise AssertionError('허용하지 않은 진단 데이터 저장')
    assert sql(env, "select has_table_privilege('authenticated','private.chat_diagnostics','SELECT') or has_table_privilege('anon','private.chat_diagnostics','INSERT')") == 'f'
    sql(env, 'begin; create table synthetic_rollback(value int); rollback;')
    assert sql(env,'select count(*) from private.chat_diagnostics') == '1'
    sql(env, f"delete from public.chat_sessions where session_id='{session}'")
    assert sql(env,'select count(*) from private.chat_diagnostics') == '0'
    print('통과: DDL 롤백, 적용, 진단 저장, 허용 목록, JSON 형식, 접근 제한, 별도 커밋 보존, 세션 삭제 연쇄')
    print('합성 로컬 DB: '+database)

if __name__ == '__main__':
    main()
