import base64
import importlib.util
import json
import pathlib
import tempfile
import unittest
from unittest.mock import patch
PATH = pathlib.Path(__file__).resolve().parents[1] / 'prepare_android_ci.py'

class PrepareAndroidCiTest(unittest.TestCase):
    def module(self):
        spec = importlib.util.spec_from_file_location('prepare_ci', PATH)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_fixture_matches_package_and_is_obviously_nonproduction(self):
        config = self.module().ci_google_services()
        self.assertEqual('novvin-ci-invalid', config['project_info']['project_id'])
        self.assertEqual('conectate.com', config['client'][0]['client_info']['android_client_info']['package_name'])

    def test_production_requires_valid_explicit_configuration(self):
        module = self.module()
        for invalid in ('', 'not base64', base64.b64encode(b'{}').decode(), base64.b64encode(json.dumps(module.ci_google_services()).encode()).decode()):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                module.production_google_services(invalid)

    def test_valid_production_configuration_is_accepted_without_replacement(self):
        module = self.module()
        config = module.ci_google_services()
        config['project_info']['project_id'] = 'example-production'
        encoded = base64.b64encode(json.dumps(config).encode()).decode()
        self.assertEqual(config, module.production_google_services(encoded))

    def test_prepare_writes_fixture_and_requests_ephemeral_debug_key(self):
        module = self.module()
        with tempfile.TemporaryDirectory() as tmp, patch.object(module.subprocess, 'run') as run:
            root = pathlib.Path(tmp)
            module.prepare(root, production=False)
            self.assertEqual(module.ci_google_services(), json.loads((root / 'app/google-services.json').read_text()))
            self.assertIn('-genkeypair', run.call_args.args[0])
            self.assertIn(str(root / 'keystores/debug.keystore'), run.call_args.args[0])

    def test_existing_local_files_are_never_overwritten(self):
        module = self.module()
        with tempfile.TemporaryDirectory() as tmp, patch.object(module.subprocess, 'run') as run:
            root = pathlib.Path(tmp)
            (root / 'app').mkdir()
            (root / 'keystores').mkdir()
            (root / 'app/google-services.json').write_text('owned config')
            (root / 'keystores/debug.keystore').write_text('owned key')
            module.prepare(root, production=False)
            self.assertEqual('owned config', (root / 'app/google-services.json').read_text())
            run.assert_not_called()
