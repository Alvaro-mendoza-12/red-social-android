import importlib.util
import io
import pathlib
import subprocess
import tempfile
import tarfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("validator", SCRIPTS / "validate-survey-release-archive.py")
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)


class ArchiveValidationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name).resolve()

    def archive(self, records, filename="candidate.tar"):
        target = self.root / filename
        with tarfile.open(target, "w") as archive:
            for name, body, kind in records:
                entry = tarfile.TarInfo(name)
                entry.type = kind
                entry.mode = 0o644
                entry.size = len(body) if kind == tarfile.REGTYPE else 0
                if kind in (tarfile.SYMTYPE, tarfile.LNKTYPE):
                    entry.linkname = "/etc/passwd"
                archive.addfile(entry, io.BytesIO(body) if entry.isfile() else None)
        return target

    def file(self, name, body=b"original"):
        target = self.root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(body)
        return target

    def test_regular_scoped_archive_is_accepted(self):
        archive = self.archive([("api/src/server.js", b"code", tarfile.REGTYPE)])
        validator.run("candidate", self.root, archive)

    def test_forbidden_paths_are_rejected(self):
        for name in ("../escape", "/etc/passwd", "api/src/../file", "api/.env", "api/node_modules/pkg.js", "api/src\\file", "survey-antivirus/.env"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                validator.run("candidate", self.root, self.archive([(name, b"x", tarfile.REGTYPE)]))

    def test_links_and_special_members_are_rejected(self):
        for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.CHRTYPE, tarfile.FIFOTYPE):
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                validator.run("candidate", self.root, self.archive([("api/src/linked", b"", kind)]))

    def test_duplicate_members_are_rejected(self):
        record = ("api/src/file.js", b"x", tarfile.REGTYPE)
        with self.assertRaises(ValueError):
            validator.run("candidate", self.root, self.archive([record, record]))

    def test_empty_archive_is_rejected(self):
        with self.assertRaises(ValueError):
            validator.run("candidate", self.root, self.archive([]))

    def test_symlink_in_target_parents_is_rejected(self):
        outside = self.root / "outside"
        outside.mkdir()
        (self.root / "api").mkdir()
        (self.root / "api/src").symlink_to(outside, target_is_directory=True)
        with self.assertRaises(ValueError):
            validator.run("candidate", self.root, self.archive([("api/src/file.js", b"x", tarfile.REGTYPE)]))
        self.assertEqual(list(outside.iterdir()), [])

    def test_special_permission_bits_are_rejected(self):
        archive = self.root / "candidate.tar"
        with tarfile.open(archive, "w") as target:
            entry = tarfile.TarInfo("api/src/file.js")
            entry.mode = 0o4644
            entry.size = 1
            target.addfile(entry, io.BytesIO(b"x"))
        with self.assertRaises(ValueError):
            validator.run("candidate", self.root, archive)

    def test_snapshot_can_include_marker_but_candidate_cannot(self):
        archive = self.archive([(".conectate-release", b"old", tarfile.REGTYPE)])
        validator.run("snapshot", self.root, archive)
        with self.assertRaises(ValueError):
            validator.run("candidate", self.root, archive)

    def test_directory_cannot_replace_regular_file(self):
        self.file("api/src/file.js")
        with self.assertRaises(ValueError):
            validator.run("candidate", self.root, self.archive([("api/src/file.js", b"", tarfile.DIRTYPE)]))

    def test_regular_file_cannot_replace_directory(self):
        (self.root / "api/src").mkdir(parents=True)
        with self.assertRaises(ValueError):
            validator.run("candidate", self.root, self.archive([("api/src", b"x", tarfile.REGTYPE)]))

    def test_original_changes_abort_deployment(self):
        target = self.file("api/src/server.js")
        archive = self.archive([("api/src/server.js", b"original", tarfile.REGTYPE)], "old.tar")
        validator.run("unchanged", self.root, archive)
        target.write_bytes(b"changed")
        with self.assertRaises(ValueError):
            validator.run("unchanged", self.root, archive)

    def test_candidate_changes_abort_activation(self):
        target = self.file("survey-antivirus/start.sh", b"candidate")
        archive = self.archive([("survey-antivirus/start.sh", b"candidate", tarfile.REGTYPE)])
        validator.run("unchanged-candidate", self.root, archive)
        target.write_bytes(b"changed")
        with self.assertRaises(ValueError):
            validator.run("unchanged-candidate", self.root, archive)

    def test_rollback_removes_only_unchanged_added_files(self):
        self.file("api/src/server.js", b"new")
        added = self.file("api/src/new.js", b"added")
        foreign = self.file("api/src/unrelated.js", b"other")
        old = self.archive([("api/src/server.js", b"original", tarfile.REGTYPE)], "old.tar")
        candidate = self.archive([("api/src/server.js", b"new", tarfile.REGTYPE), ("api/src/new.js", b"added", tarfile.REGTYPE)])
        validator.run("remove-added", self.root, old, candidate)
        self.assertFalse(added.exists())
        self.assertTrue(foreign.exists())
        self.assertEqual((self.root / "api/src/server.js").read_bytes(), b"new")

    def test_foreign_added_changes_abort_before_any_removal(self):
        first = self.file("api/src/first.js", b"added")
        second = self.file("api/src/second.js", b"modified")
        old = self.archive([("api/src/server.js", b"original", tarfile.REGTYPE)], "old.tar")
        candidate = self.archive([("api/src/first.js", b"added", tarfile.REGTYPE), ("api/src/second.js", b"added", tarfile.REGTYPE)])
        with self.assertRaises(ValueError):
            validator.run("remove-added", self.root, old, candidate)
        self.assertTrue(first.exists())
        self.assertTrue(second.exists())


class RollbackGuardTest(unittest.TestCase):
    def test_conditional_rollback_stops_at_each_failed_stage(self):
        source = (SCRIPTS / "deploy-surveys-api-candidate.sh").read_text()
        function = source[source.index("rollback() {"):source.index('if [[ "$mode" == rollback ]]')]
        helpers = source[source.index("candidate_override() {"):source.index('if [[ "$mode" == prepare ]]')]
        for stage in ("ps", "inspect", "python3", "tar", "stop", "config", "up", "ready", "edge-ready", "none", "absent", "foreign", "enabled-false", "enabled-true", "foreign-override"):
            with self.subTest(stage=stage), tempfile.TemporaryDirectory() as temp:
                root = pathlib.Path(temp).resolve()
                # Never invokes Docker, tar extraction, or the real validator.
                shell = r'''
set -Eeuo pipefail
root="$1"; release="$1"; validator=unused; previous_image=unused; api_image=candidate; scanner_image=scanner; stage="$2"
python3() { echo python3 >>"$root/trace"; [[ "$stage" != python3 ]]; }
tar() { echo tar >>"$root/trace"; [[ "$stage" != tar ]]; }
docker() { local action="${@: -1}"; [[ "$*" == *" config "* ]] && action=config; [[ "$*" == *" up "* ]] && action=up; [[ "$*" == *" stop "* ]] && action=stop; if [[ "$1" == ps ]]; then action=ps; [[ "$stage" == absent ]] || echo container; fi; if [[ "$1" == inspect ]]; then action=inspect; if [[ "$stage" == foreign ]]; then echo foreign; else echo unused; fi; fi; echo "$action" >>"$root/trace"; [[ "$stage" != "$action" ]]; }
wait_ready() { echo ready >>"$root/trace"; [[ "$stage" != ready ]]; }
wait_edge_ready() { echo edge-ready >>"$root/trace"; [[ "$stage" != edge-ready ]]; }
'''
                shell += helpers + function + '''
case "$stage" in
 enabled-false) candidate_override false >"$root/docker-compose.override.yml" ;;
 enabled-true) candidate_override true >"$root/docker-compose.override.yml" ;;
 foreign-override) echo unknown >"$root/docker-compose.override.yml" ;;
esac
if rollback; then exit 0; else exit 42; fi
'''
                result = subprocess.run(["bash", "-s", "--", str(root), stage], input=shell, text=True, capture_output=True)
                success = stage in ("none", "absent", "enabled-false", "enabled-true")
                self.assertEqual(result.returncode, 0 if success else 42, result.stderr)
                trace = (root / "trace").read_text().splitlines() if (root / "trace").exists() else []
                expected = ["ps", "inspect", "python3", "tar", "stop", "config", "up", "ready", "edge-ready"]
                if stage == "absent":
                    self.assertEqual(trace, [item for item in expected if item != "inspect"])
                elif stage == "foreign":
                    self.assertEqual(trace, expected[:2])
                elif stage == "foreign-override":
                    self.assertEqual(trace, [])
                else:
                    self.assertEqual(trace, expected if success else expected[:expected.index(stage) + 1])

    def check_override(self, variant, modification="", expected=0):
        source = (SCRIPTS / "deploy-surveys-api-candidate.sh").read_text()
        helpers = source[source.index("candidate_override() {"):source.index('if [[ "$mode" == prepare ]]')]
        with tempfile.TemporaryDirectory() as temp:
            script = 'set -Eeuo pipefail\nroot="$1"; api_image=expected-api; scanner_image=expected-scanner\n' + helpers
            script += '\ncandidate_override "$2" >"$root/docker-compose.override.yml"\n' + modification + '\napproved_override\n'
            result = subprocess.run(["bash", "-s", "--", temp, variant], input=script, text=True, capture_output=True)
            self.assertEqual(result.returncode, expected, result.stderr)
            return result.stdout.strip()

    def test_approved_false_and_true_keep_exact_images(self):
        self.assertEqual(self.check_override("false"), "false")
        self.assertEqual(self.check_override("true"), "true")

    def test_foreign_image_or_settings_are_not_owned(self):
        self.check_override("true", 'sed -i "s/expected-api/foreign-api/" "$root/docker-compose.override.yml"', expected=1)
        self.check_override("false", 'echo "foreign setting" >>"$root/docker-compose.override.yml"', expected=1)

    def test_linked_override_is_rejected(self):
        self.check_override("false", 'mv "$root/docker-compose.override.yml" "$root/linked.yml"; ln -s "$root/linked.yml" "$root/docker-compose.override.yml"', expected=1)


class WatchdogRecheckTest(unittest.TestCase):
    def check_recovery(self, stage, expected):
        source = (SCRIPTS.parent / "infrastructure/local-fallback/scripts/health-watchdog.sh").read_text()
        start = source.index("  (\n")
        recovery = source[start:source.index('  ) 9>', start) + 3]
        with tempfile.TemporaryDirectory() as temp:
            shell = r'''
set -Eeuo pipefail
root="$1"; stage="$2"
flock() { echo flock >>"$root/trace"; }
healthy() { echo healthy >>"$root/trace"; [[ "$stage" == healed ]]; }
docker() {
  local step=unknown
  [[ "$*" == *" up "* ]] && step=up
  [[ "$*" == *" nginx -t" ]] && step=nginx-test
  [[ "$*" == *" nginx -s reload" ]] && step=nginx-reload
  [[ "$*" == *" restart edge" ]] && step=restart
  echo "$step" >>"$root/trace"
  [[ "$stage" != "$step" ]]
}
'''
            run = subprocess.run(["bash", "-s", "--", temp, stage], input=shell + recovery,
                text=True, capture_output=True)
            self.assertEqual(run.returncode, 0, run.stderr)
            self.assertEqual((pathlib.Path(temp) / "trace").read_text().splitlines(), expected)
            return run.stdout

    def test_recovered_while_waiting_does_not_touch_compose_or_proxy(self):
        self.assertIn("RECOVERY_SKIPPED", self.check_recovery("healed", ["flock", "healthy"]))

    def test_recovery_reloads_proxy_without_stopping_workers(self):
        self.check_recovery("degraded", ["flock", "healthy", "up", "nginx-test", "nginx-reload"])

    def test_invalid_proxy_configuration_uses_restart_fallback(self):
        self.check_recovery("nginx-test", ["flock", "healthy", "up", "nginx-test", "restart"])

    def test_failed_graceful_reload_uses_restart_fallback(self):
        self.check_recovery("nginx-reload", ["flock", "healthy", "up", "nginx-test", "nginx-reload", "restart"])


if __name__ == "__main__":
    unittest.main()
