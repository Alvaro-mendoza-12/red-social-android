const assert = require('node:assert/strict')
const { test } = require('node:test')
const { createMessageReadReceiptPublisher } = require('../src/message-read-receipts')

test('read publication sends complete normalized messages only to authorized recipients', async () => {
  const queries = [], events = []
  const row = { id: 'message', chat_id: 'chat', status: 'read', media_url: 'https://example.test/voice.m4a', reply_to_id: 'reply' }
  const chat = {}, participantRows = [], identity = {}
  const publish = createMessageReadReceiptPublisher({
    pool: { query: async (sql, params) => { queries.push({ sql, params }); return { rows: [row] } } },
    normalizeMessageRow: (message) => ({ ...message, normalized: true }),
    recipientIdsForChat: (actualChat, actualParticipants, actualIdentity) => {
      assert.equal(actualChat, chat); assert.equal(actualParticipants, participantRows); assert.equal(actualIdentity, identity)
      return ['sender', 'reader']
    },
    publishChatMessageEvent: async (event) => { events.push(event) },
  })
  await publish({ chatId: 'chat', messageIds: ['message'], chat, participantRows, identity })
  assert.deepEqual(queries[0].params, ['chat', ['message']])
  assert.match(queries[0].sql, /lower\(chat_id\) = \$1 AND id = ANY\(\$2::uuid\[\]\)/)
  assert.match(queries[0].sql, /status = 'read' AND deleted_for_all = FALSE/)
  assert.deepEqual(events, [{ chatId: 'chat', message: { ...row, normalized: true }, recipientIds: ['sender', 'reader'], action: 'updated' }])
})

test('no mutation result means no lookup or publication', async () => {
  const publish = createMessageReadReceiptPublisher({ pool: { query: () => assert.fail('query') } })
  await publish({ chatId: 'chat', messageIds: [] })
})

test('a concurrently deleted message does not publish a stale receipt', async () => {
  const publish = createMessageReadReceiptPublisher({
    pool: { query: async () => ({ rows: [] }) },
    recipientIdsForChat: () => ['sender'],
    publishChatMessageEvent: () => assert.fail('deleted message'),
  })
  await publish({ chatId: 'chat', messageIds: ['message'] })
})
