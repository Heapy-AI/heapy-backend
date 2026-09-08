"""검토한 검진 마이그레이션의 실행 SQL만 생성한다. 작성자: 김진우."""

import argparse
import hashlib
from pathlib import Path

EXPECTED_SHA256 = '2ea475ab0c2ca604c8fd762e1923552d80cb08cc0f840f6a05bb7a62847b0686'


def prepare(path, expected_sha256, managed_transaction=False):
    """DB 접속 없이 생성한다. 기존 SQL이 달라지면 재검토하도록 중단한다."""
    data = Path(path).read_bytes()
    if hashlib.sha256(data).hexdigest() != expected_sha256.lower():
        raise ValueError('검토한 SQL 해시와 다릅니다.')
    body = data.decode('utf-8-sig')
    # 실행기가 트랜잭션을 관리하는 경우 BEGIN/COMMIT 중첩을 피한다.
    prefix = '' if managed_transaction else 'begin;\n'
    suffix = '' if managed_transaction else '\ncommit;\n'
    return prefix + """set local lock_timeout = '5s';
set local statement_timeout = '60s';
set local idle_in_transaction_session_timeout = '60s';
-- 두 테이블의 쓰기를 잠시 차단하고 이후 사전 조건과 DDL을 한 트랜잭션으로 수행한다.
lock table public.master_checkup_item, public.health_checkup_results
    in share row exclusive mode;
""" + body + suffix


def main():
    parser = argparse.ArgumentParser(description='DB에 접속하지 않고 검진 적용 SQL을 생성합니다.')
    parser.add_argument('--output', required=True)
    parser.add_argument('--managed-transaction', action='store_true',
                        help='적용 도구가 전체 트랜잭션을 보장할 때만 사용합니다.')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    migration = root / 'supabase/migrations/20260908085751_checkup_findings.sql'
    output = Path(args.output).resolve()
    if output == migration.resolve():
        raise ValueError('원본 마이그레이션을 덮어쓸 수 없습니다.')
    sql = prepare(migration, EXPECTED_SHA256, args.managed_transaction)
    with output.open('x', encoding='utf-8', newline='\n') as target:
        target.write(sql)
    print('적용 SQL 생성 완료. DB 적용과 마이그레이션 이력 등록은 실행하지 않았습니다.')


if __name__ == '__main__':
    main()
