'use strict'

// Run only with disposable audit accounts. Output contains refreshed fixture
// tokens and must be captured privately; publish only the report property.
const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const { Pool } = require('pg')
const bcrypt = require('bcrypt')

async function verifyFixtureAuthCycle(fixtures, { base = 'http://127.0.0.1:3001' } = {}) {
  assert.equal(fixtures.length, 2)
  const pool = new Pool({ connectionString: process.env.DATABASE_URL, max: 1 })
  const checks = []
  async function request(name, path, { token, body, status = 200 } = {}) {
    const start = performance.now()
    const response = await fetch(base + path, {
      method: body ? 'POST' : 'GET', headers: {
        ...(token ? { Authorization: 'Bearer ' + token } : {}), 'Content-Type': 'application/json',
      }, body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(15_000),
    })
    assert.equal(response.status, status, name)
    checks.push({ name, status, elapsedMs: Math.round(performance.now() - start) })
    return response.json()
  }
  try {
    // Verify both identities before any mutation. Never accept a real account.
    for (const fixture of fixtures) {
      const { user } = await request('verify-disposable-identity', '/v1/auth/me', { token: fixture.token })
      assert.ok([user.id, user.supabaseUserId].includes(fixture.id))
      assert.match(user.email, /^six-point-qa\.[a-f0-9-]+@example\.invalid$/)
      assert.equal(user.displayName, 'Prueba seis puntos temporal')
      const row = (await pool.query('SELECT email, display_name FROM fallback_users WHERE id=$1', [fixture.id])).rows[0]
      assert.equal(row.email, user.email)
      assert.equal(row.display_name, user.displayName)
    }
    const fixture = fixtures[0]
    const { email } = (await pool.query('SELECT email FROM fallback_users WHERE id=$1', [fixture.id])).rows[0]
    const first = 'Qa-first-' + crypto.randomUUID()
    const second = 'Qa-second-' + crypto.randomUUID()
    const third = 'Qa-recovered-' + crypto.randomUUID()
    // Controlled initialization of this disposable account only; login,
    // password changes and recovery completion all use the deployed HTTP API.
    await pool.query('UPDATE fallback_users SET password_hash=$2, password_enabled=TRUE WHERE id=$1',
      [fixture.id, await bcrypt.hash(first, 12)])
    await request('incorrect-login-rejected', '/v1/auth/login', { body: { email, password: first + '-incorrect' }, status: 401 })
    const login = await request('correct-login-accepted', '/v1/auth/login', { body: { email, password: first } })
    assert.ok(login.accessToken)
    await request('overlong-password-change-rejected', '/v1/auth/change-password', {
      token: login.accessToken, body: { currentPassword: first, newPassword: 'é'.repeat(37) }, status: 400,
    })
    await request('incorrect-current-password-rejected', '/v1/auth/change-password', {
      token: login.accessToken, body: { currentPassword: first + '-incorrect', newPassword: second }, status: 401,
    })
    const changed = await request('change-password-accepted', '/v1/auth/change-password', {
      token: login.accessToken, body: { currentPassword: first, newPassword: second },
    })
    assert.ok(changed.accessToken)
    fixture.token = changed.accessToken
    await request('previous-session-revoked', '/v1/auth/me', { token: login.accessToken, status: 401 })
    await request('previous-password-rejected', '/v1/auth/login', { body: { email, password: first }, status: 401 })
    const relogin = await request('changed-password-login-accepted', '/v1/auth/login', { body: { email, password: second } })
    fixture.token = relogin.accessToken
    const { createEmailRecoveryStore } = require('../src/password-reset-email-code')
    const store = createEmailRecoveryStore({ pool, secret: String(process.env.TOTP_ENCRYPTION_KEY || process.env.JWT_SECRET),
      makeToken: () => crypto.randomBytes(32).toString('hex'),
      hashToken: token => crypto.createHash('sha256').update(token).digest('hex'),
    })
    const code = await store.issue(fixture.id, email)
    assert.match(code, /^[0-9]{6}$/)
    const wrong = String((Number(code) + 1) % 1_000_000).padStart(6, '0')
    await request('incorrect-email-code-rejected', '/v1/auth/password-reset/verify', { body: { email, code: wrong }, status: 401 })
    const verified = await request('six-digit-code-accepted', '/v1/auth/password-reset/verify', { body: { email, code } })
    assert.ok(verified.resetToken)
    await request('overlong-recovery-password-rejected-without-consuming-grant', '/v1/auth/password-reset/confirm', {
      body: { resetToken: verified.resetToken, newPassword: 'A'.repeat(73) }, status: 400,
    })
    await request('email-code-replay-rejected', '/v1/auth/password-reset/verify', { body: { email, code }, status: 401 })
    const recovered = await request('recovery-password-saved', '/v1/auth/password-reset/confirm', {
      body: { resetToken: verified.resetToken, newPassword: third },
    })
    assert.ok(recovered.accessToken)
    fixture.token = recovered.accessToken
    await request('reset-grant-replay-rejected', '/v1/auth/password-reset/confirm', {
      body: { resetToken: verified.resetToken, newPassword: second }, status: 401,
    })
    await request('pre-recovery-session-revoked', '/v1/auth/me', { token: relogin.accessToken, status: 401 })
    await request('pre-recovery-password-rejected', '/v1/auth/login', { body: { email, password: second }, status: 401 })
    const finalLogin = await request('recovered-password-login-accepted', '/v1/auth/login', { body: { email, password: third } })
    fixture.token = finalLogin.accessToken
    await request('recovered-account-data-accessible', '/v1/auth/me', { token: fixture.token })
    const unknown = await request('unknown-account-response-remains-generic', '/v1/auth/password-reset/request', {
      body: { email: 'unknown-audit-' + crypto.randomUUID() + '@example.invalid', codeFormat: 'numeric' },
    })
    assert.equal(unknown.ok, true)
    assert.ok(!unknown.resetToken && !unknown.code)
    const smtpConfigured = process.env.PASSWORD_RESET_DELIVERY === 'smtp'
    let smtpAuthenticated = false
    if (smtpConfigured) {
      const transport = require('nodemailer').createTransport({
        host: process.env.SMTP_HOST, port: Number(process.env.SMTP_PORT || 587),
        secure: Number(process.env.SMTP_PORT || 587) === 465,
        requireTLS: Number(process.env.SMTP_PORT || 587) !== 465,
        auth: { user: process.env.SMTP_USER, pass: process.env.SMTP_PASS },
        connectionTimeout: 5000, greetingTimeout: 5000, socketTimeout: 10_000,
      })
      try { smtpAuthenticated = await transport.verify() } finally { transport.close() }
    }
    return { accounts: fixtures, report: { ok: true, checks, smtpConfigured, smtpAuthenticated,
      scope: 'Two verified disposable accounts only. Six-digit code issued by the server store; SMTP TLS/auth checked separately. Inbox receipt is not certified.' } }
  } catch {
    // Preserve the latest fixture token if a later assertion fails, so guarded
    // cleanup can still authenticate after password-change session revocation.
    return { accounts: fixtures, report: { ok: false, checks, error: 'AUTH_AUDIT_FAILED' } }
  } finally { await pool.end() }
}

module.exports = { verifyFixtureAuthCycle }
if (require.main === module) {
  verifyFixtureAuthCycle(JSON.parse(require('node:fs').readFileSync(0, 'utf8')))
    .then(result => process.stdout.write(JSON.stringify(result)))
    .catch(() => { console.error('Disposable auth audit failed; retain fixtures for guarded cleanup.'); process.exitCode = 1 })
}
