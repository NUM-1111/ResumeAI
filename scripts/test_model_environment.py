import tempfile
import unittest
from pathlib import Path
from model_environment import load_model_environment


class ModelEnvironmentTests(unittest.TestCase):
    def read(self, text, env=None):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / '.env'
            path.write_text(text, encoding='utf-8')
            return load_model_environment(env or {}, path)

    def test_reads_only_backend_key_and_ignores_comments(self):
        result = self.read('# note\nDEEPSEEK_API_KEY="synthetic-key"\nSPRING_DATASOURCE_URL=untrusted\n')
        self.assertEqual(result, {'DEEPSEEK_API_KEY': 'synthetic-key'})

    def test_shell_environment_has_priority(self):
        self.assertEqual(self.read('DEEPSEEK_API_KEY=file-value', {'DEEPSEEK_API_KEY': 'shell-value'})['DEEPSEEK_API_KEY'], 'shell-value')

    def test_values_are_literal_not_executable(self):
        self.assertEqual(self.read("export DEEPSEEK_API_KEY='$(not-executed)'\n")['DEEPSEEK_API_KEY'], '$(not-executed)')

    def test_bad_line_does_not_expose_value(self):
        with self.assertRaisesRegex(RuntimeError, '^Unclosed .env quote at line 1$'):
            self.read('DEEPSEEK_API_KEY="synthetic-secret')

    def test_missing_file_is_optional(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(load_model_environment({}, Path(directory)/'.env'), {})


if __name__ == '__main__':
    unittest.main()