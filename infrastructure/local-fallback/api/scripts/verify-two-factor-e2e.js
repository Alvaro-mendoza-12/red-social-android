const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const { Pool } = require('pg')
const { codeAtStep } = require('../src/two-factor')

const databaseUrl = process.env.DATABASE_URL
if (!databaseUrl) throw new Error('DATABASE_URL is required')
const pool = new Pool({ connectionString: databaseUrl, max: 2 })
const baseUrl = String(process.env.E2E_API_URL || 'http://127.0.0.1:3001').replace(/\/+$/, '')
const email = `two-factor-e2e-${crypto.randomUUID()}@novvin.invalid`
const password = `E2E-${crypto.randomUUID()}!`
const nextPassword = `E2E-${crypto.randomUUID()}!`

async function request(path, body, token, extraHeaders = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: {
      accept: 'application/json',
      ...(body === undefined ? {} : { 'content-type': 'application/json' }),
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...extraHeaders,
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  const result = await response.json()
  return { status: response.status, body: result }
}

async function run() {
  try {
    const registered = await request('/v1/auth/register', {
      email, password, displayName: 'Prueba aislada 2FA',
    })
    assert.equal(registered.status, 201, `registration status ${registered.status}`)
    const oldAccess = registered.body.accessToken
    const oldRefresh = registered.body.refreshToken
    assert.ok(oldAccess && oldRefresh)

    const pairing = await request('/v1/auth/web-login-pairing', { action: 'create' })
    assert.equal(pairing.status, 200)
    const approved = await request('/v1/auth/web-login-pairing', {
      action: 'approve', pairId: pairing.body.pairId, nonce: pairing.body.nonce,
    }, oldAccess)
    assert.equal(approved.status, 200)
    assert.equal(approved.body.status, 'approved')

    const setup = await request('/v1/auth/2fa/setup', { password }, oldAccess)
    assert.equal(setup.status, 200, JSON.stringify(setup.body))
    assert.ok(setup.body.secret && setup.body.provisioningUri)
    const step = Math.floor(Date.now() / 30000)
    const enabled = await request('/v1/auth/2fa/enable', {
      password, totpCode: codeAtStep(setup.body.secret, step),
    }, oldAccess)
    assert.equal(enabled.status, 200, JSON.stringify(enabled.body))
    assert.equal(enabled.body.recoveryCodes.length, 10)
    assert.ok(enabled.body.accessToken)
    const stalePairing = await request('/v1/auth/web-login-pairing', {
      action: 'consume', pairId: pairing.body.pairId, nonce: pairing.body.nonce,
    }, undefined, { 'x-novvin-web-session': 'web-v1' })
    assert.equal(stalePairing.status, 409)
    assert.equal(stalePairing.body.error, 'PAIRING_REJECTED')

    const oldSession = await request('/v1/auth/me', undefined, oldAccess)
    assert.equal(oldSession.status, 401, '2FA enrollment must revoke old access tokens')
    const oldRenewal = await request('/v1/auth/refresh', { refreshToken: oldRefresh })
    assert.equal(oldRenewal.status, 401, '2FA enrollment must revoke old refresh tokens')

    const missing = await request('/v1/auth/login', { email, password })
    assert.equal(missing.status, 401)
    assert.equal(missing.body.error, 'TWO_FACTOR_REQUIRED')
    assert.equal(missing.body.accessToken, undefined)

    const replayed = await request('/v1/auth/login', {
      email, password, totpCode: codeAtStep(setup.body.secret, step),
    })
    assert.equal(replayed.status, 401)
    assert.equal(replayed.body.error, 'TWO_FACTOR_INVALID')

    const freshCode = codeAtStep(setup.body.secret, Math.floor(Date.now() / 30000) + 1)
    const wrongPassword = await request('/v1/auth/login', {
      email, password: `${password}-incorrect`, totpCode: freshCode,
    })
    assert.equal(wrongPassword.status, 401)
    const totpLogin = await request('/v1/auth/login', { email, password, totpCode: freshCode })
    assert.equal(totpLogin.status, 200, `TOTP login status ${totpLogin.status}`)
    assert.ok(totpLogin.body.accessToken)
    const totpReplay = await request('/v1/auth/login', { email, password, totpCode: freshCode })
    assert.equal(totpReplay.body.error, 'TWO_FACTOR_INVALID')

    const recovered = await request('/v1/auth/login', {
      email, password, backupCode: enabled.body.recoveryCodes[0],
    })
    assert.equal(recovered.status, 200, JSON.stringify(recovered.body))
    assert.ok(recovered.body.accessToken)
    const reuse = await request('/v1/auth/login', {
      email, password, backupCode: enabled.body.recoveryCodes[0],
    })
    assert.equal(reuse.status, 401)
    assert.equal(reuse.body.error, 'TWO_FACTOR_INVALID')

    const webChallenge = await request('/v1/auth/login', {
      email, password, client: 'web',
    }, undefined, { 'x-novvin-web-session': 'web-v1' })
    assert.equal(webChallenge.body.error, 'TWO_FACTOR_REQUIRED')
    const webRecovered = await request('/v1/auth/login', {
      email, password, backupCode: enabled.body.recoveryCodes[2], client: 'web',
    }, undefined, { 'x-novvin-web-session': 'web-v1' })
    assert.equal(webRecovered.status, 200, `web second factor status ${webRecovered.status}`)
    assert.ok(webRecovered.body.accessToken)

    const resetRequest = await request('/v1/auth/password-reset/request', { email })
    assert.equal(resetRequest.status, 200, JSON.stringify(resetRequest.body))
    assert.ok(resetRequest.body.resetToken)
    const reset = await request('/v1/auth/password-reset/confirm', {
      resetToken: resetRequest.body.resetToken, newPassword: nextPassword,
    })
    assert.equal(reset.status, 200, JSON.stringify(reset.body))
    assert.equal(reset.body.requiresTwoFactor, true)
    assert.equal(reset.body.accessToken, undefined)
    const postReset = await request('/v1/auth/login', { email, password: nextPassword })
    assert.equal(postReset.body.error, 'TWO_FACTOR_REQUIRED')
    const secondRecovery = await request('/v1/auth/login', {
      email, password: nextPassword, backupCode: enabled.body.recoveryCodes[1],
    })
    assert.equal(secondRecovery.status, 200, JSON.stringify(secondRecovery.body))
    const disabled = await request('/v1/auth/2fa/disable', {
      password: nextPassword, backupCode: enabled.body.recoveryCodes[3],
    }, secondRecovery.body.accessToken)
    assert.equal(disabled.status, 200, `disable status ${disabled.status}`)
    const revokedOnDisable = await request('/v1/auth/me', undefined, secondRecovery.body.accessToken)
    assert.equal(revokedOnDisable.status, 401)
    const afterDisable = await request('/v1/auth/login', { email, password: nextPassword })
    assert.equal(afterDisable.status, 200)
    assert.ok(afterDisable.body.accessToken)
    console.log('2FA E2E passed: enrollment, revocation, QR, Android/web challenges, replay, recovery, reset, disable')
  } finally {
    await pool.query('DELETE FROM fallback_users WHERE email = $1', [email])
    await pool.end()
  }
}

run().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
