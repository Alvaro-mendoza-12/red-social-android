"""Safety properties of host149; no host package, daemon or Docker changes."""
from copy import deepcopy
import datetime as dt
import importlib.util
from contextlib import redirect_stderr
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT/'infrastructure/local-fallback/scripts/host-maintenance149.py'
spec = importlib.util.spec_from_file_location('host149', SOURCE)
host = importlib.util.module_from_spec(spec)
spec.loader.exec_module(host)


class ApprovedPlanTest(unittest.TestCase):
    def setUp(self):
        self.now = dt.datetime(2026, 11, 15, 15, tzinfo=dt.timezone.utc)
        self.plan = {
            'createdAtUtc': self.now.isoformat(),
            'packages': {name: {'before': '2.4.2-3', 'target': '2.4.2-4'} for name in host.PACKAGES},
            'backupDirectory': '/home/alvaro/conectate-backups/20261115T082639Z',
            'isolatedDatabaseRestorePassed': True, 'restoredTables': 265,
            'backupDumpSha256': 'a'*64, 'apiImageId': 'sha256:'+'b'*64,
        }
        for name in ('docker-ce', 'docker-ce-cli', 'docker-ce-rootless-extras'):
            self.plan['packages'][name] = {'before': '5:29.9.2-1~ubuntu.24.04~noble', 'target': '5:29.9.3-1~ubuntu.24.04~noble'}

    def test_reviewed_patch_plan_is_admitted(self):
        host.validate_plan(self.plan, self.now)

    def test_major_or_minor_docker_changes_are_refused(self):
        for version in ('5:30.0.0-1', '5:29.10.0-1', '5:29.9.1-1'):
            with self.subTest(version=version):
                candidate = deepcopy(self.plan)
                candidate['packages']['docker-ce']['target'] = version
                with self.assertRaises(ValueError):
                    host.validate_plan(candidate, self.now)

    def test_an_extra_package_cannot_enter_the_plan(self):
        self.plan['packages']['linux-image-generic'] = {'before': '1', 'target': '2'}
        with self.assertRaises(ValueError):
            host.validate_plan(self.plan, self.now)

    def test_stale_and_future_plans_are_refused(self):
        for offset in (-86401, 301):
            with self.subTest(offset=offset):
                self.plan['createdAtUtc'] = (self.now+dt.timedelta(seconds=offset)).isoformat()
                with self.assertRaises(ValueError):
                    host.validate_plan(self.plan, self.now)

    def test_latest_symlink_is_not_an_immutable_backup_identifier(self):
        self.plan['backupDirectory'] = '/home/alvaro/conectate-backups/latest'
        with self.assertRaises(ValueError):
            host.validate_plan(self.plan, self.now)

    def test_a_backup_manifest_without_a_restore_result_is_insufficient(self):
        self.plan['isolatedDatabaseRestorePassed'] = False
        with self.assertRaises(ValueError):
            host.validate_plan(self.plan, self.now)

    def test_nonroot_apply_stops_before_host_commands(self):
        self.plan['createdAtUtc'] = dt.datetime.now(dt.timezone.utc).isoformat()
        with tempfile.TemporaryDirectory() as directory:
            plan = Path(directory)/'plan.json'
            plan.write_text(json.dumps(self.plan))
            args = ['host149', '--plan', str(plan), '--approved-sha256', host.sha(plan), '--apply']
            with patch.object(sys, 'argv', args), patch.object(host.os, 'geteuid', return_value=1000, create=True), patch.object(host, 'check_host') as check:
                with self.assertRaises(PermissionError):
                    host.main()
                check.assert_not_called()

    def test_check_and_apply_cannot_be_combined(self):
        args = ['host149', '--plan', 'unused.json', '--approved-sha256', 'a'*64, '--check', '--apply']
        with patch.object(sys, 'argv', args), patch.object(host, 'check_host') as check, redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as failure:
                host.main()
            self.assertEqual(2, failure.exception.code)
            check.assert_not_called()

    def test_changed_plan_is_rejected_before_host_commands(self):
        with tempfile.TemporaryDirectory() as directory:
            plan = Path(directory)/'plan.json'; plan.write_text(json.dumps(self.plan))
            args = ['host149', '--plan', str(plan), '--approved-sha256', '0'*64, '--check']
            with patch.object(sys, 'argv', args), patch.object(host, 'check_host') as check:
                with self.assertRaises(ValueError):
                    host.main()
                check.assert_not_called()


class AptPlanTest(unittest.TestCase):
    def test_install_versions_include_epoch_and_architecture(self):
        output = 'Inst docker-ce [5:29.9.2-1] (5:29.9.3-1 Docker CE:noble [amd64])\nInst libsqlite3-0:amd64 [3.45.1-1] (3.45.1-2 Ubuntu [amd64])'
        self.assertEqual({'docker-ce': '5:29.9.3-1', 'libsqlite3-0:amd64': '3.45.1-2'}, host.parse_installs(output))

    def test_new_dependency_remains_visible_for_rejection(self):
        self.assertEqual({'unreviewed-lib': '9.0'}, host.parse_installs('Inst unreviewed-lib (9.0 Ubuntu [amd64])'))

    def test_removals_are_not_hidden_by_valid_install_rows(self):
        with self.assertRaises(ValueError):
            host.parse_installs('Inst docker-ce [1] (2 repo)\nRemv important-service [1]')

    def test_duplicate_rows_fail_closed(self):
        with self.assertRaises(ValueError):
            host.parse_installs('Inst nodejs [1] (2 repo)\nInst nodejs [1] (3 repo)')


class DaemonConfigurationTest(unittest.TestCase):
    def test_unrelated_daemon_options_and_original_mode_survive(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory)/'daemon.json'
            original = {'log-driver': 'local', 'data-root': '/srv/docker', 'live-restore': False}
            config.write_text(json.dumps(original))
            if os.name == 'posix':
                config.chmod(0o640)
            checked = []
            host.write_daemon(config, lambda staged: checked.append(json.loads(staged.read_bytes())))
            self.assertEqual({**original, 'live-restore': True}, json.loads(config.read_bytes()))
            self.assertEqual([json.loads(config.read_bytes())], checked)
            if os.name == 'posix':
                self.assertEqual(0o640, config.stat().st_mode & 0o777)

    def test_validation_failure_preserves_the_exact_original_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory)/'daemon.json'
            original = b'{ "log-driver" : "local" }\n'
            config.write_bytes(original)
            def refuse(staged):
                raise RuntimeError('Rejected by dockerd validator')
            with self.assertRaises(RuntimeError):
                host.write_daemon(config, refuse)
            self.assertEqual(original, config.read_bytes())
            self.assertEqual(['daemon.json'], sorted(path.name for path in Path(directory).iterdir()))

    def test_a_concurrent_config_edit_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory)/'daemon.json'; config.write_bytes(b'{}')
            changed = b'{"log-driver":"journald"}\n'
            with self.assertRaises(ValueError):
                host.write_daemon(config, lambda staged: config.write_bytes(changed))
            self.assertEqual(changed, config.read_bytes())

    def test_malformed_config_is_not_replaced_with_defaults(self):
        for original in (b'broken', b'[]'):
            with self.subTest(original=original), tempfile.TemporaryDirectory() as directory:
                config = Path(directory)/'daemon.json'; config.write_bytes(original)
                with self.assertRaises(ValueError):
                    host.write_daemon(config, lambda staged: None)
                self.assertEqual(original, config.read_bytes())

    @unittest.skipUnless(os.name == 'posix', 'Actual symlink behavior requires POSIX')
    def test_a_symlink_cannot_redirect_the_configuration_write(self):
        with tempfile.TemporaryDirectory() as directory:
            original = Path(directory)/'other.json'; original.write_bytes(b'{}')
            config = Path(directory)/'daemon.json'; config.symlink_to(original)
            with self.assertRaises(ValueError):
                host.write_daemon(config, lambda staged: None)
            self.assertEqual(b'{}', original.read_bytes())
            self.assertTrue(config.is_symlink())


if __name__ == '__main__':
    unittest.main()
