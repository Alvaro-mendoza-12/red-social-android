'use strict'

const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const { Client } = require('pg')
const base = String(process.env.E2E_API_URL || 'http://127.0.0.1:3001').replace(/\/+$/, '')
const suffix = crypto.randomBytes(6).toString('hex')
const emails = [1, 2, 3].map((n) => `username-guest-e2e-${suffix}-${n}@example.invalid`)
const handle = `auditoria${suffix}`
const password = `Test-${crypto.randomBytes(18).toString('hex')}!`
const contentId = crypto.randomUUID()
const checks = []

async function request(path, method = 'GET', body, token) {
  const started = Date.now()
  const response = await fetch(`${base}${path}`, {
    method, headers: { 'content-type': 'application/json',
      ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(15_000),
  })
  const payload = await response.json()
  return { status: response.status, body: payload, elapsedMs: Date.now() - started }
}
function check(name, condition) { assert.ok(condition, name); checks.push(name) }
function noPrivateFields(value) {
  if (Array.isArray(value)) return value.every(noPrivateFields)
  if (!value || typeof value !== 'object') return true
  return Object.entries(value).every(([key, item]) =>
    !/^(email|phone|password|password_hash|accessToken|refreshToken)$/i.test(key) && noPrivateFields(item))
}

async function main() {
  const db = new Client({ connectionString: process.env.DATABASE_URL })
  await db.connect()
  try {
    const availability = await request(`/v1/auth/username/availability?username=${handle}`)
    check('initial handle available', availability.status === 200 && availability.body.available)
    const register = (email, username) => request('/v1/auth/register', 'POST', {
      email, username, password, displayName: 'Nombre visible repetido', client: 'android',
    })
    const racing = await Promise.all(emails.slice(0, 2).map((email) => register(email, handle)))
    check('concurrent registration has one winner', racing.filter((r) => r.status === 201).length === 1)
    check('concurrent registration rejects duplicate', racing.some((r) => r.status === 409 && r.body.error === 'USERNAME_TAKEN'))
    const winner = racing.find((r) => r.status === 201)
    const email = winner.body.user.email
    const token = winner.body.accessToken
    const id = winner.body.user.id
    check('registration creates complete session', Boolean(token && id))
    const sameName = await register(emails[2], `${handle}b`)
    check('visible names may repeat', sameName.status === 201)
    const occupied = await request(`/v1/auth/username/availability?username=${handle.toUpperCase()}&name=Nombre%20visible`)
    check('case insensitive availability', occupied.status === 200 && !occupied.body.available)
    check('recommendations supplied', occupied.body.suggestions?.length > 0)
    for (const suggestion of occupied.body.suggestions) {
      check('recommendation is available', (await request(`/v1/auth/username/availability?username=${suggestion}`)).body.available)
    }
    check('reserved handle rejected', (await request('/v1/auth/username/availability?username=admin')).body.reason === 'USERNAME_RESERVED')
    check('invalid characters rejected', (await request('/v1/auth/username/availability?username=nombre%20apellido')).body.reason === 'USERNAME_INVALID')
    const login = await request('/v1/auth/login', 'POST', { email, password, client: 'android' })
    check('correct password logs in', login.status === 200 && Boolean(login.body.accessToken))
    check('incorrect password rejected', (await request('/v1/auth/login', 'POST', { email, password: `${password}wrong`, client: 'android' })).status === 401)
    check('display name remains editable', (await request('/v1/data/profile/me', 'PATCH', { display_name: 'Nombre cambiado', username: handle }, token)).status === 200)
    for (const username of [`${handle}x`, null, '']) {
      check('permanent handle protected in data API', (await request('/v1/data/profile/me', 'PATCH', { username }, token)).status === 409)
      check('permanent handle protected in compatibility API',
        (await request(`/rest/v1/profiles?id=eq.${id}`, 'PATCH', { username }, token)).status === 409)
    }
    await assert.rejects(db.query(`UPDATE fallback_profile_overrides SET profile = profile - 'username' WHERE user_id = $1`, [id]),
      (error) => error.code === 'P0001' && error.message === 'USERNAME_IMMUTABLE')
    checks.push('database rejects handle removal')

    const guest = await request('/v1/guest/session', 'POST', {})
    check('guest identity generated', guest.status === 201 && guest.body.role === 'guest' && /^guest_/.test(guest.body.id))
    const guestToken = guest.body.accessToken
    for (const path of ['videos', 'posts', 'moments', 'profiles/search?q=ad']) {
      const result = await request(`/v1/guest/${path}`, 'GET', undefined, guestToken)
      check(`public ${path} readable`, result.status === 200 && noPrivateFields(result.body))
      if ((path === 'videos' || path === 'posts') && result.body.items[0]) {
        const itemId = result.body.items[0].id
        check(`public ${path} detail readable`, (await request(`/v1/guest/${path}/${itemId}`, 'GET', undefined, guestToken)).status === 200)
        check(`public ${path} comments readable`, (await request(`/v1/guest/${path}/${itemId}/comments`, 'GET', undefined, guestToken)).status === 200)
      }
    }
    const paths = [
      '/v1/data/messages/chats', '/v1/data/messages/groups', '/v1/data/messages/communities',
      '/v1/data/feed/posts', `/v1/data/feed/posts/${contentId}/comments`,
      `/v1/data/feed/posts/${contentId}/reaction`, `/v1/data/profiles/${id}/follow`,
      '/v1/data/videos', `/v1/data/videos/${contentId}/comments`,
      `/v1/data/videos/${contentId}/reaction`, `/v1/data/videos/${contentId}/favorite`,
      '/v1/data/moments', '/v1/data/voice/rooms', `/v1/data/voice/rooms/${contentId}/join`,
      '/v1/data/live', `/v1/data/live/${contentId}/sfu-token`,
      '/rest/v1/rpc/send_virtual_gift', '/rest/v1/rpc/verify_google_play_purchase',
      '/rest/v1/profiles', '/rest/v1/messages', '/rest/v1/friend_requests',
      `/storage/v1/object/avatars/${id}/guest-test.png`,
    ]
    for (const path of paths) {
      const result = await request(path, 'POST', {}, guestToken)
      check(`guest mutation denied ${path}`, result.status === 401 || result.status === 403)
    }
    // A local private row must override an older imported public row at every read path.
    const imported = await db.query("SELECT to_regclass('supabase_import.videos') AS table_oid")
    if (imported.rows[0].table_oid) {
      await db.query(`INSERT INTO supabase_import.videos(id, ispublic, url) VALUES ($1, 'true', 'https://example.invalid/private.mp4')`, [contentId])
      await db.query(`INSERT INTO fallback_videos(id, user_id, video_url, is_public) VALUES ($1, $2, 'https://example.invalid/private.mp4', FALSE)`, [contentId, id])
      for (const tail of ['', '/comments']) {
        check('private local content masks stale public import', (await request(`/v1/guest/videos/${contentId}${tail}`, 'GET', undefined, guestToken)).status === 404)
      }
    }
    console.log(JSON.stringify({ ok: true, checks: checks.length, loginMs: login.elapsedMs, modulesWithDeniedWrites: paths.length }))
  } finally {
    await db.query('DELETE FROM fallback_videos WHERE id = $1', [contentId])
    const imported = await db.query("SELECT to_regclass('supabase_import.videos') AS table_oid")
    if (imported.rows[0].table_oid) await db.query('DELETE FROM supabase_import.videos WHERE id = $1', [contentId])
    // Remove only this run's fixtures, including compatibility rows without an FK.
    const fixtures = await db.query('SELECT id, supabase_user_id FROM fallback_users WHERE email = ANY($1::text[])', [emails])
    for (const row of fixtures.rows) {
      const ids = [row.id, row.supabase_user_id].filter(Boolean)
      await db.query('DELETE FROM fallback_compat_rows WHERE row_id = ANY($1::text[])', [ids])
      if (row.supabase_user_id && row.supabase_user_id !== row.id &&
          /^(1|true|yes|on)$/i.test(process.env.LEGACY_SUPABASE_IMPORT_ENABLED || '')) {
        const key = process.env.SUPABASE_SECRET_KEY || process.env.SUPABASE_SERVICE_ROLE_KEY
        const url = String(process.env.SUPABASE_URL || '').replace(/\/+$/, '')
        if (key && url) {
          const response = await fetch(`${url}/auth/v1/admin/users/${row.supabase_user_id}`, {
            method: 'DELETE', headers: { apikey: key, authorization: `Bearer ${key}` },
            signal: AbortSignal.timeout(10_000),
          })
          assert.ok(response.ok || response.status === 404, 'cleanup of mirrored synthetic account')
        }
      }
    }
    await db.query('DELETE FROM fallback_users WHERE email = ANY($1::text[])', [emails])
    await db.end()
  }
}

main().catch((error) => { console.error(error.message); process.exitCode = 1 })
