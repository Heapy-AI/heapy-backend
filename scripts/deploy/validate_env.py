"""배포 환경 파일을 값을 출력하지 않고 검증한다. 작성자: 김진우."""

import sys
import re
from pathlib import Path

REQUIRED = {
    "DATABASE_URL", "DATABASE_USERNAME", "DATABASE_PASSWORD",
    "SUPABASE_URL", "SUPABASE_ANON_KEY", "SUPABASE_JWT_ISSUER",
    "SUPABASE_JWK_SET_URI",
}
OPTIONAL = {"SUPABASE_JWT_AUDIENCE", "SUPABASE_SIGNUP_REDIRECT_URL",
            "CHAT_ENABLED", "CHAT_BASE_URL", "CHAT_INTERNAL_TOKEN",
            "PUSH_ENABLED", "FIREBASE_PROJECT_ID", "GOOGLE_APPLICATION_CREDENTIALS"}


def validate(path):
    values = {}
    for line in Path(path).read_text(encoding="utf-8-sig").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or key not in REQUIRED | OPTIONAL or key in values:
            raise ValueError("환경 파일에 미지원·중복 변수 또는 잘못된 형식이 있습니다.")
        if not value.strip() or value != value.strip() or value.startswith(("'", '"')):
            raise ValueError("환경 값은 비어 있거나 따옴표·앞뒤 공백으로 감싸면 안 됩니다.")
        values[key] = value
    if REQUIRED - values.keys():
        raise ValueError("필수 환경변수가 누락됐습니다.")
    if values.get("PUSH_ENABLED", "false") not in {"true", "false"}:
        raise ValueError("푸시 활성화 값은 true 또는 false여야 합니다.")
    if values.get("PUSH_ENABLED") == "true":
        if not re.fullmatch(r"[a-z][a-z0-9-]{4,28}[a-z0-9]", values.get("FIREBASE_PROJECT_ID", "")):
            raise ValueError("Firebase 프로젝트 ID가 필요합니다.")
        if values.get("GOOGLE_APPLICATION_CREDENTIALS") != "/run/secrets/firebase-service-account.json":
            raise ValueError("지정된 서버 전용 푸시 자격 증명 경로가 필요합니다.")
    if values.get("CHAT_ENABLED", "false") not in {"true", "false"}:
        raise ValueError("챗봇 활성화 값은 true 또는 false여야 합니다.")
    if "CHAT_BASE_URL" in values and values["CHAT_BASE_URL"] != "http://heapy-fastapi:8000":
        raise ValueError("챗봇은 지정된 내부 컨테이너 주소만 사용합니다.")
    if values.get("CHAT_ENABLED") == "true" and len(values.get("CHAT_INTERNAL_TOKEN", "")) < 32:
        raise ValueError("챗봇 내부 인증 설정이 필요합니다.")
    if not values["DATABASE_URL"].startswith("jdbc:postgresql://"):
        raise ValueError("PostgreSQL JDBC 주소가 필요합니다.")
    base = values["SUPABASE_URL"].rstrip("/")
    if not base.startswith("https://") or values["SUPABASE_JWT_ISSUER"] != base + "/auth/v1":
        raise ValueError("인증 프로젝트 URL과 발급자 설정을 확인하세요.")
    if values["SUPABASE_JWK_SET_URI"] != base + "/auth/v1/.well-known/jwks.json":
        raise ValueError("인증 프로젝트의 JWKS 주소를 확인하세요.")


if __name__ == "__main__":
    try:
        validate(sys.argv[1])
    except (ValueError, OSError):
        sys.exit("서버 환경 파일 검증 실패: 필수 변수·형식·인증 URL을 확인하세요. 값은 출력하지 않습니다.")
