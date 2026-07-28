const jwt = require('jsonwebtoken')
const { Pool } = require('pg')

const databaseUrl = process.env.DATABASE_URL
const jwtSecret = String(process.env.JWT_SECRET || '')
const baseUrl = String(process.env.E2E_API_URL || 'http://127.0.0.1:3001').replace(/\/+$/, '')

if (!databaseUrl || jwtSecret.length < 32) {
  throw new Error('DATABASE_URL and JWT_SECRET are required')
}

const pool = new Pool({ connectionString: databaseUrl, max: 2 })

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
  try {
    const actor = await findAdminActor()
    const accessToken = jwt.sign(
      { email: actor.email, provider: 'local-fallback' },
      jwtSecret,
      {
        subject: actor.id,
        expiresIn: '5m',
        issuer: 'conectate-local-fallback',
        audience: 'conectate-clients',
      },
    )

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
      adminId: actor.id,
      endpoints: results,
    }))
  } finally {
    await pool.end()
  }
}

run().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
