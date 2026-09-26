const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const bcrypt = require('bcryptjs')
const { Client } = require('pg')

const baseUrl = String(process.env.E2E_API_URL || 'http://127.0.0.1:3001').replace(/\/+$/, '')

async function request(path, body, token) {
  const response = await fetch(`${baseUrl}${path}`, {
    method: body ? 'POST' : 'GET',
    headers: {
      ...(body ? { 'content-type': 'application/json' } : {}),
      ...(token ? { authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(10_000),
  })
  return { status: response.status, body: await response.json() }
}

async function main() {
  const db = new Client({ connectionString: process.env.DATABASE_URL })
  const email = `password-recovery-smoke-${crypto.randomUUID()}@example.invalid`
  const originalPassword = `Orig-${crypto.randomBytes(18).toString('hex')}`
  const newPassword = `New-${crypto.randomBytes(18).toString('hex')}`
  let userId
  await db.connect()
  try {
    const passwordHash = await bcrypt.hash(originalPassword, 12)
    const inserted = await db.query(
      `INSERT INTO fallback_users(email, password_hash, password_enabled, display_name,
        sync_source, sync_status, local_password_set_at, password_changed_by_user_at)
       VALUES ($1, $2, TRUE, 'Recovery Smoke', 'local', 'local_only', NOW(), NOW()) RETURNING id`,
      [email, passwordHash],
    )
    userId = inserted.rows[0].id

    const login = await request('/v1/auth/login', { email, password: originalPassword, client: 'android' })
    assert.equal(login.status, 200, 'initial login')
    const oldToken = login.body.accessToken
    assert.ok(oldToken, 'initial access token')

    const rotated = await request('/v1/auth/password-recovery-codes/rotate', {
      password: originalPassword,
    }, oldToken)
    assert.equal(rotated.status, 200, 'rotate recovery codes')
    assert.equal(rotated.body.recoveryCodes?.length, 10)
    const code = rotated.body.recoveryCodes[0]

    const status = await request('/v1/auth/password-recovery-codes/status', null, oldToken)
    assert.equal(status.status, 200)
    assert.equal(status.body.recoveryCodesRemaining, 10)

    const wrong = await request('/v1/auth/password-recovery-codes/confirm', {
      email, recoveryCode: 'AAAA-AAAA-AAAA-AAAA', newPassword,
    })
    assert.equal(wrong.status, 401, 'wrong code must be rejected')

    const recovered = await request('/v1/auth/password-recovery-codes/confirm', {
      email, recoveryCode: code, newPassword,
    })
    assert.equal(recovered.status, 200, 'valid saved code recovers account')
    assert.ok(recovered.body.accessToken, 'recovered access token')

    const replay = await request('/v1/auth/password-recovery-codes/confirm', {
      email, recoveryCode: code, newPassword,
    })
    assert.equal(replay.status, 401, 'used code must not work twice')
    assert.equal((await request('/v1/auth/password-recovery-codes/status', null, oldToken)).status, 401,
      'old session must be revoked')
    assert.equal((await request('/v1/auth/login', {
      email, password: originalPassword, client: 'android',
    })).status, 401, 'old password must not work')
    assert.equal((await request('/v1/auth/login', {
      email, password: newPassword, client: 'android',
    })).status, 200, 'new password must work')

    console.log(JSON.stringify({ ok: true, recoveryCodesIssued: 10, singleUse: true,
      oldSessionsRevoked: true, passwordChanged: true }))
  } finally {
    if (userId) await db.query('DELETE FROM fallback_users WHERE id = $1', [userId])
    await db.end()
  }
}

main().catch((error) => {
  console.error(error.message)
  process.exitCode = 1
})
