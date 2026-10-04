import pathlib
import subprocess
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parents[1]
SOURCE = (SCRIPTS / "deploy-public-feed-api-candidate.sh").read_text()
HELPERS = SOURCE[SOURCE.index("override_for() {"):SOURCE.index("check_image() {")]


class UpgradeGuardTest(unittest.TestCase):
    def test_old_and_new_overrides_preserve_enabled_surveys_and_scanner(self):
        for image in ("old-api", "new-api"):
            script = 'set -Eeuo pipefail\nimage=new-api; previous_image=old-api; scanner_image=unchanged-scanner\n'
            run = subprocess.run(["bash", "-s", "--", image], input=script + HELPERS + '\noverride_for "$1"\n',
                text=True, capture_output=True)
            self.assertEqual(run.returncode, 0, run.stderr)
            self.assertIn('NOVVIN_SURVEYS_ENABLED: "true"', run.stdout)
            self.assertIn('image: "unchanged-scanner"', run.stdout)
            self.assertIn('image: "' + image + '"', run.stdout)
            self.assertNotIn('"false"', run.stdout)

    def test_unknown_images_and_foreign_override_settings_are_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            script = 'set -Eeuo pipefail\nroot="$1"; image=new-api; previous_image=old-api; scanner_image=scanner\n' + HELPERS
            bad = subprocess.run(["bash", "-s", "--", root], input=script + '\noverride_for foreign-api\n',
                text=True, capture_output=True)
            self.assertNotEqual(bad.returncode, 0)
            for alteration in ('echo foreign >>"$root/docker-compose.override.yml"',
                'sed -i "s/\\\"true\\\"/\\\"false\\\"/" "$root/docker-compose.override.yml"'):
                run = subprocess.run(["bash", "-s", "--", root],
                    input=script + '\noverride_for "$image" >"$root/docker-compose.override.yml"\n' + alteration + '\napproved_override\n',
                    text=True, capture_output=True)
                self.assertNotEqual(run.returncode, 0, run.stderr)

    def test_conditional_rollback_restores_full_override_and_stops_at_failures(self):
        rollback = SOURCE[SOURCE.index("rollback() {"):SOURCE.index('if [[ "$mode" == rollback ]]')]
        for stage in ("ps", "inspect", "validator", "tar", "config", "up", "ready", "edge", "none", "absent", "foreign-image", "foreign-settings", "wrong-restoration"):
            with self.subTest(stage=stage), tempfile.TemporaryDirectory() as temp:
                script = r'''
set -Eeuo pipefail
root="$1"; release="$1"; stage="$2"; validator=unused
image=new-api; previous_image=old-api; scanner_image=unchanged-scanner
python3() { echo validator >>"$root/trace"; [[ "$stage" != validator ]]; }
tar() {
  echo tar >>"$root/trace"; [[ "$stage" != tar ]] || return 1
  if [[ "$stage" == wrong-restoration ]]; then echo foreign >"$root/docker-compose.override.yml"
  else override_for "$previous_image" >"$root/docker-compose.override.yml"; fi
}
docker() {
  local step=unknown
  if [[ "$1" == ps ]]; then step=ps; [[ "$stage" == absent ]] || echo container; fi
  if [[ "$1" == inspect ]]; then step=inspect; if [[ "$stage" == foreign-image ]]; then echo foreign; else echo new-api; fi; fi
  [[ "$*" == *" config "* ]] && step=config
  [[ "$*" == *" up "* ]] && step=up
  echo "$step" >>"$root/trace"; [[ "$stage" != "$step" ]]
}
wait_ready() { echo ready >>"$root/trace"; [[ "$stage" != ready ]]; }
wait_edge_ready() { echo edge >>"$root/trace"; [[ "$stage" != edge ]]; }
'''
                script += HELPERS + rollback + r'''
override_for "$image" >"$root/docker-compose.override.yml"
if [[ "$stage" == foreign-settings ]]; then echo foreign >>"$root/docker-compose.override.yml"; fi
if rollback; then exit 0; else exit 42; fi
'''
                run = subprocess.run(["bash", "-s", "--", temp, stage], input=script, text=True, capture_output=True)
                self.assertEqual(run.returncode, 0 if stage in ("none", "absent") else 42, run.stderr)
                tracefile = pathlib.Path(temp) / "trace"
                trace = tracefile.read_text().splitlines() if tracefile.exists() else []
                expected = ["ps", "inspect", "validator", "tar", "config", "up", "ready", "edge"]
                if stage == "foreign-settings": self.assertEqual(trace, [])
                elif stage == "foreign-image": self.assertEqual(trace, expected[:2])
                elif stage == "wrong-restoration": self.assertEqual(trace, expected[:4])
                elif stage == "absent": self.assertEqual(trace, [x for x in expected if x != "inspect"])
                elif stage == "none": self.assertEqual(trace, expected)
                else: self.assertEqual(trace, expected[:expected.index(stage) + 1])
                if stage in ("none", "absent"):
                    restored = (pathlib.Path(temp) / "docker-compose.override.yml").read_text()
                    self.assertIn('image: "old-api"', restored)
                    self.assertIn('NOVVIN_SURVEYS_ENABLED: "true"', restored)
                    self.assertIn('image: "unchanged-scanner"', restored)


if __name__ == "__main__":
    unittest.main()
