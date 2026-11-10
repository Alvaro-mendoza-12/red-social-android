"""Workflow contract checks; these do not replace Gradle or device tests."""
from pathlib import Path
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[2]


def workflow(name):
    return yaml.safe_load((ROOT / '.github/workflows' / name).read_text())


class AndroidFixtureWorkflowsTest(unittest.TestCase):
    def test_every_synthetic_api_config_overrides_production_discovery(self):
        for name in ('android-ci.yml', 'android-instrumented-smoke.yml', 'manual-build.yml'):
            fixture_steps = [
                step for job in workflow(name)['jobs'].values() for step in job['steps']
                if 'FALLBACK_API_URL=https://api.example.invalid' in step.get('run', '')
            ]
            self.assertTrue(fixture_steps, name)
            for step in fixture_steps:
                with self.subTest(workflow=name, step=step['name']):
                    self.assertIn(
                        'echo "BACKEND_DISCOVERY_HOST=discovery.example.invalid" >> secrets.properties',
                        step['run'],
                    )

    def test_manual_fixture_build_disables_external_crashlytics_uploads(self):
        job = workflow('manual-build.yml')['jobs']['build']
        self.assertEqual('true', job.get('env', {}).get('NOVVIN_CI_FIXTURE'))

    def test_manual_build_bootstraps_test_inputs_before_any_app_build(self):
        steps = workflow('manual-build.yml')['jobs']['build']['steps']
        bootstrap = [i for i, step in enumerate(steps)
                     if step.get('run') == 'python tools/prepare_android_ci.py']
        builds = [i for i, step in enumerate(steps)
                  if ':app:assemble' in step.get('run', '')]
        self.assertEqual(1, len(bootstrap))
        self.assertTrue(builds)
        self.assertNotIn('if', steps[bootstrap[0]])
        self.assertLess(bootstrap[0], min(builds))


if __name__ == '__main__':
    unittest.main()
