import json
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]


class CiNodeRuntimeTests(unittest.TestCase):
    def test_api_declared_runtime_supports_locked_firebase_admin(self):
        folder = ROOT / 'infrastructure/local-fallback/api'
        package = json.loads((folder / 'package.json').read_text())
        lock = json.loads((folder / 'package-lock.json').read_text())
        required = lock['packages']['node_modules/firebase-admin']['engines']['node']
        declared = package['engines']['node']
        self.assertGreaterEqual(int(re.fullmatch(r'>=(\d+)', declared).group(1)),
                                int(re.fullmatch(r'>=(\d+)', required).group(1)))

    def test_android_workflow_node_runtimes_support_locked_firebase_admin(self):
        lock = json.loads((ROOT / 'infrastructure/local-fallback/api/package-lock.json').read_text())
        requirement = lock['packages']['node_modules/firebase-admin']['engines']['node']
        required_major = int(re.fullmatch(r'>=(\d+)(?:\.\d+){0,2}', requirement).group(1))
        for name in ('android-ci.yml', 'android-production-release.yml'):
            workflow = (ROOT / '.github/workflows' / name).read_text()
            versions = re.findall(r'node-version:\s*[\"\']?(\d+)', workflow)
            self.assertTrue(versions, name)
            for version in versions:
                with self.subTest(workflow=name, runtime=version):
                    self.assertGreaterEqual(int(version), required_major)


if __name__ == '__main__':
    unittest.main()
