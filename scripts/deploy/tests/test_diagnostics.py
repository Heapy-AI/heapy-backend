"""진단 정보 제한과 보존 정책을 검증한다. 작성자: 김진우."""

import json
import os
import tempfile
import unittest
import subprocess
import sys
from pathlib import Path
from unittest.mock import patch

from test_deployment import module


class DiagnosticsTest(unittest.TestCase):
    def test_messages_tokens_and_urls_are_not_retained(self):
        tool = module('diagnostics')
        result = tool.startup_summary('''APPLICATION FAILED TO START
java.lang.OutOfMemoryError: secret-password
org.postgresql.util.PSQLException: jdbc:postgresql://private-host
Authorization: Bearer private-token
건강정보 원문
''')
        self.assertTrue(result['application_failed_marker'])
        self.assertEqual(result['exception_classes'],
                         ['java.lang.OutOfMemoryError', 'org.postgresql.util.PSQLException'])
        for secret in ('secret-password', 'private-host', 'private-token', '건강정보'):
            self.assertNotIn(secret, json.dumps(result))

    def test_only_exact_safe_lines_can_reach_actions(self):
        tool = module('send_command')
        safe = 'HEAPY_DIAG stage=check_health http=503 curl=0 health=DOWN'
        result = tool.safe_diagnostics('\n'.join((safe, 'HEAPY_DIAG snapshot=saved',
                    safe + ' secret-token', 'Error secret-password', 'HEAPY_DIAG stage=private')))
        self.assertEqual(result, [safe, 'HEAPY_DIAG snapshot=saved'])

    def test_invalid_container_state_does_not_echo_raw_data(self):
        tool = module('diagnostics')
        with patch.object(tool, 'run', return_value=(0, 'private-token')):
            self.assertEqual(tool.state(), {'query_exit': 65})

    def test_state_contains_only_allowed_fields(self):
        tool = module('diagnostics')
        value = {'status': 'exited', 'exit_code': 137, 'oom_killed': True,
                 'restart_count': 1, 'Env': ['PASSWORD=private']}
        with patch.object(tool, 'run', return_value=(0, json.dumps(value))):
            result = tool.state()
        self.assertTrue(result['oom_killed'])
        self.assertNotIn('Env', result)

    def test_snapshot_skips_logs_before_new_container_attempt(self):
        tool = module('diagnostics')
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            with patch.object(tool, 'protected_store', return_value=root), \
                    patch.object(tool, 'prune'), patch.object(tool, 'run') as run:
                tool.capture('stop_previous', '000', '0', 'UNTESTED', False, root)
                run.assert_not_called()
            data = json.loads(next(root.iterdir()).read_text())
            self.assertNotIn('startup', data)

    def test_rotation_is_bounded_and_does_not_delete_unrelated_files(self):
        tool = module('diagnostics')
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for index in range(12):
                path = root / f'failure-{index:032x}.json'
                path.write_text('{}')
                os.utime(path, (1000 + index, 1000 + index))
            unrelated = root / 'keep.txt'
            unrelated.write_text('keep')
            with patch.object(tool, 'protected_store', return_value=root), \
                    patch.object(os, 'geteuid', return_value=root.stat().st_uid, create=True):
                tool.prune(root, reserve=1, now=2000)
                self.assertEqual(len(list(root.glob('failure-*.json'))), 9)
                tool.prune(root, now=2000 + tool.MAX_AGE)
                self.assertEqual(list(root.glob('failure-*.json')), [])
            self.assertTrue(unrelated.exists())

    def test_journal_counts_do_not_disclose_messages(self):
        tool = module('diagnostics')
        text = json.dumps({'MESSAGE': 'Out of memory: private-token'})
        with patch.object(tool, 'run', return_value=(0, text)):
            result = tool.journal_summary(['-k'])
        self.assertEqual(result['oom_markers'], 1)
        self.assertNotIn('private-token', json.dumps(result))

    def test_retention_timer_is_daily(self):
        tool = module('diagnostics')
        root = Path(tool.__file__).parent
        self.assertIn('OnCalendar=daily', (root / 'heapy-deploy-diagnostics-prune.timer').read_text(encoding='utf-8'))
        self.assertIn('diagnostics.py prune', (root / 'heapy-deploy-diagnostics-prune.service').read_text(encoding='utf-8'))

    def test_health_probe_keeps_only_status_and_exit_code(self):
        from test_deployment import ROOT
        bash = os.environ['DEPLOY_TEST_BASH'] if 'DEPLOY_TEST_BASH' in os.environ else 'bash'
        script = r'''
source scripts/deploy/deploy.sh
HEALTH_ATTEMPTS=1
python3() { "$DIAG_TEST_PYTHON" "$@"; }
sleep() { :; }
curl() {
  case "$CASE" in
    up) printf '{"status":"UP","secret":"private-token"}\n200';;
    down) printf '{"status":"DOWN"}\n503';;
    invalid) printf 'private-token\n200';;
    disconnected) printf '\n000'; return 7;;
  esac
}
if healthy; then code=0; else code=$?; fi
printf '%s %s %s %s' "$code" "$HEALTH_HTTP" "$HEALTH_CURL" "$HEALTH_STATE"
'''
        for case, expected in (('up', '0 200 0 UP'), ('down', '1 503 0 DOWN'),
                               ('invalid', '1 200 0 INVALID'), ('disconnected', '1 000 7 INVALID')):
            with self.subTest(case=case):
                result = subprocess.run([bash, '-s'], input=script, capture_output=True,
                    text=True, cwd=ROOT, env={**os.environ, 'CASE': case,
                    'DIAG_TEST_PYTHON': sys.executable.replace('\\', '/')})
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, expected)

    def test_inspection_payload_has_no_deployment_command(self):
        tool_path = str(Path(module('diagnostics').__file__).parent)
        with patch.object(sys, 'path', [tool_path, *sys.path]):
            tool = module('inspect_server')
        commands = '\n'.join(tool.parameters()['commands'])
        self.assertIn('python3 - inspect', commands)
        for forbidden in ('deploy.sh', 'docker run', 'docker stop', 'systemctl', 'install ', 'rm '):
            self.assertNotIn(forbidden, commands)


if __name__ == '__main__':
    unittest.main()
