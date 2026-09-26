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

    const renewal = await jsonRequest('/v1/auth/refresh', {
      method: 'POST',
      body: JSON.stringify({ refreshToken: firstRefresh }),
    })
    if (!renewal.response.ok) {
      throw new Error(`Refresh renewal failed: ${renewal.response.status} ${JSON.stringify(renewal.body)}`)
    }
    const renewedAccess = String(renewal.body?.accessToken || '')
    const renewedRefresh = String(renewal.body?.refreshToken || '')
    if (!renewedAccess || renewedRefresh !== firstRefresh) {
      throw new Error('Active session renewal did not preserve its refresh credential')
    }

    const me = await jsonRequest('/v1/auth/me', {
      headers: { authorization: `Bearer ${renewedAccess}` },
    })
    if (!me.response.ok || me.body?.user?.email !== fixtureEmail) {
      throw new Error(`Renewed access token was rejected: ${me.response.status} ${JSON.stringify(me.body)}`)
    }

    const secondLogin = await jsonRequest('/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email: fixtureEmail, password: fixturePassword }),
    })
    const secondRefresh = String(secondLogin.body?.refreshToken || '')
    if (!secondLogin.response.ok || !secondRefresh || secondRefresh === firstRefresh) {
      throw new Error(`Second independent login failed: ${secondLogin.response.status}`)
    }

    const logout = await jsonRequest('/v1/auth/logout', {
      method: 'POST',
      body: JSON.stringify({ refreshToken: firstRefresh }),
    })
    if (!logout.response.ok || logout.body?.revoked !== true) {
      throw new Error(`Mobile logout did not revoke its session: ${logout.response.status}`)
    }
    const repeatedLogout = await jsonRequest('/v1/auth/logout', {
      method: 'POST',
      body: JSON.stringify({ refreshToken: firstRefresh }),
    })
    if (!repeatedLogout.response.ok || repeatedLogout.body?.revoked !== false) {
      throw new Error('Repeated mobile logout was not idempotent')
    }

    const revokedAccess = await jsonRequest('/v1/auth/me', {
      headers: { authorization: `Bearer ${firstAccess}` },
    })
    if (revokedAccess.response.status !== 401) {
      throw new Error(`Access token from revoked session remained active: ${revokedAccess.response.status}`)
    }

    const revokedRefresh = await jsonRequest('/v1/auth/refresh', {
      method: 'POST',
      body: JSON.stringify({ refreshToken: firstRefresh, accessToken: firstAccess }),
    })
    if (revokedRefresh.response.status !== 401 || revokedRefresh.body?.error !== 'INVALID_REFRESH_TOKEN') {
      throw new Error(`Revoked session recovered through another active session: ${revokedRefresh.response.status}`)
    }

    const independentRefresh = await jsonRequest('/v1/auth/refresh', {
      method: 'POST',
      body: JSON.stringify({ refreshToken: secondRefresh }),
    })
    if (!independentRefresh.response.ok) {
      throw new Error(`Independent active session was affected: ${independentRefresh.response.status}`)
    }

    console.log(JSON.stringify({
      ok: true,
      distinctCredentials: true,
      activeRenewalAccepted: true,
      revokedRefreshRejected: true,
      previousAccessRevoked: true,
      mobileLogoutRevokedOneSession: true,
      independentSessionStillActive: true,
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
