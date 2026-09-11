"""배포 진단을 허용 목록으로 제한한다. 로그 원문은 저장하지 않는다. 작성자: 김진우."""

import datetime
import json
import os
import re
import stat
import subprocess
import sys
import time
import uuid
from pathlib import Path

STORE = Path('/var/log/heapy-deploy-diagnostics')
MAX_FILES = 10
MAX_AGE = 7 * 86400
STAGES = {'rename_previous', 'stop_previous', 'start_new', 'check_health'}
STATE_FORMAT = ('{"status":{{json .State.Status}},"exit_code":{{.State.ExitCode}},'
                '"oom_killed":{{.State.OOMKilled}},"restart_count":{{.RestartCount}}}')


def run(args, timeout=4, merge_error=False):
    """원문 오류를 호출자나 공개 로그에 노출하지 않는다."""
    try:
        result = subprocess.run(args, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT if merge_error else subprocess.DEVNULL,
                                timeout=timeout, check=False)
        return result.returncode, result.stdout[:262144].decode('utf-8', errors='replace')
    except subprocess.TimeoutExpired:
        return 124, ''
    except OSError:
        return 127, ''


def state():
    code, output = run(['docker', 'inspect', '--format', STATE_FORMAT, 'heapy-backend'])
    if code:
        return {'query_exit': code}
    try:
        value = json.loads(output)
        return {
            'status': value['status'] if value['status'] in
            {'created', 'running', 'paused', 'restarting', 'removing', 'exited', 'dead'} else 'unknown',
            'exit_code': int(value['exit_code']),
            'oom_killed': value['oom_killed'] is True,
            'restart_count': int(value['restart_count']),
        }
    except (ValueError, KeyError, TypeError):
        return {'query_exit': 65}


def startup_summary(output):
    # 예외 메시지·SQL·URL·사용자 값은 버리고 알려진 패키지의 예외 클래스만 추출한다.
    classes = re.findall(
        r'\b((?:java|javax|jakarta|org\.springframework|org\.hibernate|org\.postgresql|'
        r'com\.zaxxer|com\.heapy)(?:\.[A-Za-z_$][\w$]*)*\.[A-Z][\w$]*(?:Exception|Error))\b',
        output,
    )
    return {'exception_classes': sorted({value for value in classes if len(value) <= 160})[:30],
            'application_failed_marker': 'APPLICATION FAILED TO START' in output}


def protected_store(root=STORE):
    if root.is_symlink() or root.resolve() != root.absolute():
        raise ValueError('진단 경로에 심볼릭 링크가 있습니다.')
    root.mkdir(mode=0o700, exist_ok=True)
    info = root.stat()
    if info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != 0o700:
        raise ValueError('진단 디렉터리 소유권 또는 권한이 다릅니다.')
    return root


def prune(root=STORE, reserve=0, now=None):
    """전용 디렉터리의 본 도구가 만든 일반 파일만 비재귀적으로 정리한다."""
    if not root.exists():
        return
    protected_store(root)
    now = time.time() if now is None else now
    entries = []
    for path in root.iterdir():
        if not re.fullmatch(r'failure-[0-9a-f]{32}\.json', path.name):
            continue
        info = path.lstat()
        if stat.S_ISREG(info.st_mode) and info.st_uid == os.geteuid():
            entries.append((info.st_mtime, path))
    entries.sort(reverse=True)
    for index, (modified, path) in enumerate(entries):
        if now - modified > MAX_AGE or index >= MAX_FILES - reserve:
            path.unlink()


def capture(stage, health_http, health_curl, health_state, new_attempted, root=STORE):
    if stage not in STAGES or not re.fullmatch(r'\d{3}', health_http):
        raise ValueError('허용되지 않은 진단 단계입니다.')
    if not health_curl.isdigit() or health_state not in {'UP', 'DOWN', 'OUT_OF_SERVICE', 'UNKNOWN', 'INVALID', 'UNTESTED'}:
        raise ValueError('허용되지 않은 헬스 상태입니다.')
    root = protected_store(root)
    prune(root, reserve=1)
    data = {'time_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'stage': stage, 'health_http': health_http, 'health_curl_exit': int(health_curl),
            'health_state': health_state}
    # 이전 컨테이너 정지 실패 시에는 정상 앱의 요청 로그를 읽지 않는다.
    if new_attempted:
        data['container'] = state()
        code, output = run(['docker', 'logs', '--tail', '200', 'heapy-backend'], merge_error=True)
        data['startup_log_query_exit'] = code
        data['startup'] = startup_summary(output)
    path = root / ('failure-' + uuid.uuid4().hex + '.json')
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w', encoding='utf-8') as stream:
        json.dump(data, stream, ensure_ascii=False)
    # 공개 출력은 사용자 값이 없는 고정 상태만 사용한다.
    print('HEAPY_DIAG snapshot=saved')


def journal_summary(unit_args):
    code, output = run(['journalctl', *unit_args, '--utc', '--since', '2026-09-07 08:21:00 UTC',
                        '--until', '2026-09-07 08:26:00 UTC', '--no-pager', '-o', 'json'], timeout=8)
    result = {'query_exit': code, 'entries': 0, 'oom_markers': 0, 'error_markers': 0,
              'output_limit_reached': len(output.encode('utf-8')) >= 262144,
              'note': '조회 가능한 보존 기록만 집계하며 원문은 출력하지 않습니다.'}
    for line in output.splitlines():
        try:
            message = json.loads(line).get('MESSAGE', '')
        except ValueError:
            continue
        if not isinstance(message, str):
            continue
        result['entries'] += 1
        result['oom_markers'] += bool(re.search(r'out of memory|oom-kill|killed process', message, re.I))
        result['error_markers'] += bool(re.search(r'error|failed|fatal', message, re.I))
    return result


def inspect():
    data = {'container': state(),
            'kernel_window': journal_summary(['-k']),
            'docker_window': journal_summary(['-u', 'docker.service'])}
    code, output = run(['docker', 'events', '--since', '2026-09-07T08:21:00Z',
                        '--until', '2026-09-07T08:26:00Z', '--filter', 'type=container',
                        '--format', '{{json .}}'], timeout=8)
    events = []
    for line in output.splitlines():
        try:
            event = json.loads(line)
            attrs = event.get('Actor', {}).get('Attributes', {})
            if attrs.get('name') not in {'heapy-backend', 'heapy-backend-rollback'}:
                continue
            action = event.get('Action', '')
            if action not in {'create', 'start', 'stop', 'die', 'kill', 'oom', 'destroy', 'rename', 'restart'}:
                continue
            item = {'action': action, 'time': int(event['time'])}
            if str(attrs.get('exitCode', '')).isdigit():
                item['exit_code'] = int(attrs['exitCode'])
            events.append(item)
        except (ValueError, KeyError, TypeError):
            continue
    data['docker_events'] = {'query_exit': code, 'events': events[:100],
                             'note': '과거 이벤트가 보존되지 않을 수 있으므로 빈 결과는 장애 부재의 증거가 아닙니다.'}
    try:
        memory = {}
        for line in Path('/proc/meminfo').read_text().splitlines():
            key, value = line.split(':', 1)
            if key in {'MemTotal', 'MemAvailable', 'SwapTotal', 'SwapFree'}:
                memory[key] = int(value.strip().split()[0])
        data['current_memory_kib'] = memory
        disk = os.statvfs('/')
        data['current_disk_available_bytes'] = disk.f_bavail * disk.f_frsize
    except (OSError, ValueError):
        data['current_resources'] = '조회 불가'
    print(json.dumps(data, ensure_ascii=False))


def main():
    if os.geteuid() != 0:
        raise ValueError('관리자 실행이 필요합니다.')
    args = sys.argv[1:]
    if args == ['inspect']:
        inspect()
    elif args == ['prune']:
        prune()
    elif len(args) == 6 and args[0] == 'capture' and args[5] in {'0', '1'}:
        capture(*args[1:5], args[5] == '1')
    else:
        raise ValueError('허용되지 않은 진단 명령입니다.')


if __name__ == '__main__':
    try:
        main()
    except Exception:
        # 예외 메시지나 수집 도중의 원문을 공개하지 않는다.
        print('HEAPY_DIAG snapshot=failed', file=sys.stderr)
        sys.exit(1)
