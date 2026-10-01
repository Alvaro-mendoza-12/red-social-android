'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { registerPlayMatchRoutes } = require('../src/playmatch-routes')

async function decide(actor, status, decision) {
  const row = { id: 'connection', requester_user_id: 'sender', recipient_user_id: 'recipient', status, message: 'Private connection' }
  const queries = []
  let released = false
  const pool = { connect: async () => ({
    query: async sql => {
      queries.push(sql)
      if (sql.includes('SELECT * FROM fallback_gaming_connection_requests')) return { rows: [row] }
      if (sql === 'BEGIN' || sql === 'ROLLBACK') return { rows: [] }
      throw Error('Unexpected mutation in replay test')
    },
    release: () => { released = true },
  }) }
  let route
  const app = Object.fromEntries(['get', 'post', 'put', 'patch', 'delete'].map(verb => [verb, (path, ...handlers) => {
    if (verb === 'post' && path === '/v1/data/playmatch/connections/:connectionId/decision') route = handlers.at(-1)
  }]))
  registerPlayMatchRoutes(app, { pool, authRequired: () => {},
    getCurrentUser: async () => ({ id: actor }), userIdentity: user => ({ values: [user.id] }),
  })
  const result = { status: 200, body: null }
  const res = { status: code => { result.status = code; return res }, json: body => { result.body = body; return res } }
  const before = process.env.PLAYMATCH_CURSOR_SECRET
  process.env.PLAYMATCH_CURSOR_SECRET = 'test-only-connection-authorization-secret'
  try { await route({ params: { connectionId: row.id }, body: { decision } }, res, error => { throw error }) }
  finally {
    if (before == null) delete process.env.PLAYMATCH_CURSOR_SECRET
    else process.env.PLAYMATCH_CURSOR_SECRET = before
  }
  assert.equal(released, true)
  assert(queries.includes('ROLLBACK'))
  return result
}

test('outsiders cannot decide or replay any connection state', async () => {
  for (const status of ['PENDING', 'ACCEPTED', 'REJECTED', 'CANCELLED']) for (const decision of ['ACCEPT', 'REJECT', 'CANCEL']) {
    const response = await decide('outsider', status, decision)
    assert.equal(response.status, 403, `${status}/${decision}`)
    assert.deepEqual(response.body, { error: 'NOT_ALLOWED' })
  }
})

test('the wrong participant cannot replay the other participants decision', async () => {
  for (const [actor, status, decision] of [['sender', 'ACCEPTED', 'ACCEPT'], ['sender', 'REJECTED', 'REJECT'], ['recipient', 'CANCELLED', 'CANCEL']]) {
    assert.equal((await decide(actor, status, decision)).status, 403)
  }
})

test('only the authorized actor receives an idempotent replay', async () => {
  for (const [actor, status, decision] of [['recipient', 'ACCEPTED', 'ACCEPT'], ['recipient', 'REJECTED', 'REJECT'], ['sender', 'CANCELLED', 'CANCEL']]) {
    const response = await decide(actor, status, decision)
    assert.equal(response.status, 200)
    assert.equal(response.body.connection.id, 'connection')
  }
})
