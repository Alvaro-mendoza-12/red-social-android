const crypto = require('crypto')
const { Pool } = require('pg')

const databaseUrl = process.env.DATABASE_URL
const baseUrl = String(process.env.E2E_API_URL || 'http://127.0.0.1:3001').replace(/\/+$/, '')
if (!databaseUrl) throw new Error('DATABASE_URL is required')

const pool = new Pool({ connectionString: databaseUrl, max: 1 })
const suffix = crypto.randomUUID()
const email = `live-audio-${suffix}@novvin.invalid`
const password = `Audio-${suffix}!`

async function jsonRequest(path, { token, ...options } = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    ...options,
    headers: {
      accept: 'application/json',
      'content-type': 'application/json',
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...(options.headers || {}),
    },
  })
  const text = await response.text()
  let body = {}
  try { body = text ? JSON.parse(text) : {} } catch { body = { error: 'NON_JSON_RESPONSE' } }
  return { response, body }
}

async function cleanup(userId, liveId) {
  if (liveId) await pool.query('DELETE FROM fallback_live_streams WHERE id = $1::uuid', [liveId])
  if (userId) {
    await pool.query(
      `DELETE FROM fallback_compat_rows
        WHERE lower(row_id) = lower($1)
           OR lower(COALESCE(row->>'user_id', '')) = lower($1)`,
      [userId],
    )
  }
  await pool.query('DELETE FROM fallback_users WHERE email = $1', [email])
}

async function run() {
  let userId = ''
  let liveId = ''
  try {
    const registration = await jsonRequest('/v1/auth/register', {
      method: 'POST',
      body: JSON.stringify({ email, password, displayName: 'Live Audio E2E' }),
    })
    if (!registration.response.ok || !registration.body?.accessToken || !registration.body?.user?.id) {
      throw new Error(`Fixture registration failed with HTTP ${registration.response.status}`)
    }
    const token = String(registration.body.accessToken)
    userId = String(registration.body.user.id)

    const created = await jsonRequest('/v1/data/live', {
      token,
      method: 'POST',
      body: JSON.stringify({ title: 'Live solo voz E2E', source: 'audio', hashtags: ['audio'] }),
    })
    if (created.response.status !== 201 || created.body?.stream?.source !== 'audio') {
      throw new Error(`Audio live creation failed with HTTP ${created.response.status}`)
    }
    liveId = String(created.body.stream.id)

    const activated = await jsonRequest(`/v1/data/live/${encodeURIComponent(liveId)}/state`, {
      token,
      method: 'PATCH',
      body: JSON.stringify({ isActive: true, source: 'audio' }),
    })
    if (!activated.response.ok || activated.body?.stream?.source !== 'audio' || activated.body?.stream?.is_active !== true) {
      throw new Error(`Audio live activation failed with HTTP ${activated.response.status}`)
    }

    const loaded = await jsonRequest(`/v1/data/live/${encodeURIComponent(liveId)}`, { token })
    if (!loaded.response.ok || loaded.body?.stream?.source !== 'audio') {
      throw new Error(`Audio live read failed with HTTP ${loaded.response.status}`)
    }

    console.log(JSON.stringify({
      ok: true,
      endpoint: '/v1/data/live',
      source: loaded.body.stream.source,
      active: loaded.body.stream.is_active === true,
      noCameraRequiredByWireModel: true,
    }))
  } finally {
    await cleanup(userId, liveId)
    await pool.end()
  }
}

run().catch(error => {
  console.error(JSON.stringify({ ok: false, error: error.message }))
  process.exitCode = 1
})
