'use strict'
// Manual integration check; refuses every endpoint except its disposable audit fixture.
const assert = require('node:assert/strict')
const { randomUUID, randomBytes } = require('node:crypto')
const { Pool } = require('pg')
const base = new URL(process.env.COLLECTION_TEST_API_URL || 'http://invalid/')
const database = new URL(process.env.COLLECTION_TEST_DATABASE_URL || 'postgresql://invalid/invalid')
assert.equal(base.origin, 'http://novvin-audit101-collections-api:3001')
assert.equal(database.hostname, 'novvin-audit101-collections-db')
assert.equal(database.pathname, '/audit101_isolated_collections')
const pool = new Pool({ connectionString: database.toString(), max: 2 })
let requests = 0
async function request(path, { token, method = 'GET', body, status = 200, ignoreResponse = false } = {}) {
  requests++
  const response = await fetch(new URL(path, base), { method, signal: AbortSignal.timeout(20_000),
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
  if (ignoreResponse) {
    assert.equal(response.status, status)
    await response.body.cancel() // The client never receives the committed creation body.
    return null
  }
  const json = await response.json()
  assert.equal(response.status, status, `${method} ${path}: ${response.status}; ${json.error || 'unexpected response'}`)
  return json
}
async function register(label) {
  const result = await request('/v1/auth/register', { method: 'POST', status: 201, body: {
    email: `audit101-collections-${label}-${randomUUID()}@example.invalid`, password: randomBytes(20).toString('hex'),
    displayName: `QA Collections ${label}`, username: `qa101${randomBytes(5).toString('hex')}` } })
  const token = result.accessToken || result.access_token || result.token
  assert.equal(typeof token, 'string')
  assert.match(result.user.id, /^[0-9a-f-]{36}$/i)
  return { token, user: result.user }
}
async function members(id) {
  return (await pool.query('SELECT user_id,role FROM fallback_collection_members WHERE room_id=$1 ORDER BY user_id', [id])).rows
}
async function withMemberFailure(roomId, userId, operation) {
  assert.match(roomId, /^[0-9a-f-]{36}$/i); assert.match(userId, /^[0-9a-f-]{36}$/i)
  await pool.query(`CREATE FUNCTION audit101_reject_member() RETURNS trigger LANGUAGE plpgsql AS $$
    BEGIN IF NEW.room_id::text='${roomId}' AND NEW.user_id='${userId}' THEN
      RAISE EXCEPTION 'isolated membership failure'; END IF; RETURN NEW; END $$`)
  await pool.query(`CREATE TRIGGER audit101_member_failure BEFORE INSERT ON fallback_collection_members
    FOR EACH ROW EXECUTE FUNCTION audit101_reject_member()`)
  try { return await operation() }
  finally {
    await pool.query('DROP TRIGGER audit101_member_failure ON fallback_collection_members')
    await pool.query('DROP FUNCTION audit101_reject_member()')
  }
}
async function main() {
  const started = Date.now()
  try {
    assert.equal((await pool.query('SELECT COUNT(*)::int AS n FROM fallback_users')).rows[0].n, 0,
      'Only a fresh disposable database may be used')
    await request('/v1/data/messages/groups', { method: 'POST', body: { name: 'Denied' }, status: 401 })
    const a = await register('a'), b = await register('b'), c = await register('c')
    const id = randomUUID()
    const original = { id, name: 'Original group', description: 'Frozen payload', memberIds: [b.user.id], createOnly: true }
    await request('/v1/data/messages/groups', { method: 'POST', token: a.token, body: original, status: 201, ignoreResponse: true })
    const retries = await Promise.all(Array.from({ length: 20 }, () => request('/v1/data/messages/groups',
      { method: 'POST', token: a.token, body: original, status: 201 })))
    assert.ok(retries.every(result => result.id === id))
    assert.ok(retries.every(result => result.created === false && result.initialMembers.length === 2))
    assert.equal((await pool.query('SELECT COUNT(*)::int AS n FROM fallback_collection_rooms WHERE id=$1', [id])).rows[0].n, 1)
    assert.deepEqual(await members(id), [
      { user_id: a.user.id, role: 'owner' }, { user_id: b.user.id, role: 'member' },
    ].sort((left, right) => left.user_id.localeCompare(right.user_id)))
    await request('/v1/data/messages/groups/' + id, { token: b.token })
    await request('/v1/data/messages/groups/' + id, { token: c.token, status: 404 })
    await request('/v1/data/messages/groups', { method: 'POST', token: c.token, body: original, status: 409 })
    await request('/v1/data/messages/communities', { method: 'POST', token: a.token, body: original, status: 409 })

    const invalid = randomUUID()
    await request('/v1/data/messages/groups', { method: 'POST', token: a.token, body: { id: invalid, name: ' ' }, status: 400 })
    assert.equal((await pool.query('SELECT COUNT(*)::int AS n FROM fallback_collection_rooms WHERE id=$1', [invalid])).rows[0].n, 0)
    const failedId = randomUUID()
    const interrupted = { ...original, id: failedId, name: 'Interrupted creation' }
    await withMemberFailure(failedId, b.user.id, async () => {
      await request('/v1/data/messages/groups', { method: 'POST', token: a.token, body: interrupted, status: 500 })
      assert.equal((await pool.query('SELECT COUNT(*)::int AS n FROM fallback_collection_rooms WHERE id=$1', [failedId])).rows[0].n, 0)
      assert.deepEqual(await members(failedId), [])
    })
    assert.equal((await request('/v1/data/messages/groups', { method: 'POST', token: a.token, body: interrupted, status: 201 })).id, failedId)
    assert.equal((await members(failedId)).length, 2)
    await withMemberFailure(id, b.user.id, async () => {
      await request('/v1/data/messages/groups', { method: 'POST', token: a.token, body: { ...original, createOnly: false, name: 'Must roll back' }, status: 500 })
      assert.equal((await pool.query('SELECT name FROM fallback_collection_rooms WHERE id=$1', [id])).rows[0].name, original.name)
      assert.equal((await members(id)).length, 2)
    })

    const publicId = randomUUID()
    await pool.query('UPDATE fallback_users SET supabase_user_id=$2::uuid WHERE id=$1::uuid', [a.user.id, publicId])
    assert.equal((await request('/v1/data/messages/groups', { method: 'POST', token: a.token, body: original, status: 201 })).id, id)
    assert.equal((await members(id)).length, 2, 'A migrated alias must not add a second owner membership')
    assert.equal((await members(id)).filter(member => member.role === 'owner').length, 1)
    await pool.query('UPDATE fallback_collection_rooms SET name=$2 WHERE id=$1', [id, 'Edited after creation'])
    await pool.query('DELETE FROM fallback_collection_members WHERE room_id=$1 AND user_id=$2', [id, b.user.id])
    const recovered = await request('/v1/data/messages/groups', { method: 'POST', token: a.token, body: original, status: 201 })
    assert.equal(recovered.room.name, 'Edited after creation')
    assert.equal(recovered.initialMembers.length, 1)
    assert.equal((await members(id)).length, 1, 'Old retries must never re-invite removed members')

    const communityId = randomUUID()
    const community = { id: communityId, name: 'Private community', description: 'Private original', category: 'QA', isPublic: false, createOnly: true }
    await request('/v1/data/messages/communities', { method: 'POST', token: a.token, body: community, status: 201, ignoreResponse: true })
    assert.equal((await request('/v1/data/messages/communities', { method: 'POST', token: a.token, body: community, status: 201 })).id, communityId)
    const row = (await pool.query('SELECT is_public, creator_id FROM fallback_collection_rooms WHERE id=$1', [communityId])).rows[0]
    assert.equal(row.is_public, false); assert.equal(row.creator_id, publicId)
    assert.equal((await members(communityId)).length, 1)
    await request('/v1/data/messages/communities/' + communityId, { token: c.token, status: 404 })
    await pool.query('UPDATE fallback_collection_rooms SET is_public=TRUE WHERE id=$1', [communityId])
    const recoveredCommunity = await request('/v1/data/messages/communities', { method: 'POST', token: a.token, body: community, status: 201 })
    assert.equal(recoveredCommunity.created, false)
    assert.equal(recoveredCommunity.room.is_public, true, 'Recovery must preserve a later privacy change')
    await pool.query('UPDATE fallback_collection_rooms SET is_deleted=TRUE WHERE id=$1', [communityId])
    await request('/v1/data/messages/communities', { method: 'POST', token: a.token, body: community, status: 409 })
    assert.equal((await pool.query('SELECT is_deleted FROM fallback_collection_rooms WHERE id=$1', [communityId])).rows[0].is_deleted, true)
    return { ok: true, scope: 'isolated-api-and-postgres', productionDataTouched: false, requests, accounts: 3,
      lostAcknowledgementRecovered: true, concurrentRetries: 20, singleRoomAndMemberships: true,
      wholeCreationRollback: true, failedReplayPreservesPreviousData: true, migratedOwnerIsNotDuplicated: true,
      privateCommunityPreserved: true, foreignAndKindConflictsDenied: true, deletedRoomNotResurrected: true,
      recoveryPreservesLaterEdits: true, recoveryDoesNotReinviteRemovedMembers: true,
      elapsedMillis: Date.now() - started }
  } finally { await pool.end() }
}
main().then(report => console.log(JSON.stringify(report))).catch(error => {
  console.error(JSON.stringify({ ok: false, code: error.code || error.name, message: error.message,
    location: error.stack?.split('\n')[1]?.trim() })); process.exitCode = 1
})
