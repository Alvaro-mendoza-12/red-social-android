"""Exercise hosted SDK path setup without installing tools or accepting licenses."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[2]


class AndroidSdkPathTest(unittest.TestCase):
    def script(self):
        workflow = yaml.safe_load((ROOT / '.github/workflows/android-instrumented-smoke.yml').read_text())
        steps = workflow['jobs']['saved-account-tests']['steps']
        matches = [i for i, step in enumerate(steps) if step['name'] == 'Expose installed Android command-line tools']
        self.assertEqual(1, len(matches), 'Missing explicit hosted SDK path setup')
        install = next(i for i, step in enumerate(steps) if step['name'] == 'Install only stable AOSP SDK packages')
        self.assertLess(matches[0], install)
        return steps[matches[0]]['run']

    def test_installed_sdk_tools_become_resolvable_in_later_steps(self):
        script = self.script()
        with tempfile.TemporaryDirectory(prefix='sdk path ') as directory:
            sdk = Path(directory, 'android sdk')
            tools = {'sdkmanager': 'cmdline-tools/latest/bin', 'avdmanager': 'cmdline-tools/latest/bin',
                     'adb': 'platform-tools', 'emulator': 'emulator'}
            for tool, folder in tools.items():
                binary = sdk / folder / tool
                binary.parent.mkdir(parents=True, exist_ok=True)
                binary.write_text('#!/bin/sh\nexit 0\n')
                binary.chmod(0o700)
            path_file = Path(directory, 'github-path')
            env = dict(os.environ, ANDROID_HOME=str(sdk), GITHUB_PATH=str(path_file))
            subprocess.run(['bash', '-eu', '-c', script], env=env, check=True)
            paths = path_file.read_text().splitlines()
            self.assertEqual([str(sdk / folder) for folder in dict.fromkeys(tools.values())], paths)
            env['PATH'] = os.pathsep.join(paths + ['/usr/bin', '/bin'])
            for tool, folder in tools.items():
                result = subprocess.run(['bash', '-c', f'command -v {tool}'], env=env, check=True, capture_output=True, text=True)
                self.assertEqual(str(sdk / folder / tool), result.stdout.strip())

    def test_missing_command_line_tools_fail_before_path_write(self):
        with tempfile.TemporaryDirectory() as directory:
            path_file = Path(directory, 'github-path')
            env = dict(os.environ, ANDROID_HOME=directory, GITHUB_PATH=str(path_file))
            result = subprocess.run(['bash', '-eu', '-c', self.script()], env=env, capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(path_file.exists())


if __name__ == '__main__':
    unittest.main()
