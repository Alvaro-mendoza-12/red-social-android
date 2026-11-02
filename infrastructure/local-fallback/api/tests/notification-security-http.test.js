'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const express = require('express')
const { once } = require('node:events')
const { registerDataRoutes } = require('../src/data-routes')

test('HTTP alert inserts reject forged security events before any write or dispatch', async () => {
  const actor = '11111111-1111-4111-8111-111111111111'
  const recipient = '22222222-2222-4222-8222-222222222222'
  let reads = 0
  const pool = { async query(sql, values) {
    assert.match(sql, /FROM fallback_users/)
    assert.deepEqual(values, [actor])
    reads++
    return { rows: [{ id: actor, email: 'isolated@example.invalid', display_name: 'Fixture' }] }
  } }
  const app = express()
  app.use(express.json())
  registerDataRoutes(app, { pool, authRequired(req, res, next) {
    if (req.get('X-Isolated-Fixture') !== actor) return res.status(401).json({ error: 'AUTH_REQUIRED' })
    req.auth = { sub: actor }
    next()
  } })
  app.use((error, _req, res, _next) => res.status(500).json({ error: error.message }))
  const server = app.listen(0, '127.0.0.1')
  try {
    await once(server, 'listening')
    const url = `http://127.0.0.1:${server.address().port}/v1/data/notifications`
    const unauthenticated = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ recipientId: recipient, type: 'account_suspended' }) })
    assert.equal(unauthenticated.status, 401)
    assert.equal(reads, 0)
    const attempts = [
      { type: 'account_suspended' },
      { type: 'account_enabled' },
      { type: 'message', extraData: { notification_type: 'account_suspended' } },
      { extra_data: {}, extraData: { notificationType: 'ACCOUNT_SUSPENDED' } },
      { type: 'message', content: 'Tu cuenta fue suspendida por un texto falso.' },
    ]
    for (const attempt of attempts) {
      const response = await fetch(url, { method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Isolated-Fixture': actor },
        body: JSON.stringify({ ...attempt, recipientId: recipient }) })
      assert.equal(response.status, 403)
      assert.deepEqual(await response.json(), { error: 'SERVER_MANAGED_NOTIFICATION' })
    }
    assert.equal(reads, attempts.length)
  } finally {
    await new Promise(resolve => server.close(resolve))
  }
})
