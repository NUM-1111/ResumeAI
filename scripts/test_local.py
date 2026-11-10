import sys
import subprocess
import tempfile
import unittest
from pathlib import Path
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


class DatabaseStartupTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.data = Path(directory.name)
        (self.data / 'PG_VERSION').write_text('12\n')
        for patcher in (patch.object(local, 'DATA', self.data),
                        patch.object(local, 'config', return_value={'database': 'test-password'})):
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_stale_pid_file_does_not_prevent_startup(self):
        (self.data / 'postmaster.pid').write_text('123\n')
        self.assert_stopped_database_starts()
        self.assertTrue((self.data / 'postmaster.pid').exists())

    def test_stopped_database_without_pid_file_starts(self):
        self.assert_stopped_database_starts()

    def assert_stopped_database_starts(self):
        stopped = subprocess.CalledProcessError(3, ['pg_ctl', 'status'])
        with patch.object(local, 'pg', side_effect=[stopped, None]) as pg, patch.object(local, 'psql') as psql:
            local.legacy_db_start()
            self.assertEqual(pg.call_count, 2)
            pg.assert_any_call(local.PG / 'pg_ctl', '-D', self.data, 'status', stdout=subprocess.PIPE)
            pg.assert_called_with(local.PG / 'pg_ctl', '-D', self.data,
                                  '-l', self.data / 'server.log', '-w', 'start')
            psql.assert_called_once()

    def test_running_database_is_not_started_again(self):
        with patch.object(local, 'pg') as pg, patch.object(local, 'psql') as psql:
            local.legacy_db_start()
            pg.assert_called_once_with(local.PG / 'pg_ctl', '-D', self.data,
                                       'status', stdout=subprocess.PIPE)
            psql.assert_called_once()

    def test_unexpected_status_error_is_not_ignored(self):
        error = subprocess.CalledProcessError(4, ['pg_ctl', 'status'])
        with patch.object(local, 'pg', side_effect=error) as pg, patch.object(local, 'psql') as psql:
            with self.assertRaises(subprocess.CalledProcessError) as raised:
                local.legacy_db_start()
            self.assertIs(raised.exception, error)
            pg.assert_called_once()
            psql.assert_not_called()


if __name__ == '__main__':
    unittest.main()