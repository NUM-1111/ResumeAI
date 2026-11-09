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
        with patch.object(local, 'listening', return_value=True), patch.object(local, 'application_pid', return_value=None), patch.object(local.subprocess, 'Popen') as popen:
            with self.assertRaisesRegex(RuntimeError, '8080 is already occupied'):
                local.run_application()
            popen.assert_not_called()

    def test_existing_verified_app_is_not_started_twice(self):
        with patch.object(local, 'listening', return_value=True), patch.object(local, 'application_pid', return_value=123), patch.object(local.subprocess, 'Popen') as popen:
            local.run_application()
            popen.assert_not_called()

    def test_stop_refuses_unknown_process(self):
        with patch.object(local, 'listening', return_value=True), patch.object(local, 'application_pid', return_value=None), patch.object(local.os, 'kill') as kill:
            with self.assertRaisesRegex(RuntimeError, 'unverified process'):
                local.stop_application()
            kill.assert_not_called()

    def test_stop_only_signals_verified_listener_and_waits(self):
        with patch.object(local, 'listening', side_effect=[True, False]), patch.object(local, 'application_pid', return_value=123), patch.object(local.os, 'kill') as kill:
            local.stop_application()
            kill.assert_called_once_with(123, local.signal.SIGTERM)

    def test_stop_without_listener_is_safe(self):
        with patch.object(local, 'listening', return_value=False), patch.object(local.os, 'kill') as kill:
            local.stop_application()
            kill.assert_not_called()


if __name__ == '__main__':
    unittest.main()