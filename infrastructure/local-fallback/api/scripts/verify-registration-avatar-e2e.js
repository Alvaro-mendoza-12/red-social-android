const crypto = require('crypto')
const { Pool } = require('pg')

const databaseUrl = process.env.DATABASE_URL
const baseUrl = String(process.env.E2E_API_URL || 'http://127.0.0.1:3001').replace(/\/+$/, '')

if (!databaseUrl) throw new Error('DATABASE_URL is required')

const pool = new Pool({ connectionString: databaseUrl, max: 2 })
const suffix = crypto.randomUUID()
const fixtureEmail = `registration-avatar-e2e-${suffix}@novvin.invalid`
const fixturePassword = `Avatar-${suffix}!`
const missingEmail = `missing-registration-e2e-${suffix}@novvin.invalid`
const tinyPng = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
  'base64',
)

async function request(path, options = {}) {
  const response = await fetch(`${baseUrl}${path}`, options)
  const text = await response.text()
  let body = {}
  try {
    body = text ? JSON.parse(text) : {}
  } catch {
    body = { message: text }
  }
  return { body, response }
}

async function jsonRequest(path, options = {}) {
  return request(path, {
    ...options,
    headers: {
      accept: 'application/json',
      'content-type': 'application/json',
      ...(options.headers || {}),
    },
  })
}

async function cleanupFixture() {
  await pool.query('DELETE FROM fallback_users WHERE email = $1', [fixtureEmail])
}

async function run() {
  let accessToken = ''
  let avatarPath = ''
  try {
    const missingLogin = await jsonRequest('/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email: missingEmail, password: fixturePassword }),
    })
    if (missingLogin.response.status !== 404 || missingLogin.body?.error !== 'ACCOUNT_NOT_FOUND') {
      throw new Error(
        `Missing account was not identified: ${missingLogin.response.status} ${JSON.stringify(missingLogin.body)}`,
      )
    }

    const registration = await jsonRequest('/v1/auth/register', {
      method: 'POST',
      body: JSON.stringify({
        email: fixtureEmail,
        password: fixturePassword,
        displayName: 'Cuenta ficticia con foto',
      }),
    })
    if (!registration.response.ok) {
      throw new Error(`Fixture registration failed: ${registration.response.status} ${JSON.stringify(registration.body)}`)
    }

    accessToken = String(registration.body?.accessToken || '')
    const fixtureId = String(registration.body?.user?.id || '')
    if (!accessToken || !fixtureId) throw new Error('Registration returned an incomplete session')

    const wrongPassword = await jsonRequest('/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email: fixtureEmail, password: `${fixturePassword}-wrong` }),
    })
    if (wrongPassword.response.status !== 401 || wrongPassword.body?.error !== 'INVALID_PASSWORD') {
      throw new Error(
        `Wrong password was not identified: ${wrongPassword.response.status} ${JSON.stringify(wrongPassword.body)}`,
      )
    }

    avatarPath = `${fixtureId}/${crypto.randomUUID()}_profile.png`
    const upload = await request(
      `/storage/v1/object/avatars/${avatarPath.split('/').map(encodeURIComponent).join('/')}`,
      {
        method: 'PUT',
        headers: {
          authorization: `Bearer ${accessToken}`,
          'content-type': 'image/png',
          'x-upsert': 'true',
        },
        body: tinyPng,
      },
    )
    if (!upload.response.ok || !upload.body?.publicUrl) {
      throw new Error(`Avatar upload failed: ${upload.response.status} ${JSON.stringify(upload.body)}`)
    }

    const avatarUrl = String(upload.body.publicUrl)
    const profileUpdate = await jsonRequest(`/rest/v1/profiles?id=eq.${encodeURIComponent(fixtureId)}`, {
      method: 'PATCH',
      headers: { authorization: `Bearer ${accessToken}` },
      body: JSON.stringify({ avatar_url: avatarUrl }),
    })
    if (!profileUpdate.response.ok) {
      throw new Error(
        `Profile avatar persistence failed: ${profileUpdate.response.status} ${JSON.stringify(profileUpdate.body)}`,
      )
    }

    const profile = await jsonRequest(
      `/rest/v1/profiles?id=eq.${encodeURIComponent(fixtureId)}&select=id,email,avatar_url&limit=1`,
      { headers: { authorization: `Bearer ${accessToken}` } },
    )
    const savedAvatar = String(profile.body?.[0]?.avatar_url || '')
    if (!profile.response.ok || savedAvatar !== avatarUrl) {
      throw new Error(`Saved profile did not return its avatar: ${profile.response.status} ${JSON.stringify(profile.body)}`)
    }

    const publicAvatar = await request(new URL(avatarUrl).pathname)
    if (!publicAvatar.response.ok || !String(publicAvatar.response.headers.get('content-type')).startsWith('image/')) {
      throw new Error(`Saved avatar is not publicly readable: ${publicAvatar.response.status}`)
    }

    console.log(JSON.stringify({
      ok: true,
      accountNotFoundDistinguished: true,
      invalidPasswordDistinguished: true,
      registrationAccepted: true,
      avatarUploaded: true,
      avatarPersistedInProfile: true,
      avatarPubliclyReadable: true,
    }))
  } finally {
    if (accessToken && avatarPath) {
      await request(`/v1/storage/objects/avatars/${avatarPath.split('/').map(encodeURIComponent).join('/')}`, {
        method: 'DELETE',
        headers: { authorization: `Bearer ${accessToken}` },
      }).catch(() => {})
    }
    await cleanupFixture()
    await pool.end()
  }
}

run().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
