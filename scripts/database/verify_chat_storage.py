"""공유 DB에서 합성 상담·출처·진단 저장을 시험하고 전부 롤백한다. 작성자: 김진우."""
import os
import re
import subprocess
from verify_findings_release import PSQL, target_env

def sql(env, query):
    result = subprocess.run([str(PSQL), '-X', '-v', 'ON_ERROR_STOP=1', '-v', 'VERBOSITY=verbose', '-At', '-f', '-'],
        input=query, env=dict(env, PGCLIENTENCODING='UTF8'), capture_output=True, text=True, encoding='utf-8', timeout=30)
    if result.returncode:
        match = re.search(r'ERROR:\s+([A-Z0-9]{5}):\s+CHAT_STAGE=([a-z_]+)', result.stderr)
        print('안전 진단: '+ ('sqlState='+match[1]+' stage='+match[2] if match else '알 수 없는 단계'))
        raise RuntimeError('합성 검사 실패')

def main():
    sql(target_env(), """
    begin;
    set local lock_timeout='5s';
    set local statement_timeout='15s';
    do $test$
    declare
        owner_id uuid;
        synthetic_session uuid := gen_random_uuid();
        synthetic_message uuid := gen_random_uuid();
        synthetic_attempt uuid := gen_random_uuid();
        current_stage text := 'owner';
    begin
        select user_id into owner_id from public.users where onboarding_completed_at is not null limit 1;
        if owner_id is null then raise exception '합성 시험에 필요한 부모 계정 없음'; end if;
        current_stage := 'session';
        insert into public.chat_sessions(session_id,user_id,title,companion_code)
            values(synthetic_session,owner_id,'합성 롤백 검증','heapy_cat');
        current_stage := 'message';
        insert into public.chat_messages(message_id,session_id,role,content,response_status,companion_code_snapshot)
            values(synthetic_message,synthetic_session,'assistant','합성 검증 답변','completed','heapy_cat');
        current_stage := 'citation';
        insert into public.chat_message_citations(message_id,display_order,source_type,source_title,source_url)
            values(synthetic_message,1,'rag','합성 근거','https://example.org/synthetic');
        current_stage := 'diagnostic';
        insert into private.chat_diagnostics(attempt_id,request_id,session_id,status,stage,error_code,elapsed_ms,details)
            values(synthetic_attempt,gen_random_uuid(),synthetic_session,'completed','done','none',1,'{"citationCount":1}');
        if (select count(*) from public.chat_message_citations where message_id=synthetic_message) <> 1 then
            raise exception '합성 출처 조회 실패'; end if;
        if (select count(*) from private.chat_diagnostics where attempt_id=synthetic_attempt) <> 1 then
            raise exception '합성 진단 조회 실패'; end if;
    exception when others then
        raise exception using message='CHAT_STAGE='||current_stage, errcode=sqlstate;
    end $test$;
    rollback;
    """)
    print('기존 IDENTITY 출처 INSERT·조회·진단 저장 통과. 합성 행 전체 롤백.')

if __name__ == '__main__':
    try:
        main()
    except Exception:
        raise SystemExit('공유 DB 합성 검증 실패. 트랜잭션은 롤백되며 오류 원문은 출력하지 않습니다.') from None
