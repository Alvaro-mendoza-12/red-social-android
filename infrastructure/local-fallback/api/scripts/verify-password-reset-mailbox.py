"""Opt-in delivery audit using one disposable alias in the owned service mailbox.

Runs on the server. Credentials stay in memory and are read from the API's
existing SMTP environment. Reads only mail addressed to a fresh UUID alias;
never lists unrelated messages or changes their read state.
"""
import argparse
import email
import email.policy
import email.utils
import imaplib
import json
import re
import secrets
import ssl
import subprocess
import time
import urllib.error
import urllib.request
import uuid


def recovery_code(raw, recipient, sender):
    message = email.message_from_bytes(raw, policy=email.policy.default)
    recipients = {address.lower() for _, address in email.utils.getaddresses(message.get_all('To', []))}
    if recipient.lower() not in recipients or email.utils.parseaddr(message.get('From', ''))[1].lower() != sender.lower():
        return None
    if str(message.get('Subject', '')) != 'Novvin: recupera tu contraseña':
        return None
    for part in message.walk():
        if part.get_content_type() == 'text/plain' and part.get_content_disposition() != 'attachment':
            match = re.search(r'Código de recuperación:\s*([0-9]{6})(?![0-9])', part.get_content())
            if match:
                return match.group(1)
    return None


def audit(mailbox_user, base_url):
    # Fetch no other environment values; neither stdout nor exceptions expose
    # the app password, API tokens, mailbox bodies, or generated code.
    values = subprocess.run(['docker', 'exec', 'conectate-local-fallback-api-main', 'node', '-e',
        'process.stdout.write(JSON.stringify({user:process.env.SMTP_USER,pass:process.env.SMTP_PASS,host:process.env.SMTP_HOST}))'],
        capture_output=True, check=True, timeout=10)
    smtp = json.loads(values.stdout)
    if smtp['user'].lower() != mailbox_user.lower() or smtp['host'] != 'smtp.gmail.com':
        raise RuntimeError('OWNED_GMAIL_CONFIGURATION_REQUIRED')
    mailbox = imaplib.IMAP4_SSL('imap.gmail.com', 993, ssl_context=ssl.create_default_context(), timeout=15)
    try:
        mailbox.login(mailbox_user, smtp['pass'].replace(' ', ''))
    except Exception:
        mailbox.shutdown()
        raise RuntimeError('MAILBOX_LOGIN_FAILED') from None
    smtp.clear()
    prefix, domain = mailbox_user.split('@', 1)
    fixture_email = prefix + '+novvin-qa-' + str(uuid.uuid4()) + '@' + domain
    passwords = ['QA-' + secrets.token_urlsafe(24) + '-only' for _ in range(3)]
    account_id = None
    latest_token = None
    result = {}

    def request(path, body=None, token=None, method='POST'):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        req = urllib.request.Request(base_url.rstrip('/') + path,
            data=json.dumps(body or {}).encode() if method != 'GET' else None,
            headers=headers, method=method)
        try:
            response = urllib.request.urlopen(req, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            data = response.read()
            return response.status, json.loads(data) if data else {}

    def owned_identity(token):
        status, data = request('/v1/auth/me', token=token, method='GET')
        if status != 200 or data.get('user', {}).get('id') != account_id or data['user'].get('email') != fixture_email:
            raise RuntimeError('FIXTURE_IDENTITY_GUARD_FAILED')

    try:
        status, created = request('/v1/auth/register', {'email': fixture_email, 'password': passwords[0],
            'displayName': 'Recuperación correo QA temporal'})
        if status != 201:
            raise RuntimeError('ISOLATED_REGISTRATION_FAILED')
        account_id, latest_token = created['user']['id'], created['accessToken']
        owned_identity(latest_token)
        mailbox.select('INBOX', readonly=True)
        sent_at = time.monotonic()
        status, response = request('/v1/auth/password-reset/request', {'email': fixture_email, 'codeFormat': 'numeric'})
        if status != 200 or response.get('resetToken') or response.get('code'):
            raise RuntimeError('EMAIL_RECOVERY_REQUEST_FAILED_OR_EXPOSED_SECRET')
        if response.get('delivery') != 'email' or response.get('codeLength') != 6:
            raise RuntimeError('NUMERIC_EMAIL_RECOVERY_REQUIRED')
        code = None
        deadline = sent_at + 90
        while time.monotonic() < deadline and code is None:
            mailbox.noop()
            status, identifiers = mailbox.uid('search', None, 'TO', '"' + fixture_email + '"')
            if status != 'OK':
                raise RuntimeError('SCOPED_MAILBOX_SEARCH_FAILED')
            for identifier in identifiers[0].split()[-5:]:
                status, contents = mailbox.uid('fetch', identifier, '(BODY.PEEK[])')
                if status == 'OK':
                    for part in contents:
                        if isinstance(part, tuple):
                            code = recovery_code(part[1], fixture_email, mailbox_user)
                            if code:
                                break
                if code:
                    break
            if code is None:
                time.sleep(2)
        if code is None:
            raise RuntimeError('RECOVERY_EMAIL_NOT_RECEIVED')
        result['mailReceivedMs'] = round((time.monotonic() - sent_at) * 1000)
        result['codeDigits'] = len(code)
        wrong_code = ('1' if code[0] != '1' else '2') + code[1:]
        status, _ = request('/v1/auth/password-reset/verify', {'email': fixture_email, 'code': wrong_code})
        if status not in (400, 401):
            raise RuntimeError('INCORRECT_CODE_NOT_REJECTED')
        result['incorrectCodeRejected'] = True
        status, grant = request('/v1/auth/password-reset/verify', {'email': fixture_email, 'code': code})
        if status != 200 or not grant.get('resetToken'):
            raise RuntimeError('EMAIL_CODE_VERIFICATION_FAILED')
        status, _ = request('/v1/auth/password-reset/verify', {'email': fixture_email, 'code': code})
        if status not in (400, 401):
            raise RuntimeError('EMAIL_CODE_REPLAY_NOT_REJECTED')
        result['codeReplayRejected'] = True
        initial_token = latest_token
        status, reset = request('/v1/auth/password-reset/confirm', {'resetToken': grant['resetToken'], 'newPassword': passwords[1]})
        if status != 200 or not reset.get('accessToken'):
            raise RuntimeError('PASSWORD_RESET_FAILED')
        latest_token = reset['accessToken']
        owned_identity(latest_token)
        status, _ = request('/v1/auth/me', token=initial_token, method='GET')
        if status != 401:
            raise RuntimeError('OLD_SESSION_NOT_REVOKED')
        result['oldSessionRevoked'] = True
        status, _ = request('/v1/auth/password-reset/confirm', {'resetToken': grant['resetToken'], 'newPassword': passwords[2]})
        if status not in (400, 401):
            raise RuntimeError('RESET_GRANT_REPLAY_NOT_REJECTED')
        result['resetGrantReplayRejected'] = True
        status, _ = request('/v1/auth/login', {'email': fixture_email, 'password': passwords[0]})
        if status != 401:
            raise RuntimeError('OLD_PASSWORD_NOT_REJECTED')
        result['oldPasswordRejected'] = True
        status, login = request('/v1/auth/login', {'email': fixture_email, 'password': passwords[1]})
        if status != 200:
            raise RuntimeError('RESET_PASSWORD_LOGIN_FAILED')
        latest_token = login['accessToken']
        owned_identity(latest_token)
        result['loginWithNewPasswordPassed'] = True
        status, changed = request('/v1/auth/change-password', {'currentPassword': passwords[1], 'newPassword': passwords[2]}, latest_token)
        if status != 200 or not changed.get('accessToken'):
            raise RuntimeError('AUTHENTICATED_PASSWORD_CHANGE_FAILED')
        latest_token = changed['accessToken']
        status, _ = request('/v1/auth/login', {'email': fixture_email, 'password': passwords[1]})
        if status != 401:
            raise RuntimeError('PREVIOUS_PASSWORD_NOT_REJECTED')
        status, login = request('/v1/auth/login', {'email': fixture_email, 'password': passwords[2]})
        if status != 200:
            raise RuntimeError('FINAL_PASSWORD_LOGIN_FAILED')
        latest_token = login['accessToken']
        owned_identity(latest_token)
        result['authenticatedPasswordChangePassed'] = True
        result['passed'] = True
    finally:
        try:
            if account_id:
                # All candidates are fresh passwords for this exact UUID alias.
                # Login is used only to recover a token if a reset revoked it.
                try:
                    owned_identity(latest_token)
                except RuntimeError:
                    for password in reversed(passwords):
                        status, login = request('/v1/auth/login', {'email': fixture_email, 'password': password})
                        if status == 200:
                            latest_token = login['accessToken']
                            break
                    owned_identity(latest_token)
                status, deleted = request('/rest/v1/rpc/delete_own_account', {}, latest_token)
                if status != 200 or deleted.get('deleted') is not True:
                    raise RuntimeError('OWNED_FIXTURE_CLEANUP_FAILED')
                status, _ = request('/v1/auth/me', token=latest_token, method='GET')
                if status != 401:
                    raise RuntimeError('DELETED_FIXTURE_SESSION_STILL_VALID')
                result['fixtureDeleted'] = True
        finally:
            mailbox.logout()
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--owned-mailbox', required=True)
    parser.add_argument('--api-url', default='http://127.0.0.1:3001')
    args = parser.parse_args()
    try:
        print(json.dumps(audit(args.owned_mailbox, args.api_url)))
    except Exception as error:
        reason = str(error) if isinstance(error, RuntimeError) and re.fullmatch(r'[A-Z_]+', str(error)) else 'MAILBOX_AUDIT_FAILED'
        print(json.dumps({'passed': False, 'reason': reason}))
        raise SystemExit(1)
