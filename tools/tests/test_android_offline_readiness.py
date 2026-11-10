"""Exercise strict, bounded emulator radio readiness without a real device."""
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'tools/prepare_android_offline_emulator.py'


class Clock:
    def __init__(self):
        self.now = 0.0

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class AndroidOfflineReadinessTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SCRIPT.is_file(), 'Missing bounded offline-radio preparation')
        spec = importlib.util.spec_from_file_location('offline_radio', SCRIPT)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.clock = Clock()
        self.calls = []
        self.timeouts = []
        self.samples = 0
        self.output = io.StringIO()
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(contextlib.redirect_stdout(self.output))
        self.stack.enter_context(mock.patch.object(self.module.time, 'monotonic', self.clock.monotonic))
        self.stack.enter_context(mock.patch.object(self.module.time, 'sleep', self.clock.sleep))

    def adb(self, states=None, fail=None, hang=None):
        states = states or [{'airplane_mode_on': '1', 'wifi_on': '0', 'mobile_data': '0'}]

        def run(args, **kwargs):
            self.assertEqual(['adb', '-s', 'emulator-5554', 'shell'], args[:4])
            command = args[4:]
            self.calls.append(command)
            self.timeouts.append(kwargs['timeout'])
            if command == hang:
                raise subprocess.TimeoutExpired(args, kwargs['timeout'])
            if command == fail:
                return subprocess.CompletedProcess(args, 17, '', 'radio service unavailable')
            if command[:3] == ['settings', 'get', 'global']:
                if command[3] == 'airplane_mode_on':
                    self.samples += 1
                value = states[min(self.samples - 1, len(states) - 1)][command[3]]
                return subprocess.CompletedProcess(args, 0, value + '\r\n', '')
            self.assertIn(command, [
                ['svc', 'wifi', 'disable'], ['svc', 'data', 'disable'],
                ['cmd', 'connectivity', 'airplane-mode', 'enable']])
            return subprocess.CompletedProcess(args, 0, '', '')
        self.stack.enter_context(mock.patch.object(self.module.subprocess, 'run', side_effect=run))

    def test_disables_all_radios_before_accepting_exact_ready_state(self):
        self.adb()
        self.module.prepare_offline_emulator()
        self.assertEqual([
            ['svc', 'wifi', 'disable'], ['svc', 'data', 'disable'],
            ['cmd', 'connectivity', 'airplane-mode', 'enable']], self.calls[:3])
        self.assertIn('Offline radio state verified', self.output.getvalue())
        self.assertIn('airplane_mode_on=1 wifi_on=0 mobile_data=0', self.output.getvalue())

    def test_waits_for_asynchronous_disable_without_reissuing_commands(self):
        self.adb([
            {'airplane_mode_on': '0', 'wifi_on': '1', 'mobile_data': '1'},
            {'airplane_mode_on': '1', 'wifi_on': '2', 'mobile_data': '0'},
            {'airplane_mode_on': '1', 'wifi_on': '0', 'mobile_data': '0'}])
        self.module.prepare_offline_emulator()
        self.assertEqual(3, self.samples)
        self.assertEqual(2.0, self.clock.now)
        self.assertIn('wifi_on=2', self.output.getvalue())
        self.assertIn('Offline radio state verified', self.output.getvalue())

    def test_non_ready_state_expires_without_claiming_success(self):
        self.adb([{'airplane_mode_on': '1', 'wifi_on': '1', 'mobile_data': '0'}])
        with self.assertRaisesRegex(RuntimeError, 'deadline'):
            self.module.prepare_offline_emulator()
        self.assertEqual(30.0, self.clock.now)
        self.assertIn('wifi_on=1', self.output.getvalue())
        self.assertNotIn('Offline radio state verified', self.output.getvalue())

    def test_unknown_or_empty_radio_values_fail_closed(self):
        for invalid in ('null', '', 'unknown', '0\n1'):
            with self.subTest(value=invalid):
                self.clock.now = 0.0
                self.adb([{'airplane_mode_on': '1', 'wifi_on': invalid, 'mobile_data': '0'}])
                with self.assertRaises(RuntimeError):
                    self.module.prepare_offline_emulator()
        self.assertNotIn('Offline radio state verified', self.output.getvalue())

    def test_disable_command_error_names_failed_command_and_stops(self):
        self.adb(fail=['svc', 'wifi', 'disable'])
        with self.assertRaisesRegex(RuntimeError, 'svc wifi disable.*17.*radio service unavailable'):
            self.module.prepare_offline_emulator()
        self.assertEqual(1, len(self.calls))

    def test_adb_hang_is_bounded_and_names_command(self):
        self.adb(hang=['cmd', 'connectivity', 'airplane-mode', 'enable'])
        with self.assertRaisesRegex(RuntimeError, 'airplane-mode enable.*timed out'):
            self.module.prepare_offline_emulator()
        self.assertTrue(all(0 < value <= 5 for value in self.timeouts))
        self.assertEqual(0, self.samples)

    def test_read_error_never_accepts_partial_ready_snapshot(self):
        self.adb(fail=['settings', 'get', 'global', 'wifi_on'])
        with self.assertRaisesRegex(RuntimeError, 'settings get global wifi_on'):
            self.module.prepare_offline_emulator()
        self.assertNotIn('Offline radio state verified', self.output.getvalue())

    def test_command_budget_shrinks_at_shared_deadline(self):
        self.clock.now = 29.5
        self.adb(hang=['settings', 'get', 'global', 'wifi_on'])
        with self.assertRaisesRegex(RuntimeError, 'timed out'):
            self.module.adb_shell(['settings', 'get', 'global', 'wifi_on'], 30.0)
        self.assertEqual([0.5], self.timeouts)

    def test_cli_rejects_non_ci_context_before_touching_adb(self):
        result = subprocess.run(['python', str(SCRIPT)], text=True, capture_output=True,
                                env=dict(os.environ, GITHUB_ACTIONS='false'))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('correcciones-dot CI fixtures', result.stderr)

    def test_cli_waits_for_delayed_radio_state_with_real_subprocesses(self):
        with tempfile.TemporaryDirectory(prefix='radio cli ') as directory:
            root = Path(directory)
            adb = root / 'adb'
            adb.write_text("""#!/usr/bin/env python3
from pathlib import Path
import sys
args = sys.argv[1:]
assert args[:3] == ['-s', 'emulator-5554', 'shell']
command = args[3:]
if command[:3] == ['settings', 'get', 'global']:
    path = Path('poll-count')
    count = int(path.read_text()) if path.exists() else 0
    if command[3] == 'airplane_mode_on':
        count += 1
        path.write_text(str(count))
    print('1' if command[3] == 'airplane_mode_on' or count == 1 else '0')
else:
    assert command in [['svc', 'wifi', 'disable'], ['svc', 'data', 'disable'],
                       ['cmd', 'connectivity', 'airplane-mode', 'enable']]
""")
            adb.chmod(0o700)
            env = dict(os.environ, GITHUB_ACTIONS='true', GITHUB_REF='refs/heads/correcciones-dot',
                       NOVVIN_CI_FIXTURE='true', ANDROID_SERIAL='emulator-5554',
                       PATH=str(root) + os.pathsep + os.environ['PATH'])
            result = subprocess.run(['python', str(SCRIPT)], cwd=root, env=env,
                                    text=True, capture_output=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn('wifi_on=1', result.stdout)
            self.assertIn('wifi_on=0', result.stdout)
            self.assertIn('Offline radio state verified', result.stdout)

    def test_runner_error_trap_records_failure_and_preserves_exit_status(self):
        source = (ROOT / 'tools/run_android_account_smoke.sh').read_text()
        self.assertIn('# Record the exact failing runner line without dumping environment or secrets.', source)
        block = source.split('# Record the exact failing runner line without dumping environment or secrets.', 1)[1]
        block = block.split('# Keep command-line tools', 1)[0]
        with tempfile.TemporaryDirectory(prefix='radio error ') as directory:
            script = 'set -Eeuo pipefail\nreport_dir="$PWD"\n' + block + '\n(exit 7)\ntouch installed\n'
            result = subprocess.run(['bash', '-c', script], cwd=directory, text=True, capture_output=True)
            self.assertEqual(7, result.returncode)
            self.assertFalse(Path(directory, 'installed').exists())
            self.assertIn('exit 7', Path(directory, 'runner-error.log').read_text())

    def test_runner_stops_before_install_when_helper_fails(self):
        source = (ROOT / 'tools/run_android_account_smoke.sh').read_text()
        block = source.split('# Disable emulator networking before Gradle installs or launches the application.', 1)[1]
        block = block.split('adb shell settings put global window_animation_scale', 1)[0]
        with tempfile.TemporaryDirectory(prefix='radio guard ') as directory:
            root = Path(directory)
            fake_python = root / 'python'
            fake_python.write_text('#!/bin/sh\necho "radio guard refused" >&2\nexit 7\n')
            fake_python.chmod(0o700)
            script = 'set -euo pipefail\nreport_dir="$PWD"\n' + block + '\ntouch installed\n'
            result = subprocess.run(['bash', '-c', script], cwd=root, text=True, capture_output=True,
                                    env=dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH']))
            self.assertEqual(7, result.returncode, result.stderr)
            self.assertFalse((root / 'installed').exists())
            self.assertIn('radio guard refused', (root / 'radio-readiness.log').read_text())


if __name__ == '__main__':
    unittest.main()
