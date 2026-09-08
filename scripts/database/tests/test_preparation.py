"""DB 변경 없이 실행 준비·비밀 비노출을 검증한다. 작성자: 김진우."""

import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


ROOT = Path(__file__).resolve().parents[3]
prepare = load('prepare', ROOT / 'scripts/database/prepare_findings_migration.py')
inspect = load('target', ROOT / 'scripts/deploy/inspect_database_target.py').inspect


class PreparationTest(unittest.TestCase):
    def test_transaction_and_locks_precede_sql(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'migration.sql'
            raw = b'select 1;'
            path.write_bytes(raw)
            digest = hashlib.sha256(raw).hexdigest()
            sql = prepare.prepare(path, digest)
            self.assertTrue(sql.startswith('begin;'))
            self.assertTrue(sql.endswith('commit;\n'))
            self.assertLess(sql.index('lock table'), sql.index('select 1;'))
            self.assertIn("lock_timeout = '5s'", sql)
            managed = prepare.prepare(path, digest, True)
            self.assertNotIn('begin;', managed)
            self.assertNotIn('commit;', managed)
            with self.assertRaises(ValueError):
                prepare.prepare(path, '0' * 64)

    def test_direct_target_and_secret_non_disclosure(self):
        ref = 'a' * 20
        values = {'DATABASE_URL': f'jdbc:postgresql://db.{ref}.supabase.co:5432/postgres?password=synthetic-secret',
                  'DATABASE_USERNAME': 'postgres', 'DATABASE_PASSWORD': 'synthetic-secret',
                  'SUPABASE_URL': f'https://{ref}.supabase.co'}
        result = inspect(values, ref)
        self.assertTrue(result['databaseProjectMatches'])
        self.assertEqual(result['databaseRole'], 'postgres')
        self.assertNotIn('synthetic-secret', json.dumps(result))
        self.assertFalse(inspect(values, 'b' * 20)['databaseProjectMatches'])

    def test_pooler_role_and_unknown_host(self):
        ref = 'a' * 20
        values = {'DATABASE_URL': 'jdbc:postgresql://aws-0-ap-northeast-1.pooler.supabase.com:5432/postgres',
                  'DATABASE_USERNAME': f'postgres.{ref}'}
        self.assertTrue(inspect(values, ref)['databaseProjectMatches'])
        self.assertEqual(inspect(values, ref)['databaseRole'], 'postgres')
        values['DATABASE_URL'] = 'jdbc:postgresql://unverified.example/postgres'
        self.assertFalse(inspect(values, ref)['databaseProjectIdentified'])


if __name__ == '__main__':
    unittest.main()
