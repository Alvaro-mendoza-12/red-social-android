import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import yaml


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'tools/android_kvm_acl.py'
BASE_ACL = 'user::rw-\ngroup::rw-\nother::---\n'


class AndroidKvmAclTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SCRIPT.exists(), 'Missing bounded KVM ACL helper')
        spec = importlib.util.spec_from_file_location('android_kvm_acl', SCRIPT)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        env = {
            'GITHUB_ACTIONS': 'true', 'GITHUB_REF': 'refs/heads/correcciones-dot',
            'GITHUB_JOB': 'saved-account-tests', 'RUNNER_ENVIRONMENT': 'github-hosted',
            'NOVVIN_CI_FIXTURE': 'true', 'NOVVIN_KVM_ACCESS_APPROVED': 'true',
            'RUNNER_TEMP': self.directory.name,
        }
        self.env = patch.dict(os.environ, env, clear=True)
        self.env.start()
        self.addCleanup(self.env.stop)
        self.uid = patch.object(self.module.os, 'getuid', return_value=1001)
        self.uid.start()
        self.addCleanup(self.uid.stop)
        self.device = patch.object(Path, 'is_char_device', return_value=True)
        self.device.start()
        self.addCleanup(self.device.stop)
        self.calls = []

    def command(self, args, **kwargs):
        self.calls.append(args)
        if args[0] == 'getfacl':
            return subprocess.CompletedProcess(args, 0, BASE_ACL)
        if args[0] == 'stat':
            return subprocess.CompletedProcess(args, 0, '660 root:kvm\n')
        return subprocess.CompletedProcess(args, 0, '')

    def test_rejects_missing_approval_without_mutation(self):
        os.environ['NOVVIN_KVM_ACCESS_APPROVED'] = 'false'
        with patch.object(self.module.subprocess, 'run', side_effect=self.command), self.assertRaises(ValueError):
            self.module.manage('grant')
        self.assertEqual([], self.calls)

    def test_rejects_non_hosted_or_other_branch_context(self):
        for key, value in [('RUNNER_ENVIRONMENT', 'self-hosted'), ('GITHUB_REF', 'refs/heads/main')]:
            with patch.dict(os.environ, {key: value}), self.assertRaises(ValueError):
                self.module.manage('grant')

    def test_missing_device_never_mutates_permissions(self):
        with patch.object(Path, 'is_char_device', return_value=False), self.assertRaises(ValueError):
            self.module.manage('grant')

    def test_rejects_existing_extended_acl(self):
        def command(args, **kwargs):
            result = self.command(args, **kwargs)
            if args[0] == 'getfacl':
                result.stdout += 'user:2000:r--\n'
            return result
        with patch.object(self.module.subprocess, 'run', side_effect=command), self.assertRaises(ValueError):
            self.module.manage('grant')
        self.assertFalse(any(call[0] == 'sudo' for call in self.calls))

    def test_grant_snapshots_original_and_targets_only_current_user(self):
        with patch.object(self.module.subprocess, 'run', side_effect=self.command), patch.object(self.module.os, 'access', return_value=True):
            self.module.manage('grant')
        snapshot = Path(self.directory.name, 'novvin-kvm-original.acl')
        self.assertEqual(BASE_ACL, snapshot.read_text())
        self.assertEqual(0o600, snapshot.stat().st_mode & 0o777)
        self.assertIn(['sudo', '-n', 'setfacl', '-m', 'u:1001:rw', '/dev/kvm'], self.calls)

    def test_failed_grant_keeps_snapshot_for_always_cleanup(self):
        def command(args, **kwargs):
            if args[0] == 'sudo':
                raise subprocess.CalledProcessError(1, args)
            return self.command(args, **kwargs)
        with patch.object(self.module.subprocess, 'run', side_effect=command), self.assertRaises(subprocess.CalledProcessError):
            self.module.manage('grant')
        self.assertEqual(BASE_ACL, Path(self.directory.name, 'novvin-kvm-original.acl').read_text())

    def test_restore_reinstates_and_verifies_original_acl(self):
        snapshot = Path(self.directory.name, 'novvin-kvm-original.acl')
        snapshot.write_text(BASE_ACL)
        with patch.object(self.module.subprocess, 'run', side_effect=self.command):
            self.module.manage('restore')
        self.assertIn(['sudo', '-n', 'setfacl', '--set-file', str(snapshot), '/dev/kvm'], self.calls)
        self.assertFalse(snapshot.exists())

    def test_restore_mismatch_fails_and_retains_evidence(self):
        snapshot = Path(self.directory.name, 'novvin-kvm-original.acl')
        snapshot.write_text(BASE_ACL)
        def command(args, **kwargs):
            result = self.command(args, **kwargs)
            if args[0] == 'getfacl':
                result.stdout += 'user:1001:rw-\n'
            return result
        with patch.object(self.module.subprocess, 'run', side_effect=command), self.assertRaises(ValueError):
            self.module.manage('restore')
        self.assertTrue(snapshot.exists())

    def test_restore_command_failure_retains_snapshot_and_fails(self):
        snapshot = Path(self.directory.name, 'novvin-kvm-original.acl')
        snapshot.write_text(BASE_ACL)
        with patch.object(self.module.subprocess, 'run', side_effect=subprocess.CalledProcessError(1, ['sudo'])), self.assertRaises(subprocess.CalledProcessError):
            self.module.manage('restore')
        self.assertTrue(snapshot.exists())

    def test_unexpected_original_mode_is_not_changed(self):
        def command(args, **kwargs):
            result = self.command(args, **kwargs)
            if args[0] == 'stat':
                result.stdout = '666 root:kvm\n'
            return result
        with patch.object(self.module.subprocess, 'run', side_effect=command), self.assertRaises(ValueError):
            self.module.manage('grant')
        self.assertFalse(any(call[0] == 'sudo' for call in self.calls))

    def test_restore_without_grant_makes_no_change(self):
        with patch.object(self.module.subprocess, 'run', side_effect=self.command):
            self.module.manage('restore')
        self.assertEqual([], self.calls)

    def test_workflow_requires_opt_in_and_always_restores(self):
        workflow = yaml.safe_load((ROOT / '.github/workflows/android-instrumented-smoke.yml').read_text())
        trigger = workflow.get('on', workflow.get(True))
        approval = trigger['workflow_dispatch']['inputs']['allow_temporary_kvm_access']
        self.assertIs(False, approval['default'])
        self.assertEqual('boolean', approval['type'])
        steps = workflow['jobs']['saved-account-tests']['steps']
        grant = next(i for i, step in enumerate(steps) if step.get('run') == 'python tools/android_kvm_acl.py grant')
        restore = next(i for i, step in enumerate(steps) if step.get('run') == 'python tools/android_kvm_acl.py restore')
        case = next(i for i, step in enumerate(steps) if 'bash tools/run_android_account_smoke.sh' in step.get('run', ''))
        self.assertEqual('inputs.allow_temporary_kvm_access', steps[grant]['if'])
        self.assertEqual('always() && inputs.allow_temporary_kvm_access', steps[restore]['if'])
        self.assertLess(grant, case)
        self.assertLess(case, restore)
        self.assertNotIn('continue-on-error', steps[restore])


if __name__ == '__main__':
    unittest.main()
