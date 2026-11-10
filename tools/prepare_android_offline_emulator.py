#!/usr/bin/env python3
"""Fail closed until the disposable emulator reports all required radios offline."""
import os
import subprocess
import sys
import time


EXPECTED = {'airplane_mode_on': '1', 'wifi_on': '0', 'mobile_data': '0'}


def adb_shell(command, deadline):
    label = ' '.join(command)
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise RuntimeError(f'Offline radio readiness deadline expired before {label}')
    try:
        result = subprocess.run(
            ['adb', '-s', 'emulator-5554', 'shell', *command],
            capture_output=True, text=True, timeout=min(5.0, remaining), check=False,
        )
    except subprocess.TimeoutExpired as error:
        raise RuntimeError(f'adb {label} timed out; offline state is unverified') from error
    except OSError as error:
        raise RuntimeError(f'adb {label} could not run: {error}') from error
    if result.returncode:
        detail = (result.stderr or result.stdout).strip()[:1024]
        raise RuntimeError(f'adb {label} failed (exit {result.returncode}): {detail}')
    if time.monotonic() >= deadline:
        raise RuntimeError(f'Offline radio readiness deadline expired after {label}')
    return result.stdout.strip()


def prepare_offline_emulator():
    # One total budget includes disable requests, reads and polling. adb calls
    # cannot hang indefinitely or allow an incomplete snapshot to pass.
    deadline = time.monotonic() + 30.0
    for command in (
        ['svc', 'wifi', 'disable'],
        ['svc', 'data', 'disable'],
        ['cmd', 'connectivity', 'airplane-mode', 'enable'],
    ):
        print('Radio setup: ' + ' '.join(command), flush=True)
        adb_shell(command, deadline)

    while True:
        states = {key: adb_shell(['settings', 'get', 'global', key], deadline)
                  for key in EXPECTED}
        print('Radio state: ' + ' '.join(f'{key}={value}' for key, value in states.items()), flush=True)
        if states == EXPECTED:
            print('Offline radio state verified before application installation.', flush=True)
            return
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise RuntimeError('Offline radio readiness deadline expired; application remains unlaunched')
        time.sleep(min(1.0, remaining))


def main():
    if not (
        os.environ.get('GITHUB_ACTIONS') == 'true'
        and os.environ.get('GITHUB_REF') == 'refs/heads/correcciones-dot'
        and os.environ.get('NOVVIN_CI_FIXTURE') == 'true'
        and os.environ.get('ANDROID_SERIAL') == 'emulator-5554'
    ):
        print('Offline preparation is restricted to correcciones-dot CI fixtures on emulator-5554.', file=sys.stderr)
        return 2
    try:
        prepare_offline_emulator()
    except RuntimeError as error:
        print(f'ERROR: {error}', file=sys.stderr, flush=True)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
