const assert = require('node:assert/strict')
const { test } = require('node:test')
const { createConversationActivityAuthorizer, markSelectedMessagesRead, registerVisibleMessageReadRoute, selectedMessageIds } = require('../src/conversation-activity-routes')

const id = '11111111-1111-4111-8111-111111111111'
const identity = { ids: new Set(['me', 'my-alias']) }

test('visible receipts reject malformed IDs, bound batches and deduplicate', () => {
  for (const body of [null, {}, { messageIds: [null] }, { messageIds: ['bad'] }, { messageIds: Array(201).fill(id) }]) {
    assert.throws(() => selectedMessageIds(body), { code: 'INVALID_MESSAGE_IDS' })
  }
  assert.deepEqual(selectedMessageIds({ messageIds: [id, id] }), [id])
  assert.deepEqual(selectedMessageIds({ messageIds: [] }), [])
})

test('visible receipts constrain chat, sender and IDs without updating global read cursors', async () => {
  const calls = []
  const pool = { query: async (sql, params) => { calls.push({ sql, params }); return { rows: [{ id }] } } }
  assert.deepEqual(await markSelectedMessagesRead(pool, 'chat', identity, [id]), [id])
  assert.equal(calls.length, 1)
  assert.match(calls[0].sql, /lower\(chat_id\) = \$1/)
  assert.match(calls[0].sql, /NOT \(lower\(sender_id\) = ANY/)
  assert.match(calls[0].sql, /id = ANY\(\$3::uuid\[\]\)/)
  assert.doesNotMatch(calls[0].sql, /last_read_at|fallback_chat_participants/)
  assert.deepEqual(calls[0].params, ['chat', ['me', 'my-alias'], [id]])
  await markSelectedMessagesRead(pool, 'chat', identity, [])
  assert.equal(calls.length, 1)
})

test('visible receipt endpoint rejects another chat before any mutation', async () => {
  let handler
  const app = { post: (_path, _auth, route) => { handler = route } }
  registerVisibleMessageReadRoute(app, {
    pool: { query: () => assert.fail('unauthorized writes') }, authRequired: () => {},
    getCurrentUser: async () => ({ id: 'me' }), userIdentity: () => identity,
    privateChatAccessContext: async () => ({ chat: { id }, hasAccess: false }),
  })
  const res = { status(code) { this.statusCode = code; return this }, json(body) { this.body = body } }
  await handler({ body: { messageIds: [id] }, params: { chatId: id } }, res, assert.fail)
  assert.equal(res.statusCode, 403)
})

test('typing authorization requires current membership, including public communities', async () => {
  let access = { room: { creator_id: 'other' }, roomMembers: [] }
  const authorize = createConversationActivityAuthorizer({
    privateChatAccessContext: async () => ({ hasAccess: false }),
    loadCollectionRoomAccess: async () => access,
  })
  assert.equal(await authorize({}, {}, identity, 'chat', id), false)
  assert.equal(await authorize({}, {}, identity, 'community', id), false)
  access.roomMembers.push({ user_id: 'my-alias', status: 'removed' })
  assert.equal(await authorize({}, {}, identity, 'community', id), false)
  access.roomMembers[0].status = 'joined'
  assert.equal(await authorize({}, {}, identity, 'community', id), true)
  access = { room: { creator_id: 'me' }, roomMembers: [] }
  assert.equal(await authorize({}, {}, identity, 'group', id), true)
})
