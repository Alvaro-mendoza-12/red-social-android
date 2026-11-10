#!/usr/bin/env python3
"""Fail closed unless one requested account smoke case actually passed on device."""
import argparse
from pathlib import Path
import xml.etree.ElementTree as ET

CASES = {
    'conectate.com.ui.SavedAccountLogoutLoginFlowTest':
        'login_withSavedAccountFromLoginScreen_returnsToMessages',
    'conectate.com.ui.SecondarySavedAccountLoginFlowTest':
        'login_withSecondarySavedAccountFromLoginScreen_returnsToMessagesWithoutError',
    'conectate.com.ui.MessagesMultiAccountSwitchingTest':
        'multiAccountCenter_switchesMessagesContextWithoutLeavingInbox',
}


def verify_result(report_directory: Path, test_class: str) -> int:
    if test_class not in CASES:
        raise ValueError('Unknown account smoke class')
    reports = list(report_directory.rglob('TEST-*.xml'))
    if not reports:
        raise ValueError('No instrumentation XML report was produced')
    cases = []
    for report in reports:
        root = ET.parse(report).getroot()
        for suite in root.iter('testsuite'):
            if int(suite.get('tests', '-1')) != len(list(suite.iter('testcase'))):
                raise ValueError('Instrumentation execution count does not match its cases')
            if any(int(suite.get(key, '0')) != 0 for key in ('failures', 'errors', 'skipped')):
                raise ValueError('Instrumentation summary contains a failure, error or skip')
        cases.extend(root.iter('testcase'))
    if len(cases) != 1:
        raise ValueError(f'Expected one executed case, found {len(cases)}')
    case = cases[0]
    if case.get('classname') != test_class or case.get('name') != CASES[test_class]:
        raise ValueError('Instrumentation executed a different test case')
    if any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')):
        raise ValueError('The requested case did not pass')
    return 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('test_class', choices=CASES)
    parser.add_argument('--reports', type=Path,
                        default=Path('app/build/outputs/androidTest-results/connected'))
    args = parser.parse_args()
    verify_result(args.reports, args.test_class)
    print(f'PASS: exactly one device case completed: {args.test_class}')


if __name__ == '__main__':
    main()
