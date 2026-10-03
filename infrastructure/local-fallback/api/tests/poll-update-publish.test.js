const assert = require('node:assert/strict')
const test = require('node:test')
const { publishPollUpdate } = require('../src/poll-update-publish')

test('a committed group vote invalidates only members and exposes no voter state', async () => {
  const emitted = []
  const pool = { async query(sql, args) {
    assert.match(sql, /room_kind = \$1 AND lower\(room_id\) = \$2/)
    assert.deepEqual(args, ['group', 'room'])
    return { rows: [{ user_id: 'owner' }, { user_id: 'peer' }, { user_id: 'peer' }] }
  } }
  assert.equal(await publishPollUpdate(pool, { id: 'poll', scope_type: 'group', scope_id: 'room' },
    async (channel, payload) => { emitted.push({ channel, payload }); return true }), true)
  assert.deepEqual(emitted.map(x => x.channel), ['user:owner', 'user:peer'])
  assert.deepEqual(emitted[0].payload, { event: 'poll_updated', poll_id: 'poll', scope_type: 'group', scope_id: 'room' })
})

test('direct polls target participants, and unsupported scopes publish nothing', async () => {
  let count = 0
  const pool = { async query(sql, args) {
    assert.match(sql, /FROM fallback_chat_participants/)
    assert.deepEqual(args, ['chat'])
    count++
    return { rows: [{ user_id: 'peer' }] }
  } }
  const publish = async (channel) => { assert.equal(channel, 'user:peer'); return true }
  assert.equal(await publishPollUpdate(pool, { id: 'poll', scope_type: 'direct', scope_id: 'chat' }, publish), true)
  assert.equal(await publishPollUpdate(pool, { id: 'poll', scope_type: 'public', scope_id: 'chat' }, publish), false)
  assert.equal(count, 1)
})

test('gateway failure is recoverable through client reconciliation and fan-out is bounded', async () => {
  let active = 0, maximum = 0, sent = 0
  const pool = { async query() { return { rows: Array.from({ length: 37 }, (_, i) => ({ user_id: `member${i}` })) } } }
  const publish = async () => {
    active++; maximum = Math.max(active, maximum)
    await new Promise(resolve => setImmediate(resolve))
    active--; sent++; return false
  }
  assert.equal(await publishPollUpdate(pool, { id: 'poll', scope_type: 'community', scope_id: 'room' }, publish), false)
  assert.equal(sent, 37)
  assert.equal(maximum, 16)
})
