"""Reject green-looking instrumentation runs that did not pass the requested case."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'verify_account_smoke_result.py'
CLASS = 'conectate.com.ui.SavedAccountLogoutLoginFlowTest'
METHOD = 'login_withSavedAccountFromLoginScreen_returnsToMessages'


class VerifyAccountSmokeResultTest(unittest.TestCase):
    def run_check(self, xml):
        self.assertTrue(SCRIPT.exists(), 'Missing result verifier')
        spec = importlib.util.spec_from_file_location('account_smoke_verifier', SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory:
            if xml is not None:
                Path(directory, 'TEST-account.xml').write_text(xml)
            return module.verify_result(Path(directory), CLASS)

    def report(self, case='', **counts):
        attrs = {'tests': '1', 'failures': '0', 'errors': '0', 'skipped': '0', **counts}
        attributes = ' '.join(f'{key}="{value}"' for key, value in attrs.items())
        return f'<testsuite {attributes}><testcase classname="{CLASS}" name="{METHOD}">{case}</testcase></testsuite>'

    def test_accepts_exactly_the_requested_passing_case(self):
        self.assertEqual(1, self.run_check(self.report()))

    def test_rejects_missing_report(self):
        with self.assertRaisesRegex(ValueError, 'report'):
            self.run_check(None)

    def test_rejects_zero_executed_tests(self):
        with self.assertRaises(ValueError):
            self.run_check('<testsuite tests="0" failures="0" errors="0" skipped="0"/>')

    def test_rejects_failure_even_if_summary_counts_are_green(self):
        with self.assertRaisesRegex(ValueError, 'did not pass'):
            self.run_check(self.report('<failure message="UI not shown"/>'))

    def test_rejects_skipped_case(self):
        with self.assertRaises(ValueError):
            self.run_check(self.report('<skipped/>', skipped='1'))

    def test_rejects_summary_error(self):
        with self.assertRaises(ValueError):
            self.run_check(self.report(errors='1'))

    def test_rejects_inconsistent_execution_count(self):
        with self.assertRaises(ValueError):
            self.run_check(self.report(tests='0'))

    def test_rejects_another_case(self):
        with self.assertRaises(ValueError):
            self.run_check(self.report().replace(METHOD, 'unrelatedTest'))

    def test_rejects_duplicate_execution(self):
        xml = self.report()
        case = f'<testcase classname="{CLASS}" name="{METHOD}"/>'
        with self.assertRaises(ValueError):
            self.run_check(xml.replace('</testsuite>', case + '</testsuite>'))
