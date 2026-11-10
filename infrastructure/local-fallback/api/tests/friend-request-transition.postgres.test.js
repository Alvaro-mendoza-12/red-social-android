'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { Pool } = require('pg')
const { PGlite } = require('@electric-sql/pglite')
const { registerDataRoutes } = require('../src/data-routes')

const url = process.env.FRIEND_REQUEST_TEST_DATABASE_URL
if (url && (!['localhost', '127.0.0.1'].includes(new URL(url).hostname) || new URL(url).pathname !== '/novvin_friend_request_test')) {
  throw new Error('Requires the isolated loopback novvin_friend_request_test database')
}

// Exercise the actual route and SQL, not a fake implementing the desired state
// machine. Also callable with an isolated in-memory PostgreSQL engine locally.
async function verifyFriendRequestTransitions(db) {
  await db.query(`CREATE TEMP TABLE fallback_users (
    id text PRIMARY KEY, email text, display_name text, avatar_url text,
    supabase_user_id text, sync_source text, sync_status text,
    created_at timestamptz, updated_at timestamptz, last_login_at timestamptz)`)
  await db.query(`CREATE TEMP TABLE fallback_friends (
    id text PRIMARY KEY, sender_id text, receiver_id text, status text,
    created_at timestamptz DEFAULT now())`)
  await db.query("INSERT INTO fallback_users(id, supabase_user_id) VALUES ('receiver-local','receiver'),('stranger','stranger')")
  const handlers = new Map()
  const app = Object.fromEntries(['get','post','patch','delete','put','use'].map(method =>
    [method, (path, ...chain) => handlers.set(`${method} ${path}`, chain.at(-1))]))
  registerDataRoutes(app, { pool: db, authRequired() {} })
  const handler = handlers.get('patch /v1/data/friends/:requestId')
  const send = async (id, status, actor = 'receiver-local') => {
    const res = { statusCode: 200, status(code) { this.statusCode = code; return this }, json(body) { this.body = body; return this } }
    await handler({ auth: { sub: actor }, params: { requestId: id }, body: { status } }, res, error => { throw error })
    return res
  }
  const insert = id => db.query("INSERT INTO fallback_friends(id,sender_id,receiver_id,status) VALUES($1,'sender','receiver','pending')",[id])
  const read = async id => (await db.query('SELECT status FROM fallback_friends WHERE id=$1',[id])).rows[0].status

  for (const final of ['accepted','rejected']) {
    const id = `sequential-${final}`
    await insert(id)
    const first = await send(id, final)
    assert.equal(first.statusCode, 200)
    assert.equal(first.body.request.status, final)
    const revision = (await db.query('SELECT xmin::text AS revision FROM fallback_friends WHERE id=$1',[id])).rows[0].revision
    const retry = await send(id, final)
    assert.equal(retry.statusCode, 200, 'same-decision retries must remain compatible')
    assert.equal(retry.body.request.status, final)
    assert.equal((await db.query('SELECT xmin::text AS revision FROM fallback_friends WHERE id=$1',[id])).rows[0].revision, revision, 'an idempotent retry must not rewrite the row')
    const contrary = await send(id, final === 'accepted' ? 'rejected' : 'accepted')
    assert.equal(contrary.statusCode, 409, 'a late contradictory decision must not overwrite the first')
    assert.equal(contrary.body.error, 'FRIEND_REQUEST_ALREADY_RESOLVED')
    assert.equal(await read(id), final)
    const foreign = await send(id, final, 'stranger')
    assert.equal(foreign.statusCode, 404, 'the retry SELECT must not reveal resolved requests to another account')
    assert.deepEqual(foreign.body, { error: 'REQUEST_NOT_FOUND' })
  }

  await insert('parallel')
  const simultaneous = await Promise.all([send('parallel','accepted'),send('parallel','rejected')])
  assert.deepEqual(simultaneous.map(result => result.statusCode).sort(),[200,409])
  assert.equal(await read('parallel'), simultaneous.find(result => result.statusCode === 200).body.request.status)

  await insert('private')
  assert.equal((await send('private','accepted','stranger')).statusCode,404)
  assert.equal((await send('missing','accepted')).statusCode,404)
  assert.equal((await send('private','pending')).statusCode,400)
  assert.equal(await read('private'),'pending')
  await db.query('DROP TABLE fallback_friends')
  await db.query('DROP TABLE fallback_users')
}

test('friend request decisions are one-way, retryable and receiver-authorized in PostgreSQL', async () => {
  const db = url ? new Pool({ connectionString: url, max: 1 }) : new PGlite()
  try { await verifyFriendRequestTransitions(db) } finally {
    if (url) await db.end()
    else await db.close()
  }
})
module.exports = { verifyFriendRequestTransitions }
