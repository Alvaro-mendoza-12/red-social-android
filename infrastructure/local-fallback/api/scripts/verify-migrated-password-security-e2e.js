const crypto = require('crypto')
const { Pool } = require('pg')

const databaseUrl = process.env.DATABASE_URL
const baseUrl = String(process.env.E2E_API_URL || 'http://127.0.0.1:3001').replace(/\/+$/, '')

if (!databaseUrl) throw new Error('DATABASE_URL is required')

const pool = new Pool({ connectionString: databaseUrl, max: 2 })
const fixtureSuffix = crypto.randomUUID()
const fixtureEmail = `migrated-password-e2e-${fixtureSuffix}@novvin.invalid`
const fixturePassword = `Fixture-${fixtureSuffix}!`

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

async function run() {
  try {
    const registration = await jsonRequest('/v1/auth/register', {
      method: 'POST',
      body: JSON.stringify({
        email: fixtureEmail,
        password: fixturePassword,
        displayName: 'Cuenta ficticia de seguridad de contraseña',
      }),
    })
    if (!registration.response.ok) {
      throw new Error(`Fixture registration failed: ${registration.response.status} ${JSON.stringify(registration.body)}`)
    }
    const fixtureId = String(registration.body?.user?.id || '')
    if (!fixtureId) throw new Error('Fixture registration returned no user id')

    await pool.query(
      `UPDATE fallback_users
       SET password_hash = NULL,
           password_enabled = FALSE,
           supabase_user_id = NULL,
           sync_source = 'supabase'
       WHERE id = $1::uuid`,
      [fixtureId],
    )

    const rejectedLogin = await jsonRequest('/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify({
        email: fixtureEmail,
        password: 'This-password-must-not-authorize-migration',
      }),
    })
    if (rejectedLogin.response.status !== 401 || rejectedLogin.body?.error !== 'INVALID_PASSWORD') {
      throw new Error(
        `Migrated account accepted an unverified password: ${rejectedLogin.response.status} ${JSON.stringify(rejectedLogin.body)}`,
      )
    }
    if (rejectedLogin.body?.migrationToken || rejectedLogin.body?.accessToken) {
      throw new Error('Rejected migrated login leaked an authorization token')
    }

    console.log(JSON.stringify({
      ok: true,
      fixtureId,
      status: rejectedLogin.response.status,
      error: rejectedLogin.body.error,
      migrationTokenLeaked: false,
      accessTokenLeaked: false,
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
