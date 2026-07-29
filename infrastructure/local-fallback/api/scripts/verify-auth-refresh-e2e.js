const crypto = require('crypto')
const { Pool } = require('pg')

const databaseUrl = process.env.DATABASE_URL
const baseUrl = String(process.env.E2E_API_URL || 'http://127.0.0.1:3001').replace(/\/+$/, '')

if (!databaseUrl) throw new Error('DATABASE_URL is required')

const pool = new Pool({ connectionString: databaseUrl, max: 2 })
const suffix = crypto.randomUUID()
const fixtureEmail = `auth-refresh-e2e-${suffix}@novvin.invalid`
const fixturePassword = `Refresh-${suffix}!`

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
  await pool.query('DELETE FROM fallback_users WHERE email = $1', [fixtureEmail])
}

async function run() {
  try {
    const registration = await jsonRequest('/v1/auth/register', {
      method: 'POST',
      body: JSON.stringify({
        email: fixtureEmail,
        password: fixturePassword,
        displayName: 'Cuenta ficticia de renovación',
      }),
    })
    if (!registration.response.ok) {
      throw new Error(`Fixture registration failed: ${registration.response.status} ${JSON.stringify(registration.body)}`)
    }

    const firstAccess = String(registration.body?.accessToken || '')
    const firstRefresh = String(registration.body?.refreshToken || '')
    if (!firstAccess || !firstRefresh || firstAccess === firstRefresh) {
      throw new Error('Registration did not return separate access and refresh credentials')
    }

    const rotation = await jsonRequest('/v1/auth/refresh', {
      method: 'POST',
      body: JSON.stringify({ refreshToken: firstRefresh }),
    })
    if (!rotation.response.ok) {
      throw new Error(`Refresh rotation failed: ${rotation.response.status} ${JSON.stringify(rotation.body)}`)
    }
    const secondAccess = String(rotation.body?.accessToken || '')
    const secondRefresh = String(rotation.body?.refreshToken || '')
    if (!secondAccess || !secondRefresh || secondRefresh === firstRefresh) {
      throw new Error('Refresh rotation did not issue a new credential pair')
    }

    const me = await jsonRequest('/v1/auth/me', {
      headers: { authorization: `Bearer ${secondAccess}` },
    })
    if (!me.response.ok || me.body?.user?.email !== fixtureEmail) {
      throw new Error(`Rotated access token was rejected: ${me.response.status} ${JSON.stringify(me.body)}`)
    }

    const replay = await jsonRequest('/v1/auth/refresh', {
      method: 'POST',
      body: JSON.stringify({ refreshToken: firstRefresh }),
    })
    if (replay.response.status !== 401 || replay.body?.error !== 'INVALID_REFRESH_TOKEN') {
      throw new Error(`Consumed refresh token was not rejected: ${replay.response.status} ${JSON.stringify(replay.body)}`)
    }

    const revokedAccess = await jsonRequest('/v1/auth/me', {
      headers: { authorization: `Bearer ${firstAccess}` },
    })
    if (revokedAccess.response.status !== 401) {
      throw new Error(`Access token from rotated session remained active: ${revokedAccess.response.status}`)
    }

    console.log(JSON.stringify({
      ok: true,
      distinctCredentials: true,
      rotationAccepted: true,
      replayRejected: true,
      previousAccessRevoked: true,
      fixtureRemoved: true,
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
