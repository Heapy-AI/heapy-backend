"""실행 컨테이너의 DB 대상을 비밀 출력 없이 확인한다. 작성자: 김진우."""

import argparse
import json
import re
import subprocess
from urllib.parse import urlsplit


def inspect(values, expected_ref):
    if not re.fullmatch('[a-z]{20}', expected_ref):
        raise ValueError('프로젝트 식별자 형식 오류입니다.')
    jdbc = values.get('DATABASE_URL', '')
    if not jdbc.startswith('jdbc:postgresql://'):
        raise ValueError('DB 접속 형식을 확인하세요.')
    url = urlsplit(jdbc.removeprefix('jdbc:'))
    username = values.get('DATABASE_USERNAME', '')
    host = url.hostname or ''
    direct = re.fullmatch(r'db\.([a-z]{20})\.supabase\.co', host)
    pooled = re.fullmatch(r'([a-z_][a-z0-9_]{0,62})\.([a-z]{20})', username)
    role = username
    db_ref = None
    if direct:
        db_ref = direct.group(1)
    elif host.endswith('.pooler.supabase.com') and pooled:
        role, db_ref = pooled.groups()
    if not re.fullmatch(r'[a-z_][a-z0-9_]{0,62}', role):
        role = None
    auth_host = urlsplit(values.get('SUPABASE_URL', '')).hostname
    return {
        'databaseProjectMatches': db_ref == expected_ref,
        'databaseProjectIdentified': db_ref is not None,
        'databaseRole': role,
        'authProjectMatches': auth_host == f'{expected_ref}.supabase.co',
        'jdbcCredentialsEmbedded': url.username is not None or url.password is not None,
    }


def main():
    parser = argparse.ArgumentParser(description='실행 DB 설정의 일치 여부와 역할명만 출력합니다.')
    parser.add_argument('--expected-project', required=True)
    args = parser.parse_args()
    command = ['docker', 'inspect', '--format', '{{json .Config.Env}}', 'heapy-backend']
    response = subprocess.run(command, capture_output=True, text=True, timeout=15, check=True)
    entries = json.loads(response.stdout)
    values = {}
    for entry in entries:
        key, separator, value = entry.partition('=')
        if separator:
            if key in values:
                raise ValueError('중복 환경변수입니다.')
            values[key] = value
    print(json.dumps(inspect(values, args.expected_project), ensure_ascii=False))


if __name__ == '__main__':
    try:
        main()
    except Exception:
        raise SystemExit('접속 대상 확인 실패. 비밀값·컨테이너 원문은 출력하지 않습니다.') from None
