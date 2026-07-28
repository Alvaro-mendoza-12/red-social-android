const crypto = require('crypto')
const { Pool } = require('pg')

const databaseUrl = process.env.DATABASE_URL
const baseUrl = String(process.env.E2E_API_URL || 'http://127.0.0.1:3001').replace(/\/+$/, '')

if (!databaseUrl) throw new Error('DATABASE_URL is required')

const pool = new Pool({ connectionString: databaseUrl, max: 2 })
const suffix = crypto.randomUUID()
const fixtureEmail = `password-reset-e2e-${suffix}@novvin.invalid`
const firstPassword = `First-${suffix}!`
const resetPassword = `Reset-${suffix}!`
const finalPassword = `Final-${suffix}!`

async function jsonRequest(path, options = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    ...options,
    headers: {
      accept: 'application/json',
      'content-type': 'application/json',
      ...(options.headers || {}),
    },
  })
  const text = await response.text()
  let body = {}
  try {
    body = text ? JSON.parse(text) : {}
  } catch {
    body = { message: text }
  }
  return { body, response }
}

async function cleanupFixture() {
  const { rows } = await pool.query(
    'SELECT id::text FROM fallback_users WHERE email = $1 LIMIT 1',
    [fixtureEmail],
  )
  if (!rows[0]) return
  const fixtureId = rows[0].id

  await pool.query('BEGIN')
  try {
    await pool.query('DELETE FROM fallback_profile_moderation WHERE lower(user_id) = lower($1)', [fixtureId])
    await pool.query('DELETE FROM fallback_profile_overrides WHERE user_id = $1::uuid', [fixtureId])
    await pool.query('DELETE FROM fallback_password_reset_tokens WHERE user_id = $1::uuid', [fixtureId])
    await pool.query('DELETE FROM fallback_users WHERE id = $1::uuid', [fixtureId])
    await pool.query('COMMIT')
  } catch (error) {
    await pool.query('ROLLBACK')
    throw error
  }
}

async function expectLogin(password, expectedStatus) {
  const login = await jsonRequest('/v1/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email: fixtureEmail, password }),
  })
  if (login.response.status !== expectedStatus) {
    throw new Error(
      `Unexpected login status for fixture: expected ${expectedStatus}, got ${login.response.status} ${JSON.stringify(login.body)}`,
    )
  }
  return login.body
}

async function run() {
  try {
    const registration = await jsonRequest('/v1/auth/register', {
      method: 'POST',
      body: JSON.stringify({
        email: fixtureEmail,
        password: firstPassword,
        displayName: 'Cuenta ficticia de recuperación de contraseña',
      }),
    })
    if (!registration.response.ok) {
      throw new Error(`Fixture registration failed: ${registration.response.status} ${JSON.stringify(registration.body)}`)
    }

    const request = await jsonRequest('/v1/auth/password-reset/request', {
      method: 'POST',
      body: JSON.stringify({ email: fixtureEmail }),
    })
    if (!request.response.ok) {
      throw new Error(`Password-reset request failed: ${request.response.status} ${JSON.stringify(request.body)}`)
    }
    const resetToken = String(request.body?.resetToken || '')
    if (!resetToken) {
      throw new Error(
        `The E2E environment must use PASSWORD_RESET_DELIVERY=response; received ${JSON.stringify(request.body)}`,
      )
    }

    const confirmation = await jsonRequest('/v1/auth/password-reset/confirm', {
      method: 'POST',
      body: JSON.stringify({ resetToken, newPassword: resetPassword }),
    })
    if (!confirmation.response.ok || !confirmation.body?.accessToken) {
      throw new Error(`Password-reset confirmation failed: ${confirmation.response.status} ${JSON.stringify(confirmation.body)}`)
    }

    await expectLogin(firstPassword, 401)
    const resetLogin = await expectLogin(resetPassword, 200)
    const resetAccessToken = String(resetLogin.accessToken || '')
    if (!resetAccessToken) throw new Error('Login after reset returned no access token')

    const change = await jsonRequest('/v1/auth/change-password', {
      method: 'POST',
      headers: { authorization: `Bearer ${resetAccessToken}` },
      body: JSON.stringify({
        currentPassword: resetPassword,
        newPassword: finalPassword,
      }),
    })
    if (!change.response.ok || !change.body?.accessToken) {
      throw new Error(`Authenticated password change failed: ${change.response.status} ${JSON.stringify(change.body)}`)
    }

    await expectLogin(resetPassword, 401)
    await expectLogin(finalPassword, 200)

    const passwordState = await pool.query(
      `SELECT password_enabled,
              local_password_set_at IS NOT NULL AS local_password_set,
              password_changed_by_user_at IS NOT NULL AS password_changed_by_user
       FROM fallback_users
       WHERE email = $1
       LIMIT 1`,
      [fixtureEmail],
    )
    const state = passwordState.rows[0]
    if (!state?.password_enabled || !state?.local_password_set || !state?.password_changed_by_user) {
      throw new Error(`Password persistence flags are incomplete: ${JSON.stringify(state)}`)
    }

    console.log(JSON.stringify({
      ok: true,
      passwordResetRequested: true,
      resetTokenConsumed: true,
      oldPasswordRejected: true,
      resetPasswordAccepted: true,
      authenticatedChangeAccepted: true,
      previousPasswordRejected: true,
      finalPasswordAccepted: true,
      persisted: true,
    }))
  } finally {
    await cleanupFixture()
    await pool.end()
  }
}

run().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
