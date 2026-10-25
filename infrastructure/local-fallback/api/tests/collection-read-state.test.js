const assert = require('node:assert/strict')
const { test } = require('node:test')
const { isCollectionMessageUnread, projectCollectionReceipt, loadCollectionReceipts } = require('../src/collection-read-state')
const { markSelectedCollectionMessagesRead, registerVisibleCollectionReadRoutes } = require('../src/conversation-activity-routes')

const a = { ids: new Set(['a']), displayUserId: 'a' }
const b = { ids: new Set(['b']), displayUserId: 'b' }
const message = { id: '11111111-1111-4111-8111-111111111111', sender_id: 'sender', created_at: '2026-09-20T10:00:00Z', status: 'read', read_at: '2026-09-20T10:02:00Z' }

test('global read status never marks a group or community message read for another account', () => {
  assert.equal(projectCollectionReceipt(message, b, null, null).status, 'sent')
  assert.equal(projectCollectionReceipt(message, b, null, null).read_at, null)
  assert.equal(isCollectionMessageUnread(message, b, null, null), true)
  assert.equal(projectCollectionReceipt(message, a, null, { read_at: '2026-09-20T10:02:00Z' }).status, 'read')
})

test('read cursor survives a returning account while new and offscreen messages remain unread', () => {
  const readAt = '2026-09-20T10:00:01Z'
  assert.equal(isCollectionMessageUnread(message, a, readAt, null), false)
  assert.equal(isCollectionMessageUnread({ ...message, created_at: '2026-09-20T10:00:02Z' }, a, readAt, null), true)
  assert.equal(isCollectionMessageUnread(message, b, null, null), true)
})

test('own and deleted messages never contribute to unread counters', () => {
  assert.equal(isCollectionMessageUnread({ ...message, sender_id: 'a' }, a, null, null), false)
  assert.equal(isCollectionMessageUnread({ ...message, deleted_for_all: true }, a, null, null), false)
  assert.equal(isCollectionMessageUnread({ ...message, deleted_by: ['a'] }, a, null, null), false)
})

test('receipt lookup is bound to viewer aliases room and kind', async () => {
  const pool = { query: async (sql, params) => {
    assert.match(sql, /lower\(user_id\) = ANY/)
    assert.deepEqual(params, ['community', ['a'], ['room']])
    return { rows: [{ room_id: 'room', message_id: message.id, read_at: message.read_at }] }
  } }
  assert.equal((await loadCollectionReceipts(pool, 'community', a, ['room'])).size, 1)
})

test('paged history only loads receipts for its messages and an empty page does not query', async () => {
  let calls = 0
  const pool = { query: async (sql, params) => {
    calls++
    assert.match(sql, /lower\(message_id::text\) = ANY\(\$4::text\[\]\)/)
    assert.deepEqual(params, ['group', ['a'], ['room'], [message.id]])
    return { rows: [] }
  } }
  await loadCollectionReceipts(pool, 'group', a, ['room'], [message.id])
  await loadCollectionReceipts(pool, 'group', a, ['room'], [])
  assert.equal(calls, 1)
})

test('visible writes are idempotent per reader and never advance the whole conversation cursor', async () => {
  const pool = { query: async (sql, params) => {
    if (sql.includes('to_regclass') || sql.includes('fallback_compat_rows') || sql.includes('fallback_collection_chat_history') || sql.includes('fallback_collection_policies') || sql.includes('fallback_collection_history_grants')) return {rows:[]}
    if (sql.includes('FROM fallback_collection_rooms')) return {rows:[{id:'room',creator_id:'a'}]}
    if (sql.includes('FROM fallback_collection_members')) return {rows:[]}
    if (sql.includes('SELECT id::text,created_at FROM fallback_room_messages')) return {rows:[message]}
    assert.match(sql, /ON CONFLICT \(room_kind, message_id, user_id\)/)
    assert.match(sql, /id = ANY\(\$4::uuid\[\]\)/)
    assert.doesNotMatch(sql, /last_read_at|UPDATE fallback_room_messages/)
    assert.deepEqual(params, ['group', 'room', 'a', [message.id], ['a']])
    return { rows: [{ id: message.id }] }
  } }
  assert.deepEqual(await markSelectedCollectionMessagesRead(pool, 'group', 'room', a, [message.id]), [message.id])
})

test('public room visibility does not authorize receipts for nonmembers', async () => {
  const handlers = []
  registerVisibleCollectionReadRoutes({ post: (path, auth, handler) => handlers.push(handler) }, {
    pool: { query: () => assert.fail('unauthorized mutation') }, authRequired: () => {},
    getCurrentUser: async () => ({}), userIdentity: () => a,
    loadCollectionRoomAccess: async () => ({ room: { creator_id: 'other' }, roomMembers: [] }),
  })
  for (const handler of handlers) {
    const res = { status(code) { this.code = code; return this }, json() {} }
    await handler({ params: { roomId: message.id }, body: { messageIds: [message.id] } }, res, assert.fail)
    assert.equal(res.code, 403)
  }
})
