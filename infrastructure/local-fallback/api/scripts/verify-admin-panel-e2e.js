const jwt = require('jsonwebtoken')
const crypto = require('crypto')
const { Pool } = require('pg')
const {
  ADMIN_CAPABILITY,
  adminAccessContract,
} = require('../src/admin-capabilities')

const databaseUrl = process.env.DATABASE_URL
const jwtSecret = String(process.env.JWT_SECRET || '')
const baseUrl = String(process.env.E2E_API_URL || 'http://127.0.0.1:3001').replace(/\/+$/, '')
const expectedAdminCount = Math.max(3, Number.parseInt(process.env.EXPECTED_ADMIN_COUNT || '3', 10) || 3)

if (!databaseUrl || jwtSecret.length < 32) {
  throw new Error('DATABASE_URL and JWT_SECRET are required')
}

const pool = new Pool({ connectionString: databaseUrl, max: 2 })

async function findAdminActors() {
  const { rows } = await pool.query(
    `SELECT u.id::text AS id,
            u.email,
            COALESCE(m.admin_access_level, CASE WHEN m.is_admin THEN 'full' ELSE 'none' END) AS access_level,
            COALESCE(m.admin_is_owner, FALSE) AS is_owner
     FROM fallback_users u
     JOIN fallback_profile_moderation m
       ON lower(m.user_id) IN (
         lower(u.id::text),
         lower(COALESCE(u.supabase_user_id::text, ''))
       )
     WHERE m.is_admin = TRUE
     ORDER BY m.updated_at DESC
     LIMIT 20`,
  )
  if (rows.length < expectedAdminCount) {
    throw new Error(`Expected at least ${expectedAdminCount} configured administrators; found ${rows.length}`)
  }
  return rows.slice(0, expectedAdminCount)
}

async function jsonRequest(path, accessToken) {
  const response = await fetch(`${baseUrl}${path}`, {
    headers: {
      accept: 'application/json',
      authorization: `Bearer ${accessToken}`,
    },
  })
  const text = await response.text()
  let body = {}
  try {
    body = text ? JSON.parse(text) : {}
  } catch {
    body = { message: text }
  }
  if (!response.ok) {
    throw new Error(`${path} failed: ${response.status} ${JSON.stringify(body)}`)
  }
  return body
}

async function run() {
  const diagnosticSessionIds = []
  try {
    const actors = await findAdminActors()
    const actorsWithTokens = await Promise.all(actors.map(async (actor) => {
      const refreshHash = crypto.createHash('sha256').update(crypto.randomBytes(48)).digest('hex')
      const { rows } = await pool.query(
        `INSERT INTO fallback_auth_sessions(user_id, refresh_token_hash, client, user_agent, expires_at)
         VALUES ($1, $2, 'admin-panel-smoke', 'internal-admin-panel-smoke', NOW() + INTERVAL '5 minutes')
         RETURNING id`,
        [actor.id, refreshHash],
      )
      diagnosticSessionIds.push(rows[0].id)
      return {
        actor,
        accessToken: jwt.sign(
        { email: actor.email, provider: 'local-fallback', client: 'admin-panel-smoke', sid: rows[0].id },
        jwtSecret,
        {
          subject: actor.id,
          expiresIn: '5m',
          issuer: 'conectate-local-fallback',
          audience: 'conectate-clients',
        },
        ),
      }
    }))

    for (const { actor, accessToken } of actorsWithTokens) {
      const access = await jsonRequest('/v1/data/admin/access', accessToken)
      const expected = adminAccessContract({
        level: actor.access_level,
        isOwner: actor.is_owner === true,
      })
      if (JSON.stringify(access.capabilities) !== JSON.stringify(expected.capabilities)) {
        throw new Error('Admin capability contract does not match persisted access')
      }
      if (
        access.accessLevel !== expected.accessLevel ||
        access.isOwner !== expected.isOwner ||
        access.canWrite !== expected.canWrite ||
        access.canDeleteUsers !== expected.canDeleteUsers
      ) {
        throw new Error('Admin compatibility fields do not match named capabilities')
      }
      await jsonRequest('/v1/data/admin/catalog', accessToken)
      await jsonRequest('/v1/data/admin/users?limit=1', accessToken)
      await jsonRequest('/v1/data/admin/stats', accessToken)
    }

    const systemActor = actorsWithTokens.find(({ actor }) =>
      adminAccessContract({ level: actor.access_level, isOwner: actor.is_owner === true })
        .capabilities.includes(ADMIN_CAPABILITY.SYSTEM_READ))
    if (!systemActor) throw new Error('At least one full administrator is required for system health verification')
    const accessToken = systemActor.accessToken

    const endpoints = [
      '/v1/data/admin/access',
      '/v1/data/admin/catalog',
      '/v1/data/admin/users?limit=20',
      '/v1/data/admin/stats',
      '/v1/data/admin/suspended-profiles',
      '/v1/data/admin/moderation/appeals',
      '/v1/data/admin/reports',
      '/v1/data/admin/video-reports',
      '/v1/system/health',
    ]
    const results = {}
    for (const endpoint of endpoints) {
      const body = await jsonRequest(endpoint, accessToken)
      results[endpoint] = {
        ok: body?.ok !== false,
        itemCount: Array.isArray(body)
          ? body.length
          : Array.isArray(body?.items)
            ? body.items.length
            : undefined,
      }
    }

    console.log(JSON.stringify({
      ok: true,
      verifiedAdminAccounts: actors.length,
      verifiedCapabilityContracts: actors.length,
      endpoints: results,
    }))
  } finally {
    try {
      if (diagnosticSessionIds.length > 0) {
        await pool.query(
          `DELETE FROM fallback_auth_sessions
           WHERE id=ANY($1::uuid[]) AND client='admin-panel-smoke'`,
          [diagnosticSessionIds],
        )
      }
    } finally {
      await pool.end()
    }
  }
}

run().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
