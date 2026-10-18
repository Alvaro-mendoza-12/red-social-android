const { test } = require('node:test')
const assert = require('node:assert/strict')
const { leaveLiveGuest } = require('../src/live-guest-exit')
test('explicit exit cancels only the actor invitations and releases its guest seat atomically', async () => {
  const calls = [], invitation = { id: 'invitation', status: 'canceled' }
  let released = false
  const client = { async query(sql, args) { calls.push({ sql, args }); return { rows: sql.includes('RETURNING id::text') ? [invitation] : sql.includes('RETURNING user_id') ? [{ user_id: 'actor', role: 'viewer' }] : [] } }, release() { released = true } }
  const result = await leaveLiveGuest({ connect: async () => client }, 'live', { values: ['actor', 'alias'] })
  assert.deepEqual(result.invitations, [invitation])
  assert.equal(result.participant.role, 'viewer')
  assert.equal(calls[0].sql, 'BEGIN')
  assert.equal(calls.at(-1).sql, 'COMMIT')
  for (const call of calls.filter(item => item.sql.startsWith('UPDATE'))) assert.deepEqual(call.args, ['live', ['actor', 'alias']])
  assert.match(calls[2].sql, /invitee_user_id/)
  assert.match(calls[3].sql, /AND role='guest'/)
  assert.match(calls[4].sql, /opponent_live_id IS NULL/)
  assert(released)
})
test('a failed departure rolls back and always releases the database connection', async () => {
  let rolledBack = false, released = false
  const client = { async query(sql) { if (sql.startsWith('UPDATE')) throw new Error('failure'); if (sql === 'ROLLBACK') rolledBack = true; return { rows: [] } }, release() { released = true } }
  await assert.rejects(leaveLiveGuest({ connect: async () => client }, 'live', { values: ['actor'] }))
  assert(rolledBack); assert(released)
})
