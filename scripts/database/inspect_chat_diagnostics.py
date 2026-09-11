"""개인 원문 없이 최근 상담 진단만 조회한다. 작성자: 김진우."""
import argparse
import uuid
from verify_findings_release import sql, target_env

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--request-id', type=uuid.UUID)
    args = parser.parse_args()
    where = "created_at > now()-interval '2 hours'"
    if args.request_id:
        where = "request_id='"+str(args.request_id)+"'::uuid"
    query = """begin read only; set local statement_timeout='5s';
        select coalesce(json_agg(t),'[]'::json) from
        (select request_id,status,stage,error_code,elapsed_ms,http_status,details,created_at,updated_at
        from private.chat_diagnostics where """+where+" order by created_at desc limit 20) t; rollback;"
    for line in sql(target_env(),query).splitlines():
        if line.startswith('['):
            print(line)

if __name__ == '__main__':
    try:
        main()
    except Exception:
        raise SystemExit('진단 조회 실패. 접속 정보와 오류 원문은 출력하지 않습니다.') from None
