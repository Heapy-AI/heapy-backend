"""검증한 배포 스크립트를 SSM으로 전달한다. 작성자: 김진우."""

import base64
import json
import os
import re
import shlex
import subprocess
import time
from pathlib import Path


def aws(*args):
    result = subprocess.run(
        ["aws", *args, "--region", "ap-northeast-2", "--output", "json"],
        capture_output=True, text=True, check=True,
    )
    return json.loads(result.stdout)


def parameters(image):
    if not re.fullmatch(
        r"577638373354\.dkr\.ecr\.ap-northeast-2\.amazonaws\.com/heapy-backend@sha256:[a-f0-9]{64}", image
    ):
        raise ValueError("허용된 저장소의 이미지 다이제스트가 필요합니다.")
    commands = [
        "set -eu", "umask 077", "deploy_dir=$(mktemp -d /run/heapy-deploy.XXXXXX)",
        "trap 'rm -f -- \"$deploy_dir/deploy.sh\" \"$deploy_dir/validate_env.py\"; rmdir -- \"$deploy_dir\"' EXIT",
    ]
    for name in ("deploy.sh", "validate_env.py"):
        payload = base64.b64encode(Path(__file__).with_name(name).read_bytes()).decode()
        commands.append(f"printf '%s' '{payload}' | base64 -d > \"$deploy_dir/{name}\"")
    commands.append(f'timeout --signal=TERM 900 bash "$deploy_dir/deploy.sh" {shlex.quote(image)} ap-northeast-2')
    return {"commands": commands, "executionTimeout": ["960"]}


def main():
    instance = os.environ["EC2_INSTANCE_ID"]
    if instance != "i-055b8632e5b93fd96":
        raise ValueError("승인된 개발 인스턴스가 아닙니다.")
    response = aws(
        "ssm", "send-command", "--instance-ids", instance,
        "--document-name", "AWS-RunShellScript", "--timeout-seconds", "60",
        "--parameters", json.dumps(parameters(os.environ["DEPLOY_IMAGE"])),
        "--comment", "HEAPY 개발 백엔드 배포",
    )
    command_id = response["Command"]["CommandId"]
    print(f"SSM 배포 명령 ID: {command_id}", flush=True)
    deadline = time.monotonic() + 1100
    while time.monotonic() < deadline:
        try:
            invocation = aws("ssm", "get-command-invocation", "--command-id", command_id, "--instance-id", instance)
        except subprocess.CalledProcessError as error:
            if "InvocationDoesNotExist" not in error.stderr:
                raise
            time.sleep(5)
            continue
        status = invocation["Status"]
        if status == "Success":
            print("EC2 배포 및 헬스 체크 성공.")
            return
        if status not in {"Pending", "InProgress", "Delayed", "Cancelling"}:
            # 서버 출력에는 민감한 정보가 있을 수 있어 Actions에 복제하지 않는다.
            raise RuntimeError(f"배포 실패({status}). SSM 명령 ID로 결과를 확인하세요.")
        time.sleep(5)
    raise RuntimeError("상태 조회 시간 초과. SSM 상태 확인 전 중복 배포하지 마세요.")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError:
        raise SystemExit("AWS 요청 실패: IAM 권한·리전·SSM 연결을 확인하세요.") from None
