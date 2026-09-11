"""상담 오류와 관련된 스키마·상태 집계만 읽는다. 작성자: 김진우."""
import json
from verify_findings_release import target_env, sql


def main():
    query = """
    begin read only;
    set local statement_timeout='15s';
    select json_build_object(
      'columns', (select json_agg(json_build_object('table',table_name,'column',column_name,'type',data_type))
        from information_schema.columns where table_schema='public' and table_name in
        ('chat_sessions','chat_messages','chat_message_citations','health_checkup_records','health_checkup_findings','lifestyle_activity')),
      'constraints', (select json_agg(json_build_object('table',conrelid::regclass::text,'definition',pg_get_constraintdef(oid)))
        from pg_constraint where conrelid in ('public.chat_messages'::regclass,'public.chat_message_citations'::regclass) and contype='c'),
      'recentRequestStates', (select json_agg(t) from (select state,count(*) as count,max(updated_at) as latest
        from private.chat_requests where scope like 'message:%%' and updated_at>now()-interval '2 hours' group by state) t)
    );
    rollback;
    """
    value=sql(target_env(), query)
    for line in value.splitlines():
        if line.startswith('{'):
            print(json.dumps(json.loads(line),ensure_ascii=False))


if __name__ == '__main__':
    try:
        main()
    except Exception:
        raise SystemExit('읽기 전용 진단 실패. 접속 정보·오류 원문은 출력하지 않습니다.') from None
