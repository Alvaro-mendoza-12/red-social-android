#!/usr/bin/env python3
"""Apply a run-approved, runner-only KVM ACL and verify its later restoration."""
import argparse
import os
from pathlib import Path
import subprocess


DEVICE = Path('/dev/kvm')
BASE_ACL = 'user::rw-\ngroup::rw-\nother::---\n'


def output(args):
    return subprocess.run(args, check=True, text=True, capture_output=True).stdout.strip()


def current_acl():
    return output(['getfacl', '-c', '-p', str(DEVICE)]) + '\n'


def manage(operation):
    required = {
        'GITHUB_ACTIONS': 'true', 'GITHUB_REF': 'refs/heads/correcciones-dot',
        'GITHUB_JOB': 'saved-account-tests', 'RUNNER_ENVIRONMENT': 'github-hosted',
        'NOVVIN_CI_FIXTURE': 'true', 'NOVVIN_KVM_ACCESS_APPROVED': 'true',
    }
    if operation not in ('grant', 'restore') or any(os.environ.get(key) != value for key, value in required.items()):
        raise ValueError('Temporary KVM access requires explicit approval for this hosted correction-branch run.')
    uid = os.getuid()
    if uid == 0 or not os.environ.get('RUNNER_TEMP'):
        raise ValueError('Expected the unprivileged hosted job user and runner temporary directory.')
    snapshot = Path(os.environ['RUNNER_TEMP']) / 'novvin-kvm-original.acl'
    if operation == 'restore' and not snapshot.exists():
        print('No KVM ACL change was attempted; no restoration needed.')
        return
    if not DEVICE.is_char_device():
        raise ValueError('/dev/kvm must already exist as a character device.')
    if operation == 'grant':
        if output(['stat', '-c', '%a %U:%G', str(DEVICE)]) != '660 root:kvm' or current_acl() != BASE_ACL:
            raise ValueError('KVM permissions differ from the approved baseline; refusing to change them.')
        # Record before attempting sudo so even a partially failed grant is restored.
        fd = os.open(snapshot, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as stream:
            stream.write(BASE_ACL)
        subprocess.run(['sudo', '-n', 'setfacl', '-m', f'u:{uid}:rw', str(DEVICE)], check=True)
        if not os.access(DEVICE, os.R_OK | os.W_OK):
            raise ValueError('Approved runner access was not established.')
        print(f'Temporary KVM read/write access granted only to job UID {uid}.')
        print(current_acl())
    else:
        if snapshot.read_text() != BASE_ACL:
            raise ValueError('Unexpected KVM snapshot; refusing to apply unrelated permissions.')
        subprocess.run(['sudo', '-n', 'setfacl', '--set-file', str(snapshot), str(DEVICE)], check=True)
        if current_acl() != BASE_ACL or output(['stat', '-c', '%a %U:%G', str(DEVICE)]) != '660 root:kvm':
            raise ValueError('KVM ACL restoration could not be verified.')
        print('KVM ACL restored and verified: root:kvm 660, no named-user access.')
        snapshot.unlink()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('grant', 'restore'))
    manage(parser.parse_args().operation)
