#!/usr/bin/env python3
"""Prepare clean CI Android inputs. Never substitutes test Firebase config in production."""
import argparse
import base64
import json
import os
from pathlib import Path
import subprocess


def ci_google_services():
    # Format-valid, deliberately non-operational. CI tests must not contact Firebase.
    return {
        'project_info': {'project_number': '123456789012', 'project_id': 'novvin-ci-invalid', 'storage_bucket': 'novvin-ci-invalid.invalid'},
        'client': [{
            'client_info': {'mobilesdk_app_id': '1:123456789012:android:0000000000000000000000', 'android_client_info': {'package_name': 'conectate.com'}},
            'oauth_client': [], 'api_key': [{'current_key': 'AIzaSy' + '0' * 33}],
            'services': {'appinvite_service': {'other_platform_oauth_client': []}},
        }],
        'configuration_version': '1',
    }


def production_google_services(encoded):
    try:
        config = json.loads(base64.b64decode(encoded, validate=True))
        project = config['project_info']['project_id']
        matching = [client for client in config['client'] if client['client_info']['android_client_info']['package_name'] == 'conectate.com']
        if not project or project == 'novvin-ci-invalid' or not matching:
            raise ValueError('Missing production Firebase project or package')
        if not matching[0]['client_info']['mobilesdk_app_id'] or not matching[0]['api_key'][0]['current_key']:
            raise ValueError('Missing Firebase app or API configuration')
        return config
    except (ValueError, KeyError, TypeError, IndexError) as error:
        raise ValueError('ANDROID_GOOGLE_SERVICES_JSON_BASE64 must contain the production google-services.json for conectate.com') from error


def prepare(root, production=False):
    root = Path(root)
    config = production_google_services(os.environ.get('ANDROID_GOOGLE_SERVICES_JSON_BASE64', '')) if production else ci_google_services()
    target = root / 'app/google-services.json'
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.exists():
        target.write_text(json.dumps(config, indent=2) + '\n')
    elif production and json.loads(target.read_text()) != config:
        raise ValueError('Existing google-services.json differs from the explicit production configuration')
    keystore = root / 'keystores/debug.keystore'
    if not keystore.exists():
        keystore.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run([
            'keytool', '-genkeypair', '-noprompt', '-keystore', str(keystore),
            '-storepass', 'android', '-keypass', 'android', '-alias', 'androiddebugkey',
            '-dname', 'CN=Android CI Debug,O=Android,C=US', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '30',
        ], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path('.'))
    parser.add_argument('--production', action='store_true')
    args = parser.parse_args()
    prepare(args.root, args.production)

if __name__ == '__main__':
    main()
