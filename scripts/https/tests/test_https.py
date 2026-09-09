"""HTTPS 설정의 공개 범위와 약관 경계를 검증한다. 작성자: 김진우."""

import importlib.util
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class HttpsTest(unittest.TestCase):
    def test_http_never_proxies_api(self):
        content = (ROOT / "nginx-http.conf").read_text(encoding="utf-8")
        self.assertNotIn("proxy_pass", content)
        self.assertIn("location / { return 404; }", content)
        self.assertIn("/.well-known/acme-challenge/", content)

    def test_https_proxies_api_and_swagger_and_does_not_log_requests(self):
        content = (ROOT / "nginx-https.conf").read_text(encoding="utf-8")
        self.assertEqual(content.count("proxy_pass"), 2)
        self.assertIn("location /api/", content)
        self.assertIn("proxy_pass http://127.0.0.1:8080;", content)
        self.assertIn("access_log off;", content)
        self.assertIn("ssl_protocols TLSv1.2 TLSv1.3;", content)
        self.assertIn("location / { return 404; }", content)

    def test_swagger_route_rejects_unrelated_paths(self):
        content = (ROOT / "nginx-https.conf").read_text(encoding="utf-8")
        route = re.search(r"location ~ (\S+) \{", content).group(1)
        for path in ("/swagger-ui.html", "/swagger-ui/index.html", "/swagger-ui/swagger-ui.css",
                     "/v3/api-docs", "/v3/api-docs/swagger-config"):
            self.assertIsNotNone(re.match(route, path), path)
        for path in ("/actuator/health", "/internal/chat/stream", "/v3/api-docs-other",
                     "/swagger-ui.html-other", "/swagger-ui-other"):
            self.assertIsNone(re.match(route, path), path)

    def test_activate_uses_current_source_and_checks_swagger(self):
        content = (ROOT / "setup.sh").read_text(encoding="utf-8")
        self.assertIn('install -m 644 "$SOURCE/nginx-https.conf" "$TARGET/nginx.conf"', content)
        self.assertIn("/v3/api-docs/swagger-config", content)
        self.assertIn("/internal/chat/stream", content)

    def test_terms_are_not_automatically_accepted(self):
        content = (ROOT / "issue-certificate.sh").read_text(encoding="utf-8")
        self.assertNotIn("--agree-tos", content)
        self.assertNotIn("--non-interactive", content)
        self.assertIn("--ip-address 13.125.12.94", content)
        self.assertIn("--preferred-profile shortlived", content)

    def test_renewal_reloads_correct_configuration(self):
        content = (ROOT / "renew-certificate.sh").read_text(encoding="utf-8")
        self.assertIn("-c /etc/heapy/nginx.conf -t", content)
        self.assertIn("-c /etc/heapy/nginx.conf -s reload", content)
        self.assertIn("--dry-run", content)

    def test_modes_and_transferred_files(self):
        spec = importlib.util.spec_from_file_location("https_sender", ROOT / "send_command.py")
        sender = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(sender)
        for mode in ("inspect", "bootstrap", "activate", "repair", "verify-renewal"):
            params = sender.parameters(mode)
            self.assertTrue(params["commands"][-1].endswith(mode))
        for mode in ("issue", "bootstrap;id", ""):
            with self.assertRaises(ValueError):
                sender.parameters(mode)

    def test_all_nginx_temporary_paths_use_writable_tmp(self):
        for name in ("nginx-http.conf", "nginx-https.conf"):
            content = (ROOT / name).read_text(encoding="utf-8")
            for module in ("proxy", "fastcgi", "uwsgi", "scgi"):
                self.assertIn(f"{module}_temp_path /tmp/{module}_temp;", content)


if __name__ == "__main__":
    unittest.main()
