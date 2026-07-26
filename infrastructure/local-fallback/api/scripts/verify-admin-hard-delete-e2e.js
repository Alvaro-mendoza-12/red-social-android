const crypto = require('crypto')
const jwt = require('jsonwebtoken')
const { Pool } = require('pg')

const databaseUrl = process.env.DATABASE_URL
const jwtSecret = String(process.env.JWT_SECRET || '')
const baseUrl = String(process.env.E2E_API_URL || 'http://127.0.0.1:3001').replace(/\/+$/, '')

if (!databaseUrl || jwtSecret.length < 32) {
  throw new Error('DATABASE_URL and JWT_SECRET are required')
}

const pool = new Pool({ connectionString: databaseUrl, max: 2 })
const fixtureSuffix = crypto.randomUUID()
const fixtureEmail = `admin-delete-e2e-${fixtureSuffix}@novvin.invalid`
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

async function findAdminActor() {
  const { rows } = await pool.query(
    `SELECT u.id::text AS id, u.email
     FROM fallback_users u
     JOIN fallback_profile_moderation m
       ON lower(m.user_id) IN (
         lower(u.id::text),
         lower(COALESCE(u.supabase_user_id::text, ''))
       )
     WHERE m.is_admin = TRUE
     ORDER BY m.updated_at DESC
     LIMIT 1`,
  )
  if (!rows[0]) throw new Error('No configured administrator was found')
  return rows[0]
}

async function cleanupFixture() {
  const { rows } = await pool.query(
    `SELECT id::text
     FROM fallback_users
     WHERE email = $1
     LIMIT 1`,
    [fixtureEmail],
  )
  if (!rows[0]) return

  const fixtureId = rows[0].id
  await pool.query('BEGIN')
  try {
    await pool.query(`DELETE FROM fallback_profile_moderation WHERE lower(user_id) = lower($1)`, [fixtureId])
    await pool.query(`DELETE FROM fallback_profile_overrides WHERE user_id = $1::uuid`, [fixtureId])
    await pool.query(`DELETE FROM fallback_password_reset_tokens WHERE user_id = $1::uuid`, [fixtureId])
    await pool.query(`DELETE FROM fallback_users WHERE id = $1::uuid`, [fixtureId])
    await pool.query('COMMIT')
  } catch (error) {
    await pool.query('ROLLBACK')
    throw error
  }
}

async function run() {
  let fixtureId = ''
  try {
    const actor = await findAdminActor()
    const actorToken = jwt.sign(
      { email: actor.email, provider: 'local-fallback' },
      jwtSecret,
      {
        subject: actor.id,
        expiresIn: '5m',
        issuer: 'conectate-local-fallback',
        audience: 'conectate-clients',
      },
    )

    const registration = await jsonRequest('/v1/auth/register', {
      method: 'POST',
      body: JSON.stringify({
        email: fixtureEmail,
        password: fixturePassword,
        displayName: 'Cuenta ficticia de borrado E2E',
      }),
    })
    if (!registration.response.ok) {
      throw new Error(`Fixture registration failed: ${registration.response.status} ${JSON.stringify(registration.body)}`)
    }
    fixtureId = String(registration.body?.user?.id || '')
    if (!fixtureId) throw new Error('Fixture registration returned no user id')

    const adminHeaders = { authorization: `Bearer ${actorToken}` }
    const suspension = await jsonRequest('/v1/data/admin/suspend', {
      method: 'POST',
      headers: adminHeaders,
      body: JSON.stringify({
        user_id: fixtureId,
        reason: 'Prueba automatizada aislada de eliminación completa',
        days: 1,
      }),
    })
    if (!suspension.response.ok) {
      throw new Error(`Fixture suspension failed: ${suspension.response.status} ${JSON.stringify(suspension.body)}`)
    }

    const deletion = await jsonRequest(`/v1/data/admin/users/${encodeURIComponent(fixtureId)}`, {
      method: 'DELETE',
      headers: adminHeaders,
      body: JSON.stringify({
        hard_delete: true,
        reason: 'Prueba automatizada aislada de eliminación completa',
        confirmation: `ELIMINAR ${fixtureId} ${fixtureEmail}`,
        allow_suspended_account_delete: true,
        acknowledge_irreversible: true,
        acknowledge_full_purge: true,
        expected_email: fixtureEmail,
      }),
    })
    if (!deletion.response.ok) {
      throw new Error(`Fixture deletion failed: ${deletion.response.status} ${JSON.stringify(deletion.body)}`)
    }

    const remaining = await pool.query(
      `SELECT EXISTS(
         SELECT 1 FROM fallback_users WHERE id = $1::uuid
       ) AS user_exists`,
      [fixtureId],
    )
    if (remaining.rows[0]?.user_exists) {
      throw new Error('Fixture still exists after successful delete response')
    }

    console.log(JSON.stringify({
      ok: true,
      deletedFixtureId: fixtureId,
      deletion: deletion.body?.deletion || {},
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
