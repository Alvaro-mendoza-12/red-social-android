"""Offline regression cases for the bounded read-only storage probe."""
import importlib.util
import json
import pathlib
import unittest
from unittest.mock import patch

source = pathlib.Path(__file__).with_name('diagnose-storage-response-latency.py')
spec = importlib.util.spec_from_file_location('storage_probe', source)
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class Response:
    def __init__(self, status, headers, body):
        self.status, self.headers, self.body = status, headers, body
        self.read_sizes = []

    def getheader(self, name, default=None):
        return self.headers.get(name, default)

    def read(self, size):
        self.read_sizes.append(size)
        chunk, self.body = self.body[:size], self.body[size:]
        return chunk


class Connection:
    def __init__(self, response):
        self.response, self.closed = response, False

    def connect(self):
        pass

    def request(self, *args, **kwargs):
        self.request_args = (args, kwargs)

    def getresponse(self):
        return self.response

    def close(self):
        self.closed = True


class StorageProbeTests(unittest.TestCase):
    def test_only_owned_audio_path(self):
        prefix = f'/storage/v1/object/public/profile-audio/{probe.OWNER_ID}/'
        self.assertEqual(probe.validate_path(prefix + 'fixture.m4a'), prefix + 'fixture.m4a')
        for invalid in (prefix + '../x.m4a', prefix + '%2fx.m4a', prefix + 'x.m4a?token=secret',
                        prefix + 'x.m4a#secret', prefix + 'folder/x.m4a', prefix + 'x.pdf',
                        prefix.replace(probe.OWNER_ID, 'another-owner') + 'x.m4a'):
            with self.subTest(path=invalid), self.assertRaises(ValueError):
                probe.validate_path(invalid)

    def test_metadata_allowlist_and_loopback(self):
        data = {'Name': '/conectate-local-fallback-api-main', 'State': {'Running': True},
                'Config': {'Env': ['SECRET=must-not-appear'], 'Labels': {'org.opencontainers.image.revision': 'fed1546'}},
                'Image': 'sha256:' + 'a' * 64,
                'NetworkSettings': {'Ports': {'3001/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '3001'}]}}}
        port, safe = probe.parse_inspection('api', json.dumps([data]))
        self.assertEqual(port, 3001)
        self.assertNotIn('SECRET', json.dumps(safe))
        self.assertEqual(safe['revision'], 'fed1546')
        data['NetworkSettings']['Ports']['3001/tcp'][0]['HostIp'] = '0.0.0.0'
        with self.assertRaises(ValueError):
            probe.parse_inspection('api', json.dumps([data]))

    def test_timing_metric_is_numeric_not_raw_header(self):
        self.assertEqual(probe.app_duration('db;dur=20, app;dur=25.3'), 25.3)
        for invalid in ('app;dur=NaN', 'app;dur=inf', 'app;dur=-1', 'app;dur=700000', 'secret-value', 'app;dur=2;desc="secret"'):
            self.assertIsNone(probe.app_duration(invalid))

    def test_valid_partial_read_and_connection_closed(self):
        response = Response(206, {'Content-Range': 'bytes 0-4095/8192', 'Server-Timing': 'app;dur=3'}, b'a' * 4096)
        connection = Connection(response)
        with patch.object(probe.http.client, 'HTTPConnection', return_value=connection):
            result = probe.sample('http://127.0.0.1:3080', '/owned')
        self.assertTrue(result['measurementValid'])
        self.assertEqual(result['bytesRead'], 4096)
        self.assertEqual(response.read_sizes, [1, 4096])
        self.assertTrue(connection.closed)

    def test_api_accel_is_not_claimed_as_media_delivery(self):
        response = Response(200, {'X-Accel-Redirect': '/private/object'}, b'')
        connection = Connection(response)
        with patch.object(probe.http.client, 'HTTPConnection', return_value=connection):
            result = probe.sample('http://127.0.0.1:3001', '/owned')
        self.assertTrue(result['measurementValid'])
        self.assertTrue(result['apiDelegatedToEdge'])
        self.assertFalse(result['validRangeResponse'])
        self.assertIsNone(result['firstBodyByteMs'])
        self.assertNotIn('/private/object', json.dumps(result))

    def test_redirect_or_full_body_never_read(self):
        for status in (302, 200, 404):
            response = Response(status, {'Location': 'https://unexpected.example/secret'}, b'body')
            connection = Connection(response)
            with patch.object(probe.http.client, 'HTTPConnection', return_value=connection):
                result = probe.sample('http://127.0.0.1:3080', '/owned')
            self.assertFalse(result['bodyRead'])
            self.assertEqual(response.read_sizes, [])
            self.assertTrue(connection.closed)
            self.assertNotIn('unexpected', json.dumps(result))

    def test_incorrect_range_size_rejected_after_bounded_read(self):
        response = Response(206, {'Content-Range': 'bytes 0-4095/90000'}, b'a' * 90000)
        connection = Connection(response)
        with patch.object(probe.http.client, 'HTTPConnection', return_value=connection):
            result = probe.sample('http://127.0.0.1:3080', '/owned')
        self.assertFalse(result['measurementValid'])
        self.assertEqual(result['bytesRead'], 4097)
        self.assertEqual(response.read_sizes, [1, 4096])

    def test_transport_error_message_not_exposed(self):
        connection = Connection(None)
        with patch.object(probe.http.client, 'HTTPConnection', return_value=connection), \
             patch.object(connection, 'connect', side_effect=OSError('token-and-host-must-stay-private')):
            result = probe.sample('http://127.0.0.1:3080', '/owned')
        self.assertTrue(result['failed'])
        self.assertEqual(result['type'], 'OSError')
        self.assertNotIn('token-and-host', json.dumps(result))
        self.assertTrue(connection.closed)


if __name__ == '__main__':
    unittest.main()
