'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const jwt = require('jsonwebtoken')
const express = require('express')
const { issueGuestIdentity, guestAuth, GUEST_AUDIENCE, registerGuestModeRoutes } = require('../src/guest-mode-routes')

const secret = 'test-secret-only'
const issuer = 'novvin-test'

test('detail lookup cannot revive a private local item from an old public import', async () => {
  const { readPublicContent } = require('../src/guest-mode-routes')
  for (const kind of ['videos', 'posts']) {
    const statements = []
    const pool = { async query(sql) {
      statements.push(sql)
      if (sql.includes('to_regclass')) return { rows: [{ table_oid: 'exists' }] }
      return { rows: [] }
    } }
    assert.equal(await readPublicContent(pool, kind, cryptoId()), null)
    assert.match(statements.at(-1), /NOT EXISTS \(SELECT 1 FROM fallback_(videos|posts)/)
  }
})

test('comments require publicly readable content and include local comments on imported content', async () => {
  const app = express()
  const statements = []
  let publicContent = false
  const pool = { async query(sql) {
    statements.push(sql)
    if (sql.includes('FROM fallback_videos WHERE')) {
      return { rows: publicContent ? [{ id: cryptoId(), video_url: 'https://example.invalid/v.mp4' }] : [] }
    }
    if (sql.includes('FROM fallback_video_comments c WHERE')) {
      return { rows: [{ id: 'comment-1', user_name: 'Usuario', content: 'Comentario público' }] }
    }
    return { rows: [] }
  } }
  registerGuestModeRoutes(app, { pool, jwt, jwtSecret: secret, serviceName: issuer })
  const server = app.listen(0, '127.0.0.1')
  try {
    await new Promise((resolve) => server.once('listening', resolve))
    const base = `http://127.0.0.1:${server.address().port}`
    const headers = { Authorization: `Bearer ${issueGuestIdentity(jwt, secret, issuer).accessToken}` }
    const path = `${base}/v1/guest/videos/${cryptoId()}/comments`
    assert.equal((await fetch(path, { headers })).status, 404)
    assert.ok(!statements.some((sql) => sql.includes('FROM fallback_video_comments')))
    publicContent = true
    const response = await fetch(path, { headers })
    assert.equal(response.status, 200)
    assert.equal((await response.json()).items[0].content, 'Comentario público')
    assert.ok(statements.some((sql) => sql.includes('FROM fallback_video_comments c WHERE')))
  } finally { await new Promise((resolve) => server.close(resolve)) }
})

test('guest identity is random, explicit and cannot authenticate as a registered client', () => {
  const first = issueGuestIdentity(jwt, secret, issuer)
  const second = issueGuestIdentity(jwt, secret, issuer)
  assert.equal(first.role, 'guest')
  assert.match(first.id, /^guest_[0-9a-f-]{36}$/)
  assert.match(first.username, /^guest[0-9a-f]{12}$/)
  assert.notEqual(first.id, second.id)
  assert.notEqual(first.username, second.username)
  assert.throws(() => jwt.verify(first.accessToken, secret, {
    issuer, audience: 'conectate-clients',
  }), /audience/)
  assert.equal(jwt.verify(first.accessToken, secret, { issuer, audience: GUEST_AUDIENCE }).role, 'guest')
})

test('guest middleware rejects registered and forged credentials', () => {
  const middleware = guestAuth(jwt, secret, issuer)
  const registered = jwt.sign({ sub: cryptoId(), role: 'registered' }, secret, {
    issuer, audience: 'conectate-clients',
  })
  for (const token of [registered, 'invalid']) {
    let status
    const res = { status(code) { status = code; return this }, json(body) { return body } }
    middleware({ headers: { authorization: `Bearer ${token}` } }, res, () => {
      throw new Error('unexpected access')
    })
    assert.equal(status, 401)
  }
})

function cryptoId() { return '550e8400-e29b-41d4-a716-446655440000' }

test('guest API serves public reads and exposes no mutation route', async () => {
  const app = express()
  const sql = []
  registerGuestModeRoutes(app, {
    pool: { async query(statement) { sql.push(statement); return { rows: [] } } },
    jwt, jwtSecret: secret, serviceName: issuer,
  })
  const server = app.listen(0, '127.0.0.1')
  try {
    await new Promise((resolve) => server.once('listening', resolve))
    const base = `http://127.0.0.1:${server.address().port}`
    const session = await fetch(`${base}/v1/guest/session`, { method: 'POST' })
    assert.equal(session.status, 201)
    const identity = await session.json()
    const denied = await fetch(`${base}/v1/guest/videos`)
    assert.equal(denied.status, 401)
    const headers = { Authorization: `Bearer ${identity.accessToken}` }
    const visible = await fetch(`${base}/v1/guest/videos`, { headers })
    assert.deepEqual(await visible.json(), { items: [] })
    assert.ok(sql.some((statement) => /is_public = TRUE AND is_sensitive = FALSE/.test(statement)))
    const mutation = await fetch(`${base}/v1/guest/videos`, { method: 'POST', headers })
    assert.equal(mutation.status, 404)
  } finally {
    await new Promise((resolve) => server.close(resolve))
  }
})
