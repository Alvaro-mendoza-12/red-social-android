"""Check the shared registry used by AVD creation and emulator discovery."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


class AndroidSmokeAvdTest(unittest.TestCase):
    def setup_script(self):
        script = (ROOT / 'tools/run_android_account_smoke.sh').read_text()
        start = '# Keep command-line tools and emulator on the same disposable AVD registry.'
        end = '# Start the validated AVD.'
        self.assertIn(start, script)
        self.assertIn(end, script)
        return script.split(start, 1)[1].split(end, 1)[0]

    def test_creation_and_lookup_share_explicit_registry(self):
        with tempfile.TemporaryDirectory(prefix='avd registry ') as directory:
            root = Path(directory)
            sdk = root / 'sdk'
            bin_dir = root / 'bin'
            bin_dir.mkdir()
            (sdk / 'emulator').mkdir(parents=True)
            manager = bin_dir / 'avdmanager'
            manager.write_text('''#!/bin/bash
set -eu
[[ "$ANDROID_EMULATOR_HOME" == "$ANDROID_USER_HOME" ]]
[[ "$ANDROID_AVD_HOME" == "$ANDROID_USER_HOME/avd" ]]
printf 'path=%s/saved_account_qa.avd\\n' "$ANDROID_AVD_HOME" > "$ANDROID_AVD_HOME/saved_account_qa.ini"
''')
            emulator = sdk / 'emulator/emulator'
            emulator.write_text('''#!/bin/bash
set -eu
[[ "$1" == -list-avds ]]
[[ -s "$ANDROID_AVD_HOME/saved_account_qa.ini" ]]
echo saved_account_qa
''')
            manager.chmod(0o700)
            emulator.chmod(0o700)
            env = dict(os.environ, ANDROID_HOME=str(sdk), RUNNER_TEMP=str(root / 'runner tmp'),
                       ANDROID_AVD_HOME=str(root / 'wrong inherited registry'),
                       PATH=str(bin_dir) + os.pathsep + os.environ['PATH'])
            subprocess.run(['bash', '-euo', 'pipefail', '-c', self.setup_script()], env=env, check=True)
            self.assertTrue((root / 'runner tmp/novvin-android-user/avd/saved_account_qa.ini').is_file())

    def test_preflight_requires_both_registry_file_and_emulator_discovery(self):
        script = self.setup_script()
        self.assertIn('test -s "$ANDROID_AVD_HOME/saved_account_qa.ini"', script)
        self.assertIn('"$ANDROID_HOME/emulator/emulator" -list-avds | grep -Fx saved_account_qa', script)
        self.assertIn('--path "$ANDROID_AVD_HOME/saved_account_qa.avd"', script)


if __name__ == '__main__':
    unittest.main()
