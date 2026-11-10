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
  await db.query("INSERT INTO fallback_users(id, supabase_user_id) VALUES ('receiver-local','receiver'),('sender-local','sender'),('stranger','stranger')")
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
  const removeRequest = async (id, actor = 'receiver-local') => {
    const res = { statusCode: 200, status(code) { this.statusCode = code; return this }, json(body) { this.body = body; return this } }
    await handlers.get('delete /v1/data/friends/:requestId')({ auth: { sub: actor }, params: { requestId: id } }, res, error => { throw error })
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
    for (const actor of final === 'accepted' ? ['receiver-local','sender-local'] : []) {
      const cancelled = await removeRequest(id,actor)
      assert.equal(cancelled.statusCode,409,'a delayed rejection/cancellation cannot remove a resolved relationship')
      assert.equal(await read(id),final)
    }
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
  for (const actor of ['receiver-local','sender-local']) for (const status of ['pending','rejected']) {
    const id = `cancel-${actor}-${status}`
    await insert(id)
    if (status === 'rejected') await send(id,'rejected')
    assert.equal((await removeRequest(id,'stranger')).statusCode,404)
    assert.equal((await removeRequest(id,actor)).statusCode,200)
    assert.equal((await db.query('SELECT id FROM fallback_friends WHERE id=$1',[id])).rows.length,0)
  }
  await insert('accept-vs-cancel')
  const [accept,cancel]=await Promise.all([send('accept-vs-cancel','accepted'),removeRequest('accept-vs-cancel','sender-local')])
  const remaining=(await db.query("SELECT status FROM fallback_friends WHERE id='accept-vs-cancel'")).rows
  if (accept.statusCode === 200) {
    assert.equal(cancel.statusCode,409)
    assert.equal(remaining[0].status,'accepted')
  } else {
    assert.equal(accept.statusCode,404)
    assert.equal(cancel.statusCode,200)
    assert.equal(remaining.length,0)
  }
  // Reads accept local and migrated IDs. Mutations must recognize the same
  // authenticated identity rather than requiring only its preferred alias.
  for (const final of ['accepted','rejected']) {
    const id = `local-receiver-${final}`
    await db.query("INSERT INTO fallback_friends(id,sender_id,receiver_id,status) VALUES($1,'sender-local','receiver-local','pending')",[id])
    assert.equal((await send(id,final,'stranger')).statusCode,404)
    assert.equal((await send(id,final,'sender-local')).statusCode,404,'a sender cannot answer its own outgoing request')
    assert.equal((await send(id,final)).statusCode,200,'local receiver IDs must be actionable')
    assert.equal((await send(id,final)).statusCode,200,'alias-aware retry must remain idempotent')
    assert.equal((await send(id,final==='accepted'?'rejected':'accepted')).statusCode,409)
  }
  for (const actor of ['receiver-local','sender-local']) {
    const id = `local-cancel-${actor}`
    await db.query("INSERT INTO fallback_friends(id,sender_id,receiver_id,status) VALUES($1,'sender-local','receiver-local','pending')",[id])
    assert.equal((await removeRequest(id,'stranger')).statusCode,404)
    assert.equal((await removeRequest(id,actor)).statusCode,200,'either participant can cancel using its local ID')
    assert.equal((await removeRequest(id,actor)).statusCode,404)
  }
  assert.equal((await removeRequest('local-receiver-accepted','stranger')).statusCode,404,'resolved alias lookup remains private')
  assert.equal((await removeRequest('local-receiver-accepted')).statusCode,409,'alias-aware lookup must still protect accepted friendships')
  // Removing a confirmed friend is an explicit different action and remains supported.
  const unfriend={ statusCode:200,status(code){this.statusCode=code;return this},json(body){this.body=body;return this} }
  await handlers.get('delete /v1/data/friends/users/:targetUserId')({auth:{sub:'receiver-local'},params:{targetUserId:'sender'}},unfriend,error=>{throw error})
  assert.equal(unfriend.statusCode,200)
  assert.ok(unfriend.body.deleted>0)
  const removeFriend = async (targetAlias,actor='receiver-local') => {
    const result={statusCode:200,status(code){this.statusCode=code;return this},json(body){this.body=body;return this}}
    await handlers.get('delete /v1/data/friends/users/:targetUserId')({auth:{sub:actor},params:{targetUserId:targetAlias}},result,error=>{throw error})
    return result
  }
  for (const targetAlias of ['sender','sender-local']) for (const reverse of [false,true]) {
    const id = `unfriend-local-${targetAlias}-${reverse}`
    const participants=reverse?['receiver-local','sender-local']:['sender-local','receiver-local']
    await db.query("INSERT INTO fallback_friends(id,sender_id,receiver_id,status) VALUES($1,$2,$3,'accepted')",[id,...participants])
    assert.equal((await removeFriend(targetAlias,'stranger')).body.deleted,0,'unrelated actor cannot unfriend another pair')
    const result=await removeFriend(targetAlias)
    assert.equal(result.statusCode,200)
    assert.ok(result.body.deleted>0,'explicit unfriend must resolve both participants verified aliases')
    assert.equal((await db.query('SELECT id FROM fallback_friends WHERE id=$1',[id])).rows.length,0)
    assert.equal((await removeFriend(targetAlias)).body.deleted,0,'repeat stays idempotent')
  }
  await db.query("INSERT INTO fallback_friends(id,sender_id,receiver_id,status) VALUES('orphan-target','receiver-local','historical-user','accepted')")
  assert.equal((await removeFriend('unknown-user')).body.deleted,0)
  assert.equal((await removeFriend('historical-user')).body.deleted,1,'exact historical target without account row remains removable')
  await db.query("INSERT INTO fallback_users(id,supabase_user_id) VALUES('sender','other-account')")
  await db.query("INSERT INTO fallback_friends(id,sender_id,receiver_id,status) VALUES('ambiguous-target','receiver-local','sender-local','accepted')")
  const ambiguous=await removeFriend('sender')
  assert.equal(ambiguous.statusCode,409,'ambiguous persisted alias must fail closed')
  assert.equal(ambiguous.body.error,'FRIEND_TARGET_AMBIGUOUS')
  assert.equal((await db.query("SELECT id FROM fallback_friends WHERE id='ambiguous-target'")).rows.length,1)
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
