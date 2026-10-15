const test = require('node:test')
const assert = require('node:assert/strict')
const { publishAutomaticFriendRequest, ensureDirectMessageRequestPolicy } = require('../src/direct-message-request-policy')

test('first-message guard binds the durable introduction request to the exact message', async () => {
  const statements = []
  await ensureDirectMessageRequestPolicy({ query: async (sql) => { statements.push(sql); return { rows: [] } } })
  assert.ok(statements.some((sql) => sql.includes('fallback_message_requests') && sql.includes("'pending',NEW.id::text")))
  assert.ok(!statements.some((sql) => sql.includes('INSERT INTO fallback_friends')))
})

test('relay publishes the existing notification to its recipient without a second insert', async () => {
  const notification = { id: 'n', recipient_id: 'recipient', actor_id: 'sender', type: 'friend_request', created_at: new Date('2026-09-29T12:00:00Z'), extra_data: { message_id: 'message', chat_id: 'chat' } }
  const calls = []
  const sent = []
  const count = await publishAutomaticFriendRequest({ query: async (sql, args) => {
    calls.push([sql, args]); return { rows: [notification] }
  } }, { senderId: 'sender', chatId: 'chat', messageId: 'message' }, async (channel, payload) => sent.push([channel, payload]))
  assert.equal(count, 1)
  assert.deepEqual(calls[0][1], ['sender', 'chat', 'message'])
  assert.match(calls[0][0], /extra_data->>'message_id' = \$3/)
  assert.doesNotMatch(calls[0][0], /INSERT/i)
  assert.equal(sent[0][0], 'user:recipient')
  assert.equal(sent[0][1].event, 'notification')
  assert.equal(sent[0][1].notification.id, 'n')
  assert.equal(sent[0][1].notification.created_at, '2026-09-29T12:00:00.000Z')
})

test('ordinary messages and incomplete identifiers do not relay old requests', async () => {
  let queries = 0
  let published = 0
  const pool = { query: async () => { queries++; return { rows: [] } } }
  const send = async () => published++
  assert.equal(await publishAutomaticFriendRequest(pool, { senderId: 's', chatId: 'c', messageId: 'm' }, send), 0)
  assert.equal(await publishAutomaticFriendRequest(pool, { senderId: 's', chatId: 'c' }, send), 0)
  assert.equal(queries, 1)
  assert.equal(published, 0)
})
