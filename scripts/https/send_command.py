"""지정 EC2에 승인된 HTTPS 설정만 전달한다. 작성자: 김진우."""

import base64
import json
import os
import re
import subprocess
import time
from pathlib import Path

FILES = (
    "setup.sh", "images.sh", "nginx-http.conf", "nginx-https.conf",
    "issue-certificate.sh", "renew-certificate.sh", "probe.txt",
    "heapy-certbot-renew.service", "heapy-certbot-renew.timer",
)


def parameters(mode):
    if mode not in {"inspect", "bootstrap", "activate", "repair"}:
        raise ValueError("허용되지 않은 HTTPS 작업입니다.")
    commands = ["set -eu", "umask 077", "setup_dir=$(mktemp -d /run/heapy-https.XXXXXX)"]
    targets = " ".join(f'"$setup_dir/{name}"' for name in FILES)
    commands.append(f"trap 'rm -f -- {targets}; rmdir -- \"$setup_dir\"' EXIT")
    for name in FILES:
        payload = base64.b64encode(Path(__file__).with_name(name).read_bytes()).decode()
        commands.append(f"printf '%s' '{payload}' | base64 -d > \"$setup_dir/{name}\"")
    commands.append(f'timeout --signal=TERM 900 bash "$setup_dir/setup.sh" {mode}')
    return {"commands": commands, "executionTimeout": ["960"]}


def aws(*args):
    result = subprocess.run(
        ["aws", *args, "--region", "ap-northeast-2", "--output", "json"],
        capture_output=True, text=True, check=True,
    )
    return json.loads(result.stdout)


def main():
    mode = os.environ["HTTPS_MODE"]
    instance = os.environ["EC2_INSTANCE_ID"]
    if instance != "i-055b8632e5b93fd96":
        raise ValueError("승인된 EC2가 아닙니다.")
    result = aws("ssm", "send-command", "--instance-ids", instance,
                 "--document-name", "AWS-RunShellScript", "--timeout-seconds", "60",
                 "--parameters", json.dumps(parameters(mode)), "--comment", "HEAPY HTTPS 설정 " + mode)
    command_id = result["Command"]["CommandId"]
    print("HTTPS 설정 명령 ID:", command_id, flush=True)
    deadline = time.monotonic() + 1100
    while time.monotonic() < deadline:
        try:
            result = aws("ssm", "get-command-invocation", "--instance-id", instance, "--command-id", command_id)
        except subprocess.CalledProcessError as error:
            if "InvocationDoesNotExist" not in error.stderr:
                raise
            time.sleep(5)
            continue
        status = result["Status"]
        if status == "Success":
            # inspect는 포트·컨테이너 이름·파일 존재 여부만 출력한다.
            if mode == "inspect":
                print(result.get("StandardOutputContent", ""))
                diagnostic_id = os.environ.get("DIAGNOSTIC_COMMAND_ID", "")
                if diagnostic_id:
                    if not re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", diagnostic_id):
                        raise ValueError("잘못된 진단 명령 ID입니다.")
                    previous = aws("ssm", "get-command-invocation", "--instance-id", instance, "--command-id", diagnostic_id)
                    print("이전 배포 상태:", previous["Status"])
                    # 환경 값을 출력하지 않는 HEAPY 배포 스크립트의 알려진 오류만 추린다.
                    for line in (previous.get("StandardOutputContent", "") + "\n" + previous.get("StandardErrorContent", "")).splitlines():
                        if any(message in line for message in (
                            "새 버전 배포 실패", "이전 컨테이너 복원", "최초 배포 실패",
                            "환경 파일", "docker: Error", "Error response from daemon",
                            "permission denied", "Permission denied", "unbound variable",
                            "nginx:", "curl:", "manifest unknown", "not found", "Error:",
                            "failed", "Failed", "실패", "중단", "존재합니다",
                        )):
                            print(line[:500])
            print("HTTPS 작업 성공:", mode)
            return
        if status not in {"Pending", "InProgress", "Delayed", "Cancelling"}:
            raise RuntimeError(f"HTTPS 작업 실패: {status}. SSM 명령 ID로 확인하세요.")
        time.sleep(5)
    raise RuntimeError("HTTPS 작업 조회 시간 초과. SSM 상태를 확인하세요.")


if __name__ == "__main__":
    main()
