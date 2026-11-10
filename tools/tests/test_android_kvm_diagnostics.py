"""Keep hosted-emulator diagnostics read-only and fail closed."""
from pathlib import Path
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[2]


class AndroidKvmDiagnosticsTest(unittest.TestCase):
    def test_diagnostics_leave_hardware_access_requirement_intact(self):
        workflow = yaml.safe_load((ROOT / '.github/workflows/android-instrumented-smoke.yml').read_text())
        steps = workflow['jobs']['saved-account-tests']['steps']
        check = next(step for step in steps if step['name'] == 'Require existing hardware acceleration')
        script = check['run']
        self.assertIn('\nid\n', '\n' + script)
        self.assertIn('stat -c', script)
        self.assertIn('getfacl -p /dev/kvm', script)
        self.assertIn('No /dev/kvm device is present', script)
        self.assertTrue(script.strip().endswith('test -r /dev/kvm && test -w /dev/kvm'))
        self.assertNotIn('continue-on-error', check)
        for mutation in ('sudo', 'chmod', 'chown', 'setfacl', 'udevadm', 'usermod'):
            self.assertNotIn(mutation, script)


if __name__ == '__main__':
    unittest.main()
