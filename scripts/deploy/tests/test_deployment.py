"""AWS·실제 DB 없이 배포 안전장치를 검증한다. 작성자: 김진우."""

import importlib.util
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts/deploy" / (name + ".py"))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


class EnvironmentTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.path = Path(self.folder.name) / "backend.env"
        self.content = (
            "DATABASE_URL=jdbc:postgresql://database.test/postgres\n"
            "DATABASE_USERNAME=test\nDATABASE_PASSWORD=test$with=characters\n"
            "SUPABASE_URL=https://project.test\nSUPABASE_ANON_KEY=test-key\n"
            "SUPABASE_JWT_ISSUER=https://project.test/auth/v1\n"
            "SUPABASE_JWK_SET_URI=https://project.test/auth/v1/.well-known/jwks.json\n"
        )

    def check(self, content):
        self.path.write_text(content, encoding="utf-8")
        module("validate_env").validate(self.path)

    def test_valid_values_and_literal_password(self):
        self.check(self.content)

    def test_invalid_files(self):
        for content in (
            "", self.content + "JAVA_TOOL_OPTIONS=-agentlib:invalid\n",
            self.content + "DATABASE_USERNAME=duplicate\n",
            self.content.replace("DATABASE_PASSWORD=test$with=characters", 'DATABASE_PASSWORD="quoted"'),
            self.content.replace("https://project.test/auth/v1\n", "https://other.test/auth/v1\n"),
        ):
            with self.subTest(content_length=len(content)), self.assertRaises(ValueError):
                self.check(content)


class CommandTest(unittest.TestCase):
    def test_digest_only_and_no_secrets(self):
        image = "577638373354.dkr.ecr.ap-northeast-2.amazonaws.com/heapy-backend@sha256:" + "a" * 64
        params = module("send_command").parameters(image)
        self.assertIn(image, params["commands"][-1])
        self.assertEqual(params["executionTimeout"], ["960"])
        self.assertNotIn("DATABASE_PASSWORD=", "\n".join(params["commands"]))

    def test_other_registry_or_shell_injection_rejected(self):
        for image in ("evil/image:latest", "image; echo injected", "heapy-backend:latest"):
            with self.subTest(image=image), self.assertRaises(ValueError):
                module("send_command").parameters(image)


class RolloutTest(unittest.TestCase):
    def execute(self, scenario):
        bash = os.environ.get("DEPLOY_TEST_BASH") or shutil.which("bash")
        self.assertIsNotNone(bash, "배포 검증에 Bash가 필요합니다.")
        script = r'''
source scripts/deploy/deploy.sh
IMAGE=test-image
calls=0
docker() {
  echo "DOCKER $*" >&2
  if [[ $1 == container ]]; then
    if [[ $3 == heapy-backend-rollback ]]; then
      [[ $SCENARIO == leftover ]]; return
    fi
    [[ $SCENARIO != first-fail ]]; return
  fi
  if [[ $1 == run && $SCENARIO == run-fail ]]; then return 1; fi
  if [[ $1 == stop && $SCENARIO == stop-fail ]]; then return 1; fi
  return 0
}
healthy() {
  calls=$((calls+1))
  [[ $SCENARIO != rollback-fail ]] || return 1
  [[ $SCENARIO == success || $SCENARIO == run-fail || $SCENARIO == stop-fail || $calls -gt 1 ]]
}
rollout
'''
        return subprocess.run(
            [bash, "-s"], input=script, text=True, capture_output=True, cwd=ROOT,
            env={**os.environ, "SCENARIO": scenario}, encoding="utf-8",
        )

    def test_success_retains_loopback_and_cleans_previous(self):
        result = self.execute("success")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("-p 127.0.0.1:8080:8080", result.stderr)
        self.assertIn("SPRINGDOC_API_DOCS_ENABLED=true", result.stderr)
        self.assertIn("SPRINGDOC_SWAGGER_UI_ENABLED=true", result.stderr)
        self.assertIn("SERVER_FORWARD_HEADERS_STRATEGY=framework", result.stderr)
        self.assertIn("DOCKER rm heapy-backend-rollback", result.stderr)
        self.assertNotIn("DOCKER start", result.stderr)

    def test_health_run_and_stop_failure_restore_previous(self):
        for scenario in ("health-fail", "run-fail", "stop-fail"):
            with self.subTest(scenario=scenario):
                result = self.execute(scenario)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("DOCKER rename heapy-backend-rollback heapy-backend", result.stderr)
                self.assertIn("DOCKER start heapy-backend", result.stderr)
                self.assertIn("이전 컨테이너 복원 완료", result.stderr)

    def test_failed_rollback_is_reported(self):
        result = self.execute("rollback-fail")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("이전 컨테이너 복원 실패", result.stderr)

    def test_first_failure_has_no_fake_rollback(self):
        result = self.execute("first-fail")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("DOCKER start", result.stderr)

    def test_leftover_backup_blocks_replacement(self):
        result = self.execute("leftover")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("DOCKER stop", result.stderr)
        self.assertNotIn("DOCKER rm", result.stderr)


if __name__ == "__main__":
    unittest.main()
