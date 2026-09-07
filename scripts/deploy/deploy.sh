#!/usr/bin/env bash
# 작성자: 김진우 — 대상 한 대의 컨테이너 교체와 실패 복원을 수행한다.
set -Eeuo pipefail

APP=heapy-backend
BACKUP=heapy-backend-rollback
ENV_FILE=/opt/heapy/backend.env
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
HEALTH_ATTEMPTS=60

healthy() {
  local attempt
  for ((attempt=0; attempt<HEALTH_ATTEMPTS; attempt++)); do
    if curl --fail --silent --max-time 3 http://127.0.0.1:8080/actuator/health |
      python3 -c 'import json,sys; sys.exit(0 if json.load(sys.stdin).get("status") == "UP" else 1)' 2>/dev/null; then
      return 0
    fi
    sleep 2
  done
  return 1
}

rollback() {
  local code=$?
  trap - EXIT INT TERM
  if [[ ${DEPLOYING:-0} == 1 ]]; then
    echo '새 버전 배포 실패: 복원을 시작합니다.' >&2
    docker rm -f "$APP" >/dev/null 2>&1 || true
    if [[ ${HAD_PREVIOUS:-0} == 1 ]]; then
      if docker rename "$BACKUP" "$APP" && docker start "$APP" >/dev/null && healthy; then
        echo '이전 컨테이너 복원 완료.' >&2
      else
        echo '이전 컨테이너 복원 실패: 운영자 확인이 필요합니다.' >&2
      fi
    else
      echo '최초 배포 실패: 복원할 이전 컨테이너가 없습니다.' >&2
    fi
    exit 1
  fi
  exit "$code"
}

rollout() {
  HAD_PREVIOUS=0
  DEPLOYING=0
  # 남은 복원 컨테이너는 자동 삭제하지 않고 운영자가 상태를 확인하게 한다.
  if docker container inspect "$BACKUP" >/dev/null 2>&1; then
    echo '이전 복원 컨테이너가 남아 있습니다. 상태 확인 후 다시 배포하세요.' >&2
    return 1
  fi
  trap rollback EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  if docker container inspect "$APP" >/dev/null 2>&1; then
    docker rename "$APP" "$BACKUP"
    HAD_PREVIOUS=1
    DEPLOYING=1
    docker stop --time 30 "$BACKUP" >/dev/null
  else
    DEPLOYING=1
  fi
  docker run -d --name "$APP" --restart unless-stopped \
    --user 10001:10001 --read-only --tmpfs /tmp:rw,nosuid,noexec,size=256m \
    --cap-drop ALL --security-opt no-new-privileges:true \
    --memory 2g --cpus 1.5 --log-opt max-size=10m --log-opt max-file=3 \
    --env-file "$ENV_FILE" \
    -e SPRING_PROFILES_ACTIVE=deploy -e FLYWAY_ENABLED=false \
    -e SERVER_PORT=8080 -e HEAPY_ONBOARDING_REQUIRE_CONSENTS=true \
    -e SPRINGDOC_API_DOCS_ENABLED=false -e SPRINGDOC_SWAGGER_UI_ENABLED=false \
    -p 127.0.0.1:8080:8080 "$IMAGE" >/dev/null
  healthy
  DEPLOYING=0
  trap - EXIT INT TERM
  if [[ $HAD_PREVIOUS == 1 ]]; then
    docker rm "$BACKUP" >/dev/null || echo '배포는 성공했지만 이전 컨테이너 정리가 필요합니다.' >&2
  fi
  echo '배포 및 헬스 체크 성공.'
}

main() {
  [[ $EUID == 0 ]] || { echo 'SSM 관리자 실행이 필요합니다.' >&2; return 1; }
  IMAGE=${1:?이미지 주소 필요}
  REGION=${2:?리전 필요}
  [[ $REGION == ap-northeast-2 ]] || return 1
  [[ $IMAGE =~ ^577638373354\.dkr\.ecr\.ap-northeast-2\.amazonaws\.com/heapy-backend@sha256:[a-f0-9]{64}$ ]] || return 1
  for dependency in docker aws python3 curl flock; do command -v "$dependency" >/dev/null; done
  exec 9>/run/lock/heapy-backend-deploy.lock
  flock -n 9 || { echo '다른 배포가 실행 중입니다.' >&2; return 1; }
  [[ -f $ENV_FILE && ! -L $ENV_FILE ]] || { echo '서버 환경 파일이 없습니다.' >&2; return 1; }
  [[ $(stat -c '%u:%a' "$ENV_FILE") == 0:600 ]] || { echo '환경 파일은 root 소유, 권한 600이어야 합니다.' >&2; return 1; }
  python3 "$SCRIPT_DIR/validate_env.py" "$ENV_FILE"
  docker info >/dev/null
  # 기존 앱을 중단하기 전에 인증·다운로드를 완료한다.
  aws ecr get-login-password --region "$REGION" |
    docker login --username AWS --password-stdin "${IMAGE%%/*}" >/dev/null
  docker pull "$IMAGE" >/dev/null
  rollout
}

if [[ ${BASH_SOURCE[0]} == "$0" ]]; then main "$@"; fi
