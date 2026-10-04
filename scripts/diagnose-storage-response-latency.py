#!/usr/bin/env python3
"""Read-only, bounded comparison of one owned public audio fixture.

Run on the existing Linux server. No uploads, writes, configuration changes,
container restarts or raw addresses/headers appear in the JSON report.
Each sample opens a fresh HTTP/1.1 connection. This is not an Android/H2 test.
"""
import argparse
import datetime
import hashlib
import http.client
import json
import math
import pathlib
import re
import ssl
import subprocess
import sys
import time
import urllib.parse

PUBLIC_ORIGIN = 'https://conectate.tail9b267c.ts.net'
OWNER_ID = '0e990f73-424e-4c33-9d83-5887f995e294'
MAX_BYTES = 4096
CONTAINERS = {
    'api': ('conectate-local-fallback-api-main', '3001/tcp'),
    'edge': ('conectate-local-fallback-edge-main', '8080/tcp'),
}


def validate_path(value):
    prefix = f'/storage/v1/object/public/profile-audio/{OWNER_ID}/'
    if not isinstance(value, str) or not value.startswith(prefix):
        raise ValueError('Only the owned QA profile-audio fixture is supported')
    tail = value[len(prefix):]
    # Reject encoded slashes, query strings, traversal and foreign buckets.
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,200}\.(?:m4a|aac|mp3|wav|ogg)', tail):
        raise ValueError('Invalid fixture filename')
    if '..' in tail:
        raise ValueError('Traversal is not allowed')
    return value


def milliseconds(start):
    return round((time.monotonic() - start) * 1000, 3)


def app_duration(value):
    """Extract only the app metric, never echo arbitrary response metadata."""
    for metric in str(value or '').split(','):
        match = re.fullmatch(r'\s*app\s*;\s*dur=(\d+(?:\.\d+)?)\s*', metric)
        if match:
            number = float(match.group(1))
            if math.isfinite(number) and 0 <= number <= 600_000:
                return number
    return None


def parse_inspection(label, payload):
    items = json.loads(payload)
    if not isinstance(items, list) or len(items) != 1:
        raise ValueError('Unexpected Docker inspection')
    item = items[0]
    container, port_key = CONTAINERS[label]
    if item.get('Name') != '/' + container or item.get('State', {}).get('Running') is not True:
        raise ValueError('Expected container is not running')
    ports = item.get('NetworkSettings', {}).get('Ports', {}).get(port_key) or []
    bindings = [binding for binding in ports if binding.get('HostIp') == '127.0.0.1']
    if len(bindings) != 1:
        raise ValueError('Expected a single loopback binding')
    port = int(bindings[0]['HostPort'])
    if not 1 <= port <= 65535:
        raise ValueError('Invalid port')
    revision = str(item.get('Config', {}).get('Labels', {}).get('org.opencontainers.image.revision', ''))
    image = str(item.get('Image', ''))
    return port, {
        'running': True,
        'imageDigest': image if re.fullmatch(r'sha256:[0-9a-f]{64}', image) else None,
        'revision': revision if re.fullmatch(r'[0-9a-f]{7,40}', revision) else None,
    }


def inspect_container(label):
    # Ask Docker for a minimal view, rather than reading Config.Env or mounts.
    template = ('[{"Name":{{json .Name}},"State":{"Running":{{json .State.Running}}},'
                '"Image":{{json .Image}},"Config":{"Labels":{"org.opencontainers.image.revision":'
                '{{json (index .Config.Labels "org.opencontainers.image.revision")}}}},'
                '"NetworkSettings":{"Ports":{{json .NetworkSettings.Ports}}}}]')
    result = subprocess.run(
        ['docker', 'inspect', '--type=container', '--format', template, CONTAINERS[label][0]],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10, check=True,
    )
    return parse_inspection(label, result.stdout)


def sample(origin, object_path, timeout=15):
    parsed = urllib.parse.urlsplit(origin)
    connection_class = http.client.HTTPSConnection if parsed.scheme == 'https' else http.client.HTTPConnection
    kwargs = {'timeout': timeout}
    if parsed.scheme == 'https':
        kwargs['context'] = ssl.create_default_context()
    connection = connection_class(parsed.hostname, parsed.port, **kwargs)
    started = time.monotonic()
    try:
        connection.connect()
        connected_ms = milliseconds(started)
        request_started = time.monotonic()
        connection.request('GET', object_path, headers={
            'Range': 'bytes=0-4095', 'Accept-Encoding': 'identity', 'Connection': 'close',
        })
        response = connection.getresponse()
        headers_ms = milliseconds(request_started)
        accel = response.getheader('X-Accel-Redirect') is not None
        status = response.status
        # Direct API may delegate approved bytes to Nginx and have an empty
        # body. The public/edge endpoints must actually honor the 4 KiB range.
        delegated = status == 200 and accel
        range_header = response.getheader('Content-Range', '')
        range_valid = bool(re.fullmatch(r'bytes 0-4095/\d+', range_header))
        if not delegated and (status != 206 or not range_valid):
            return {'status': status, 'connectMs': connected_ms,
                    'requestToHeadersMs': headers_ms, 'validRangeResponse': False,
                    'apiDelegatedToEdge': False, 'bodyRead': False,
                    'appDurationMs': app_duration(response.getheader('Server-Timing'))}
        first = response.read(1)
        first_byte_ms = milliseconds(request_started) if first else None
        rest = response.read(MAX_BYTES)  # at most 4097 bytes in total
        size = len(first) + len(rest)
        return {
            'status': status, 'connectMs': connected_ms, 'requestToHeadersMs': headers_ms,
            'firstBodyByteMs': first_byte_ms, 'totalMs': milliseconds(started),
            'bytesRead': size, 'validRangeResponse': range_valid and size == MAX_BYTES,
            'apiDelegatedToEdge': delegated, 'bodyRead': True,
            'appDurationMs': app_duration(response.getheader('Server-Timing')),
            'measurementValid': (delegated and size == 0) or (range_valid and size == MAX_BYTES),
        }
    except (OSError, http.client.HTTPException) as error:
        return {'failed': True, 'type': type(error).__name__, 'elapsedMs': milliseconds(started)}
    finally:
        connection.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--object-path', required=True, help='Path of the owned synthetic audio; no query or signed token')
    parser.add_argument('--plan', action='store_true', help='Validate input without Docker or network access')
    args = parser.parse_args()
    object_path = validate_path(args.object_path)
    script_file = pathlib.Path(__file__)
    record = {
        'schemaVersion': 1, 'recordedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'diagnosticScriptSha256': hashlib.sha256(script_file.read_bytes()).hexdigest() if script_file.is_file() else None,
        'fixturePathRef': hashlib.sha256(object_path.encode()).hexdigest(),
        'publicOriginRef': hashlib.sha256(PUBLIC_ORIGIN.encode()).hexdigest(),
        'requestUrlOrSecretsIncluded': False, 'readOnly': True, 'executed': not args.plan,
        'scope': 'Linux server fresh HTTP/1.1 connections; not Android/H2, acoustic output or mobile-network proof',
        'apiBodyScope': 'Direct API can return empty X-Accel-Redirect; that measures authorization/file metadata, not media delivery',
        'samples': [],
    }
    if not args.plan:
        origins = []
        for label in ('api', 'edge'):
            try:
                port, metadata = inspect_container(label)
                record.setdefault('containers', {})[label] = metadata
                origins.append((label, f'http://127.0.0.1:{port}'))
            except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
                record.setdefault('containers', {})[label] = {'inspectionFailed': True}
        origins.append(('public', PUBLIC_ORIGIN))
        # Alternate the routes; each receives three independent samples.
        for attempt in range(1, 4):
            for label, origin in origins:
                result = sample(origin, object_path)
                record['samples'].append({'route': label, 'attempt': attempt, **result})
                print(json.dumps({'route': label, 'attempt': attempt, **result}), file=sys.stderr, flush=True)
    print(json.dumps(record, indent=2))


if __name__ == '__main__':
    main()
