import sys
import unittest
from unittest.mock import patch

import local


class LauncherTests(unittest.TestCase):
    def test_windows_python_is_explicitly_rejected(self):
        with patch.object(sys, 'platform', 'win32'):
            with self.assertRaisesRegex(RuntimeError, 'Ubuntu WSL Python'):
                local.require_linux()

    def test_linux_is_accepted(self):
        local.require_linux()

    def test_run_with_database_stopped_fails_before_starting_java(self):
        with patch.object(local, 'listening', return_value=False), patch.object(local.subprocess, 'Popen') as popen:
            with self.assertRaisesRegex(RuntimeError, 'db-start first'):
                local.run_application()
            popen.assert_not_called()

    def test_run_with_occupied_application_port_does_not_stop_processes(self):
        with patch.object(local, 'listening', return_value=True), patch.object(local.subprocess, 'Popen') as popen:
            with self.assertRaisesRegex(RuntimeError, '8080 is already occupied'):
                local.run_application()
            popen.assert_not_called()


if __name__ == '__main__':
    unittest.main()