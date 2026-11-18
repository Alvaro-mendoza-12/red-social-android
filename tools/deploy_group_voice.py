"""Apply a verified voice-only overlay to the existing Ubuntu images.

Run on Ubuntu: python3 deploy_group_voice.py /home/alvaro/conectate-releases/<release>
The prepared directory contains manifest.json and payload/, never credentials.
Existing server features, pending host sources, Compose settings and secrets are retained. A failure
restores the previous Compose file/images; additive database tables remain compatible.
"""
from pathlib import Path
import fcntl
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import time


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(*args, cwd=None):
    return subprocess.check_output(list(args), cwd=cwd).decode().strip()


def inspect(name):
    return json.loads(run('docker', 'inspect', name))[0]


def health(container, kind):
    script = "fetch('http://127.0.0.1:'+(process.env.PORT||" + ('3001' if kind == 'api' else '3000') + ")+'/" + ('ready' if kind == 'api' else 'health') + "',{signal:AbortSignal.timeout(4000)}).then(r=>{if(!r.ok)throw Error('HTTP');return r.json()}).then(d=>console.log(JSON.stringify({ok:d.ok,activeSessions:d.activeSessions})))"
    return json.loads(run('docker', 'exec', container, 'node', '-e', script))


def wait_ready(container, kind):
    for _ in range(60):
        try:
            if health(container, kind).get('ok') is True:
                return
        except (subprocess.CalledProcessError, ValueError):
            pass
        time.sleep(2)
    raise RuntimeError('Service did not become ready: ' + kind)


def main():
    os.umask(0o077)
    root = Path('/home/alvaro/conectate-local-fallback')
    release = Path(sys.argv[1]).resolve(strict=True)
    assert release.parent == Path('/home/alvaro/conectate-releases')
    assert re.fullmatch(r'group-voice-[a-z0-9-]+', release.name)
    manifest = json.loads((release / 'manifest.json').read_text())
    assert not (release / 'DEPLOYED.json').exists()
    lock = Path('/home/alvaro/.cache/conectate-compose.lock')
    lock.parent.mkdir(exist_ok=True)
    with lock.open('a') as stream:
        fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        old_override = (root / 'docker-compose.override.yml').read_bytes()
        assert hashlib.sha256(old_override).hexdigest() == manifest['composeOverrideSha256']
        for kind, service in manifest['services'].items():
            current = inspect(service['container'])
            assert current['Image'] == service['baseImageId'], kind
            assert run('docker', 'image', 'inspect', '--format', '{{.Id}}', service['baseImage']) == service['baseImageId']
        assert health(manifest['services']['api']['container'], 'api')['ok']
        signal = health(manifest['services']['signaling']['container'], 'signaling')
        assert signal['ok'] and all(value == 0 for key, value in signal['activeSessions'].items() if key != 'scope'), 'Active media sessions; retry after they finish'
        payload = release / 'payload'
        for name, record in manifest['files'].items():
            assert name.startswith(('api/src/','signaling/')) and '..' not in Path(name).parts
            candidate = payload / name
            assert candidate.is_file() and sha(candidate) == record['after'], name
            if record['containerBefore']:
                service = manifest['services']['signaling' if name.startswith('signaling/') else 'api']
                remote = '/usr/src/app/' + name.split('/', 1)[1]
                current = run('docker', 'exec', service['container'], 'sha256sum', remote).split()[0]
                assert current == record['containerBefore'], name
        backup = release / 'before.tar.gz'
        with tarfile.open(backup, 'w:gz') as archive:
            archive.add(root / 'docker-compose.override.yml', arcname='docker-compose.override.yml')
        assert backup.stat().st_size > 0
        with tarfile.open(backup) as archive:
            for member in archive.getmembers():
                assert member.isfile() and '..' not in Path(member.name).parts
                assert archive.extractfile(member).read()
        dump = release / 'voice-before.dump'
        with dump.open('wb') as output:
            subprocess.run(['docker', 'exec', 'conectate-local-fallback-db-main', 'sh', '-ec',
                'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc '
                '-t public.fallback_voice_rooms -t public.fallback_voice_room_participants '
                '-t public.fallback_notifications'], stdout=output, check=True)
        assert dump.stat().st_size > 0
        # Check the archive with the same PostgreSQL version without restoring production.
        with dump.open('rb') as data:
            subprocess.run(['docker', 'exec', '-i', 'conectate-local-fallback-db-main', 'pg_restore', '--list'], stdin=data, stdout=subprocess.DEVNULL, check=True)
        (release / 'BACKUP.json').write_text(json.dumps({'filesSha256': sha(backup), 'databaseSha256': sha(dump)}, indent=2))
        for kind, service in manifest['services'].items():
            context = release / ('build-' + kind)
            context.mkdir()
            relevant = {name: rec for name, rec in manifest['files'].items() if name.startswith(kind + '/')}
            assert relevant
            for name in relevant:
                target = context / name.split('/', 1)[1]
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(payload / name, target)
            dockerfile = 'FROM ' + service['baseImage'] + '\n'
            dockerfile += 'LABEL novvin.group-voice-source="' + manifest['sourceDigest'] + '"\n'
            for name in relevant:
                relative = name.split('/', 1)[1]
                dockerfile += f'COPY --chmod=0644 --chown=node:node {relative} /usr/src/app/{relative}\n'
            (context / 'Dockerfile').write_text(dockerfile)
            subprocess.run(['docker', 'build', '--network=none', '-t', service['image'], str(context)], check=True)
            args = ['docker', 'run', '--rm', '--network=none', '--user', 'novvin' if kind == 'api' else 'node', '--entrypoint=node', service['image']]
            for name in relevant:
                if name.endswith('.js'):
                    subprocess.run([*args, '--check', '/usr/src/app/' + name.split('/', 1)[1]], check=True)
        updated = old_override.decode()
        for service in manifest['services'].values():
            old = '    image: ' + service['baseImage']
            assert updated.count(old) == 1
            updated = updated.replace(old, '    image: ' + service['image'], 1)
        # Build/preparation has no live impact. Recheck media immediately before replacement.
        signal = health(manifest['services']['signaling']['container'], 'signaling')
        assert signal['ok'] and all(value == 0 for key, value in signal['activeSessions'].items() if key != 'scope'), 'Active media sessions; preparation retained without replacement'
        changed = False
        try:
            changed = True
            (root / 'docker-compose.override.yml').write_bytes(updated.encode())
            subprocess.run(['docker', 'compose', 'config', '--quiet'], cwd=root, check=True)
            subprocess.run(['docker', 'compose', 'up', '-d', '--no-deps', '--no-build', '--force-recreate', 'api'], cwd=root, check=True)
            wait_ready(manifest['services']['api']['container'], 'api')
            subprocess.run(['docker', 'compose', 'up', '-d', '--no-deps', '--no-build', '--force-recreate', 'signaling'], cwd=root, check=True)
            wait_ready(manifest['services']['signaling']['container'], 'signaling')
            for kind, service in manifest['services'].items():
                assert inspect(service['container'])['Config']['Image'] == service['image']
            for name, record in manifest['files'].items():
                service = manifest['services']['signaling' if name.startswith('signaling/') else 'api']
                actual = run('docker', 'exec', service['container'], 'sha256sum', '/usr/src/app/' + name.split('/', 1)[1]).split()[0]
                assert actual == record['after']
            probe = "fetch('http://127.0.0.1:3001/v1/data/messages/groups/qa/voice-room-invitees').then(r=>{if(r.status!==401)process.exit(1)})"
            subprocess.run(['docker', 'exec', manifest['services']['api']['container'], 'node', '-e', probe], check=True)
            result = {'ok': True, 'sourceDigest': manifest['sourceDigest'], 'images': {k: inspect(v['container'])['Image'] for k, v in manifest['services'].items()},
                      'apiReady': health(manifest['services']['api']['container'], 'api')['ok'], 'signalingReady': health(manifest['services']['signaling']['container'], 'signaling')['ok']}
            (release / 'DEPLOYED.json').write_text(json.dumps(result, indent=2))
            print(json.dumps(result), flush=True)
        except BaseException:
            if changed:
                (root / 'docker-compose.override.yml').write_bytes(old_override)
                subprocess.run(['docker', 'compose', 'up', '-d', '--no-deps', '--no-build', '--force-recreate', 'api', 'signaling'], cwd=root, check=True)
                wait_ready(manifest['services']['api']['container'], 'api')
                wait_ready(manifest['services']['signaling']['container'], 'signaling')
                (release / 'ROLLED_BACK').write_text('Previous files/images restored; additive schema retained.\n')
            raise


if __name__ == '__main__':
    main()
