'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const jwt = require('jsonwebtoken')
const express = require('express')
const { issueGuestIdentity, guestAuth, GUEST_AUDIENCE, registerGuestModeRoutes } = require('../src/guest-mode-routes')

const secret = 'test-secret-only'
const issuer = 'novvin-test'

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
