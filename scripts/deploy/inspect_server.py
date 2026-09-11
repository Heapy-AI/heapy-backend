"""배포 없이 지정 서버의 진단 요약만 조회한다. 작성자: 김진우."""

import base64
import json
import os
import time
from pathlib import Path
import subprocess

from send_command import aws


def parameters():
    payload = base64.b64encode(Path(__file__).with_name('diagnostics.py').read_bytes()).decode()
    # 임시 파일 설치조차 하지 않고 표준 입력으로 읽기 전용 진단 코드만 실행한다.
    command = f"printf '%s' '{payload}' | base64 -d | timeout 45 python3 - inspect"
    return {'commands': ['set -eu', command], 'executionTimeout': ['60']}


def main():
    instance = os.environ['EC2_INSTANCE_ID']
    if instance != 'i-055b8632e5b93fd96':
        raise ValueError('승인된 EC2가 아닙니다.')
    value = aws('ssm', 'send-command', '--instance-ids', instance,
                '--document-name', 'AWS-RunShellScript', '--timeout-seconds', '60',
                '--parameters', json.dumps(parameters()), '--comment', 'HEAPY 읽기 전용 배포 진단')
    command_id = value['Command']['CommandId']
    print('진단 명령 ID:', command_id, flush=True)
    deadline = time.monotonic() + 150
    while time.monotonic() < deadline:
        try:
            result = aws('ssm', 'get-command-invocation', '--instance-id', instance,
                         '--command-id', command_id)
        except subprocess.CalledProcessError as error:
            if 'InvocationDoesNotExist' not in error.stderr:
                raise
            time.sleep(5)
            continue
        if result['Status'] == 'Success':
            # diagnostics.py가 자유 형식 로그를 제거하고 만든 요약 JSON만 출력한다.
            print(json.dumps(json.loads(result['StandardOutputContent']), ensure_ascii=False))
            return
        if result['Status'] not in {'Pending', 'InProgress', 'Delayed'}:
            raise RuntimeError('진단 조회 실패. 서비스 상태는 변경하지 않았습니다.')
        time.sleep(5)
    raise RuntimeError('진단 조회 제한 시간 초과.')


if __name__ == '__main__':
    try:
        main()
    except Exception:
        raise SystemExit('진단 조회 실패. 인증 정보나 서버 로그 원문은 출력하지 않습니다.') from None
