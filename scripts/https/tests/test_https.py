"""HTTPS 설정의 공개 범위와 약관 경계를 검증한다. 작성자: 김진우."""

import importlib.util
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class HttpsTest(unittest.TestCase):
    def test_http_never_proxies_api(self):
        content = (ROOT / "nginx-http.conf").read_text(encoding="utf-8")
        self.assertNotIn("proxy_pass", content)
        self.assertIn("location / { return 404; }", content)
        self.assertIn("/.well-known/acme-challenge/", content)

    def test_https_proxies_only_api_and_does_not_log_requests(self):
        content = (ROOT / "nginx-https.conf").read_text(encoding="utf-8")
        self.assertEqual(content.count("proxy_pass"), 1)
        self.assertIn("location /api/", content)
        self.assertIn("proxy_pass http://127.0.0.1:8080;", content)
        self.assertIn("access_log off;", content)
        self.assertIn("ssl_protocols TLSv1.2 TLSv1.3;", content)
        self.assertIn("location / { return 404; }", content)

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
        for mode in ("inspect", "bootstrap", "activate"):
            params = sender.parameters(mode)
            self.assertTrue(params["commands"][-1].endswith(mode))
        for mode in ("issue", "bootstrap;id", ""):
            with self.assertRaises(ValueError):
                sender.parameters(mode)


if __name__ == "__main__":
    unittest.main()
