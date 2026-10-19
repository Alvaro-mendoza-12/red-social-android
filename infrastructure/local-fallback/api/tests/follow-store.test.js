const test = require('node:test')
const assert = require('node:assert/strict')
const { setFollowState, visibleFollowRows } = require('../src/follow-store')
const actor = { id: 'local-a', supabase_user_id: 'public-a' }
const target = { id: 'local-b', supabase_user_id: 'public-b' }
function database(failInsert = false) {
  const calls = []
  const db = { release() { calls.push({ sql: 'RELEASE' }) }, async query(sql, args) {
    calls.push({ sql, args })
    if (failInsert && sql.startsWith('INSERT INTO fallback_follows')) throw new Error('write failed')
    return { rows: [] }
  } }
  return { calls, pool: { connect: async () => db } }
}
test('a follow writes one canonical edge and overrides both accounts aliases atomically', async () => {
  const { calls, pool } = database()
  const result = await setFollowState(pool, actor, target, true)
  const states = calls.filter(call => call.sql.includes('INSERT INTO fallback_follow_overrides'))
  assert.equal(states.length, 4)
  assert.deepEqual(states.filter(call => call.args[2]).map(call => call.args.slice(0, 2)), [['public-a', 'public-b']])
  assert.equal(result.followerId, 'public-a')
  assert.equal(calls.at(-2).sql, 'COMMIT')
})
test('unfollow tombstones every alias so an old imported edge cannot reappear', async () => {
  const { calls, pool } = database()
  await setFollowState(pool, actor, target, false)
  assert.ok(calls.filter(call => call.sql.includes('INSERT INTO fallback_follow_overrides')).every(call => call.args[2] === false))
  assert.equal(calls.some(call => call.sql.startsWith('INSERT INTO fallback_follows')), false)
})
test('failed follow storage rolls back instead of acknowledging an optimistic counter', async () => {
  const { calls, pool } = database(true)
  await assert.rejects(setFollowState(pool, actor, target, true), /write failed/)
  assert.equal(calls.at(-2).sql, 'ROLLBACK')
})
test('self-follow through another identity alias is rejected before storage', async () => {
  await assert.rejects(setFollowState({}, actor, { id: 'public-a' }, true), { status: 400 })
})
test('removed imports are hidden while active canonical follows remain readable', async () => {
  const pool = { query: async () => ({ rows: [
    { follower_id: 'local-a', following_id: 'public-b', active: false },
    { follower_id: 'public-a', following_id: 'public-b', active: true },
  ] }) }
  const rows = [{ follower_id: 'local-a', following_id: 'public-b' }, { follower_id: 'public-a', following_id: 'public-b' }]
  assert.deepEqual(await visibleFollowRows(pool, rows), [rows[1]])
})
