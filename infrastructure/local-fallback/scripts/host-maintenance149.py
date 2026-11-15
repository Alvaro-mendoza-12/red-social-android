#!/usr/bin/env python3
"""Apply one approved Ubuntu package plan. No release upgrade, reboot or prune.

Run --check as the operator first. Applying needs real root privileges and a
detached systemd unit; the script never tries to obtain or bypass privileges.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import tempfile
import time

PACKAGES = {
    'docker-ce', 'docker-ce-cli', 'containerd.io', 'libsqlite3-0',
    'docker-buildx-plugin', 'docker-ce-rootless-extras',
    'docker-compose-plugin', 'nodejs', 'tailscale',
}
API = 'conectate-local-fallback-api-main'
READY = 'https://conectate.tail9b267c.ts.net/ready'


def sha(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def parse_installs(output: str) -> dict[str, str]:
    if re.search(r'^Remv\s', output, re.M):
        raise ValueError('The package plan would remove packages')
    rows = re.findall(r'^Inst (\S+)(?: \[[^\]]+\])? \((\S+)', output, re.M)
    if len(rows) != len({name for name, _ in rows}):
        raise ValueError('Duplicate package entries')
    return dict(rows)


def docker_patch(version: str) -> tuple[int, int, int]:
    match = re.fullmatch(r'(?:\d+:)?(\d+)\.(\d+)\.(\d+)-[^\s]+', version)
    if not match:
        raise ValueError('Unrecognized Docker package version')
    return tuple(map(int, match.groups()))


def validate_plan(plan: dict, now: dt.datetime) -> None:
    created = dt.datetime.fromisoformat(plan['createdAtUtc'])
    if created.tzinfo is None or not -300 <= (now - created).total_seconds() <= 86400:
        raise ValueError('The approved plan is stale or has no valid timestamp')
    packages = plan['packages']
    if set(packages) != PACKAGES:
        raise ValueError('Exactly the nine reviewed packages are required')
    for versions in packages.values():
        if set(versions) != {'before', 'target'} or not all(
            re.fullmatch(r'[A-Za-z0-9:.+~_-]+', version) for version in versions.values()
        ) or versions['before'] == versions['target']:
            raise ValueError('Invalid or unchanged package version')
    for name in ('docker-ce', 'docker-ce-cli', 'docker-ce-rootless-extras'):
        before, target = (docker_patch(packages[name][key]) for key in ('before', 'target'))
        if before[:2] != target[:2] or target[2] <= before[2]:
            raise ValueError('Only a forward Docker patch update is supported')
    if not re.fullmatch(r'/home/alvaro/conectate-backups/20\d{6}T\d{6}Z', plan['backupDirectory']):
        raise ValueError('Expected a dated backup in the dedicated directory')
    if plan.get('isolatedDatabaseRestorePassed') is not True or plan.get('restoredTables', 0) <= 0:
        raise ValueError('A successful isolated database restore is required')
    for key in ('backupDumpSha256', 'apiImageId'):
        value = plan[key].removeprefix('sha256:')
        if not re.fullmatch('[a-f0-9]{64}', value):
            raise ValueError('Missing immutable backup/image fingerprint')


def daemon_bytes(original: bytes) -> bytes:
    config = json.loads(original)
    if not isinstance(config, dict):
        raise ValueError('Docker daemon configuration must be an object')
    return (json.dumps({**config, 'live-restore': True}, indent=2) + '\n').encode()


def write_daemon(path: Path, validate) -> None:
    """Stage and validate before replacing; preserve unrelated configuration."""
    if path.is_symlink() or path.parent.is_symlink():
        raise ValueError('Review symlinked Docker configuration manually')
    existed = path.exists()
    original = path.read_bytes() if existed else b'{}'
    stat = path.stat() if existed else None
    replacement = daemon_bytes(original)
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix='.novvin149-', delete=False) as stream:
        staged = Path(stream.name)
        stream.write(replacement)
        stream.flush()
        os.fsync(stream.fileno())
    try:
        os.chmod(staged, (stat.st_mode & 0o777) if stat else 0o644)
        if stat and hasattr(os, 'chown'):
            os.chown(staged, stat.st_uid, stat.st_gid)
        validate(staged)
        if path.is_symlink() or path.exists() != existed or (existed and path.read_bytes() != original):
            raise ValueError('Docker configuration changed during validation')
        os.replace(staged, path)
    finally:
        # Only our uniquely created staging file, never a backup or daemon file.
        staged.unlink(missing_ok=True)


def containers(run) -> dict:
    ids = run(['docker', 'ps', '-q']).split()
    if not ids:
        raise ValueError('No running containers; review the host manually')
    rows = json.loads(run(['docker', 'inspect', *ids]))
    return {row['Name'].lstrip('/'): {
        'id': row['Id'], 'imageId': row['Image'],
        'startedAt': row['State']['StartedAt'], 'running': row['State']['Running'],
        'health': row['State'].get('Health', {}).get('Status'),
        'oomKilled': row['State']['OOMKilled'], 'restarts': row['RestartCount'],
    } for row in rows}


def check_host(plan: dict, run) -> dict:
    if socket.gethostname() != 'conectate':
        raise ValueError('Unexpected host')
    release = dict(line.split('=', 1) for line in Path('/etc/os-release').read_text().splitlines() if '=' in line)
    if release.get('ID', '').strip('"') != 'ubuntu' or release.get('VERSION_ID', '').strip('"') != '24.04':
        raise ValueError('Expected Ubuntu 24.04')
    if shutil.disk_usage('/').free < 5 * 1024**3:
        raise ValueError('At least 5 GiB free is required')
    if run(['dpkg', '--audit']).strip():
        raise ValueError('Existing incomplete package work')
    if run(['systemctl', '--failed', '--no-legend']).strip():
        raise ValueError('Existing failed services must be reviewed first')
    for name, versions in plan['packages'].items():
        installed = run(['dpkg-query', '-W', '-f=${Version}', name]).strip()
        if installed != versions['before']:
            raise ValueError(f'Installed version changed: {name}')
    before = containers(run)
    api = before.get(API, {})
    if api.get('imageId') != plan['apiImageId'] or api.get('health') != 'healthy' or api.get('oomKilled'):
        raise ValueError('API baseline is not the approved healthy image')
    backup = Path(plan['backupDirectory'])
    if backup.resolve() != backup or sha(backup/'postgres.dump') != plan['backupDumpSha256']:
        raise ValueError('Backup changed')
    metadata = json.loads((backup/'metadata.json').read_text())
    created = dt.datetime.fromisoformat(metadata['createdAt'].replace('Z', '+00:00'))
    if not 0 <= (dt.datetime.now(dt.timezone.utc) - created).total_seconds() <= 86400:
        raise ValueError('The backup is older than 24 hours')
    run(['sha256sum', '--check', 'SHA256SUMS'], cwd=backup)
    run(['curl', '--fail', '--silent', '--show-error', '--connect-timeout', '5', '--max-time', '10', READY])
    targets = [f'{name}={versions["target"]}' for name, versions in sorted(plan['packages'].items())]
    simulation = run(['apt-get', '-s', '--no-remove', '--only-upgrade', 'install', *targets])
    if parse_installs(simulation) != {name: row['target'] for name, row in plan['packages'].items()}:
        raise ValueError('APT would change packages outside the reviewed plan')
    rollback_and_targets = [f'{name}={version}' for name, versions in sorted(plan['packages'].items())
        for version in versions.values()]
    run(['apt-get', '--print-uris', 'download', *rollback_and_targets])
    return before


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plan', required=True, type=Path)
    parser.add_argument('--approved-sha256', required=True)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--check', action='store_true', help='read-only preflight (the default)')
    mode.add_argument('--apply', action='store_true', help='requires real root privileges')
    args = parser.parse_args()
    if sha(args.plan) != args.approved_sha256:
        raise ValueError('Approved plan fingerprint differs')
    plan = json.loads(args.plan.read_text())
    validate_plan(plan, dt.datetime.now(dt.timezone.utc))
    if args.apply and os.geteuid() != 0:
        raise PermissionError('Real administrator privileges are required; no packages changed')
    log_dir = None
    report = {'success': False, 'applyRequested': args.apply, 'packagesChanged': False}

    def run(command, *, cwd=None):
        # Never put a timeout around apt/dpkg installation: a lost SSH session
        # must not kill package configuration. Invoke --apply through systemd-run.
        value = subprocess.run(command, cwd=cwd, capture_output=True, text=True,
            env={**os.environ, 'LC_ALL': 'C', 'DEBIAN_FRONTEND': 'noninteractive', 'NEEDRESTART_MODE': 'l'})
        if log_dir and command[:2] != ['docker', 'inspect']:
            with (log_dir/'commands.log').open('a') as stream:
                stream.write(json.dumps(command) + '\n' + value.stdout + value.stderr + '\n')
        if value.returncode:
            raise RuntimeError(f'Command failed ({value.returncode}): {command[0]} {command[1]}')
        return value.stdout

    before = check_host(plan, run)
    if not args.apply:
        print(json.dumps({'preflightPassed': True, 'hostPackagesChanged': False, 'api': before[API]}))
        return 0
    import fcntl
    runtime = Path('/run/novvin-host149')
    runtime.mkdir(mode=0o700, exist_ok=True)
    if runtime.is_symlink() or runtime.stat().st_uid != 0 or runtime.stat().st_mode & 0o077:
        raise ValueError('Expected a private root-owned runtime directory')
    descriptor = os.open(runtime/'maintenance.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        # Recheck after acquiring the host lock. Nothing was mutated by preflight.
        before = check_host(plan, run)
        stamp = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
        log_dir = Path('/var/backups') / f'novvin-host149-{stamp}'
        log_dir.mkdir(mode=0o700)
        os.umask(0o077)
        shutil.copyfile(args.plan, log_dir/'approved-plan.json')
        try:
            run(['apt-get', 'update'])
            check_host(plan, run)
            debs = log_dir/'packages'; debs.mkdir(mode=0o700)
            # Retain both versions before changing config or packages. If the
            # prior version is no longer obtainable, stop for manual review.
            for name, versions in sorted(plan['packages'].items()):
                for version in versions.values():
                    run(['apt-get', 'download', f'{name}={version}'], cwd=debs)
            report['debs'] = {path.name: sha(path) for path in debs.glob('*.deb')}
            config = Path('/etc/docker/daemon.json')
            if config.is_symlink() or config.parent.is_symlink():
                raise ValueError('Review symlinked Docker configuration manually')
            config.parent.mkdir(exist_ok=True)
            report['daemonOriginallyPresent'] = config.exists()
            if config.exists():
                shutil.copyfile(config, log_dir/'daemon-before.json')
            else:
                (log_dir/'daemon-before-absent').touch()
            write_daemon(config, lambda staged: run(['dockerd', '--validate', '--config-file', str(staged)]))
            report['daemonConfigChanged'] = True
            run(['systemctl', 'reload', 'docker'])
            if run(['docker', 'info', '--format', '{{.LiveRestoreEnabled}}']).strip() != 'true':
                raise ValueError('Docker live restore was not enabled')
            if containers(run) != before:
                raise ValueError('Container state changed during daemon reload')
            targets = [f'{name}={row["target"]}' for name, row in sorted(plan['packages'].items())]
            report['packageInstallationStarted'] = True
            report['packagesChanged'] = None  # A failed dpkg run may be partially applied.
            run(['apt-get', '-y', '--no-remove', '--only-upgrade', '-o', 'DPkg::Lock::Timeout=60',
                '-o', 'Dpkg::Options::=--force-confdef', '-o', 'Dpkg::Options::=--force-confold', 'install', *targets])
            report['packagesChanged'] = True
            if run(['dpkg', '--audit']).strip():
                raise ValueError('dpkg reports incomplete package work')
            for name, versions in plan['packages'].items():
                if run(['dpkg-query', '-W', '-f=${Version}', name]).strip() != versions['target']:
                    raise ValueError(f'Target version not active: {name}')
            after = containers(run)
            report.update({'containersBefore': before, 'containersAfter': after,
                'containerIdentitiesAndStartsPreserved': after == before,
                'failedUnits': run(['systemctl', '--failed', '--no-legend']).strip(),
                'rebootRequired': Path('/var/run/reboot-required').exists()})
            if after != before or report['failedUnits']:
                raise ValueError('Container or service state changed; inspect retained diagnostics')
            for attempt in range(12):
                try:
                    run(['curl', '--fail', '--silent', '--show-error', '--connect-timeout', '5', '--max-time', '10', READY])
                    break
                except RuntimeError:
                    if attempt == 11:
                        raise
                    time.sleep(5)
            report['success'] = True
            return 0
        except Exception as error:
            report['error'] = str(error)
            raise
        finally:
            report['finishedAtUtc'] = dt.datetime.now(dt.timezone.utc).isoformat()
            (log_dir/'result.json').write_text(json.dumps(report, indent=2))
            print(json.dumps({'report': str(log_dir/'result.json'), 'success': report['success']}))


if __name__ == '__main__':
    raise SystemExit(main())
