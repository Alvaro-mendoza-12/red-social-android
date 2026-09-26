const assert = require('node:assert/strict')
const { test } = require('node:test')
const { insertMessageOnce } = require('../src/message-insert-store')

const input = {
  kind: 'dm', scopeId: 'chat', actorIds: new Set(['owner']),
  message: { id: '11111111-1111-4111-8111-111111111111', sender_id: 'owner',
    content: 'original', content_type: 'text', media_size: 0, is_forwarded: false },
}

test('a newly inserted message is returned without a duplicate lookup', async () => {
  let queries = 0
  const row = { ...input.message, chat_id: 'chat', status: 'sent' }
  const result = await insertMessageOnce({ query: async () => {
    queries++
    return { rows: [row] }
  } }, input)
  assert.deepEqual(result, { message: row, inserted: true })
  assert.equal(queries, 1)
})

test('a duplicate returns its current edit, read receipt and deletion, not the retry payload', async () => {
  const row = { ...input.message, content: 'edited', is_edited: true, status: 'read',
    read_at: '2026-09-10T08:00:00Z', deleted_for_all: true, deleted_by: ['owner'] }
  let queries = 0
  const result = await insertMessageOnce({ query: async () => ({ rows: ++queries === 1 ? [] : [row] }) }, input)
  assert.deepEqual(result, { message: row, inserted: false })
  assert.equal(queries, 2)
})

test('a duplicate not owned in the requested conversation fails with a generic conflict', async () => {
  await assert.rejects(insertMessageOnce({ query: async () => ({ rows: [] }) }, input),
    { message: 'MESSAGE_ID_CONFLICT', status: 409 })
})

test('an unknown scope or forged sender cannot reach storage', async () => {
  const pool = { query: async () => assert.fail('Unauthorized storage access') }
  await assert.rejects(insertMessageOnce(pool, { ...input, kind: 'arbitrary_table' }), /INVALID_MESSAGE_SCOPE/)
  await assert.rejects(insertMessageOnce(pool, { ...input, actorIds: new Set(['other']) }),
    { message: 'MESSAGE_ID_CONFLICT', status: 409 })
  await assert.rejects(insertMessageOnce(pool, { ...input, scopeId: '' }), /MESSAGE_ID_CONFLICT/)
})

test('database failures propagate instead of acknowledging an unsaved message', async () => {
  const error = new Error('database unavailable')
  await assert.rejects(insertMessageOnce({ query: async () => { throw error } }, input),
    (actual) => actual === error)
})

test('a failed duplicate lookup is not acknowledged', async () => {
  let queries = 0
  await assert.rejects(insertMessageOnce({ query: async () => {
    if (++queries === 1) return { rows: [] }
    throw new Error('connection interrupted')
  } }, input), /connection interrupted/)
})
