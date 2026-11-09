import crypt
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import local
from passwords import bcrypt_hash, test_credentials

class PasswordTests(unittest.TestCase):
    def test_length_boundaries_and_verification(self):
        for size in (6, 32):
            with self.subTest(size=size):
                value = 'a' * size
                encoded = bcrypt_hash(value)
                self.assertEqual(crypt.crypt(value, encoded), encoded)
                self.assertNotEqual(crypt.crypt(value + 'x', encoded), encoded)
        for size in (0, 5, 33):
            with self.assertRaisesRegex(RuntimeError, '6 to 32'):
                bcrypt_hash('a' * size)

    def test_unicode_byte_limit_and_nul(self):
        encoded = bcrypt_hash('中' * 24)
        self.assertEqual(crypt.crypt('中' * 24, encoded), encoded)
        for value in ('中' * 25, 'abc\x00def'):
            with self.assertRaisesRegex(RuntimeError, '72 UTF-8'):
                bcrypt_hash(value)

    def test_generated_test_password_obeys_new_policy(self):
        env, password = test_credentials({})
        self.assertTrue(6 <= len(password) <= 32)
        self.assertEqual(crypt.crypt(password, env['AGENTCV_OPERATOR_PASSWORD_HASH']), env['AGENTCV_OPERATOR_PASSWORD_HASH'])

    def test_legacy_migration_preserves_long_password_without_cleartext(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'credentials.json'
            password = 'a' * 48
            path.write_text(json.dumps({'database': 'synthetic-database', 'operator': password}))
            path.chmod(0o600)
            with patch.object(local, 'LOCAL', Path(directory)):
                values = local.config()
            self.assertNotIn('operator', values)
            self.assertNotIn(password, path.read_text())
            self.assertEqual(crypt.crypt(password, values['operatorHash']), values['operatorHash'])

    def test_invalid_or_mismatched_set_password_does_not_write_config(self):
        for inputs in (['abcde', 'abcde'], ['abcdef', 'different']):
            with patch('sys.argv', ['local.py', 'set-password']), patch('getpass.getpass', side_effect=inputs), patch.object(local, 'config', return_value={'operatorHash': 'unchanged'}), patch('pathlib.Path.open') as opened:
                with self.assertRaises(RuntimeError):
                    local.main()
                opened.assert_not_called()

if __name__ == '__main__':
    unittest.main()