const test = require('node:test')
const assert = require('node:assert/strict')
const { insertWithCommunityBudget } = require('../src/community-message-budget')
const input = () => ({ kind: 'community', scopeId: 'room', actorIds: new Set(['member']), message: {
  id: '12345678-1234-1234-1234-123456789012', sender_id: 'member', content: 'hola', content_type: 'text',
} })
function store({ weekly = 0, daily = 0, duplicate = false, failure = false } = {}) {
  const calls = []
  const db = { release() { calls.push('RELEASE') }, async query(sql) {
    calls.push(sql)
    if (sql.includes('SELECT id FROM')) return { rows: duplicate ? [{ id: input().message.id }] : [] }
    if (sql.includes('weekly_used')) return { rows: [{ weekly_used: weekly, daily_used: daily }] }
    if (sql.includes('INSERT INTO')) {
      if (failure) throw new Error('storage unavailable')
      return { rows: [{ ...input().message }] }
    }
    return { rows: [] }
  } }
  return { db, calls, pool: { connect: async () => db, query: db.query } }
}
test('weekly quota uses a transaction and a per-member lock before counting and inserting', async () => {
  const { pool, calls } = store({ weekly: 9 })
  await insertWithCommunityBudget(pool, input(), { weeklyLimit: 10 })
  assert.ok(calls.findIndex(sql => sql.includes('advisory')) < calls.findIndex(sql => sql.includes('weekly_used')))
  assert.ok(calls.findIndex(sql => sql.includes('weekly_used')) < calls.findIndex(sql => sql.includes('INSERT INTO')))
  assert.equal(calls.at(-2), 'COMMIT'); assert.equal(calls.at(-1), 'RELEASE')
})
test('the next message beyond the weekly quota is rejected and rolled back', async () => {
  const { pool, calls } = store({ weekly: 10 })
  await assert.rejects(insertWithCommunityBudget(pool, input(), { weeklyLimit: 10 }), { code: 'COMMUNITY_WEEKLY_MESSAGE_LIMIT_REACHED', status: 429 })
  assert.equal(calls.some(sql => sql.includes('INSERT INTO')), false)
  assert.equal(calls.at(-2), 'ROLLBACK')
})
test('a retried ID does not consume another slot, including at the limit', async () => {
  const { pool, calls } = store({ weekly: 10, duplicate: true })
  await insertWithCommunityBudget(pool, input(), { weeklyLimit: 10 })
  assert.equal(calls.some(sql => sql.includes('weekly_used')), false)
})
test('weekly configuration supersedes the legacy daily quota', async () => {
  const { pool } = store({ weekly: 2, daily: 2 })
  await insertWithCommunityBudget(pool, input(), { weeklyLimit: 10, dailyLimit: 1 })
})
test('legacy daily quotas stay effective for communities that have not changed policy', async () => {
  const { pool } = store({ daily: 2 })
  await assert.rejects(insertWithCommunityBudget(pool, input(), { dailyLimit: 2 }), { code: 'COMMUNITY_DAILY_MESSAGE_LIMIT_REACHED' })
})
test('poll transactions retain responsibility for committing and releasing', async () => {
  const { db, calls } = store()
  await insertWithCommunityBudget(db, input(), { weeklyLimit: 10, inTransaction: true })
  assert.equal(calls.some(sql => ['BEGIN', 'COMMIT', 'ROLLBACK', 'RELEASE'].includes(sql)), false)
})
test('admins and groups are exempt from the community budget', async () => {
  for (const [value, policy] of [[input(), { isAdmin: true }], [{ ...input(), kind: 'group' }, {}]]) {
    const { pool, calls } = store({ weekly: 999 })
    await insertWithCommunityBudget(pool, value, { weeklyLimit: 10, ...policy })
    assert.equal(calls.length, 1)
  }
})
test('failed storage never commits or acknowledges a message', async () => {
  const { pool, calls } = store({ failure: true })
  await assert.rejects(insertWithCommunityBudget(pool, input(), { weeklyLimit: 10 }), /storage unavailable/)
  assert.equal(calls.at(-2), 'ROLLBACK')
})
