const { test } = require('node:test')
const assert = require('node:assert/strict')
const { previewFromMessage, sortMessagesNewestFirst } = require('../src/data-routes')
const { refreshChatSummary } = require('../src/message-deletion-policy')

test('a sticker label is presented as Sticker rather than as a text message', () => {
  assert.equal(previewFromMessage({ id: 'latest-sticker', content_type: 'sticker', content: 'Nombre del sticker', media_url: 'https://fixture.invalid/sticker.webp' }), 'Sticker')
})

test('summary repair uses the same sticker business rule', async () => {
  const latest = { id: 'latest-sticker', chat_id: 'fixture-chat', sender_id: 'fixture-user', created_at: '2026-11-13T20:00:00.000Z', content_type: 'sticker', content: 'Nombre del sticker' }
  let summary
  const db = { query: async (sql, params) => {
    if (/SELECT \*/.test(sql)) return { rows: [latest] }
    summary = params
    return { rows: [], rowCount: 1 }
  } }
  await refreshChatSummary(db, 'fixture-chat')
  assert.equal(summary[1], 'Sticker')
  assert.equal(summary[4], 'latest-sticker')
})

test('ordinary text and an image caption retain their content', () => {
  assert.equal(previewFromMessage({ content_type: 'text', content: 'Mensaje normal' }), 'Mensaje normal')
  assert.equal(previewFromMessage({ content_type: 'image', content: 'Una foto con contexto' }), 'Una foto con contexto')
})

test('the inbox chooses the same last message as history within one millisecond', () => {
  const older = { id: 'older', created_at: '2026-11-13T20:00:00.000001Z' }
  const newer = { id: 'newer', created_at: '2026-11-13T20:00:00.000002Z' }
  assert.equal(sortMessagesNewestFirst([older, newer])[0].id, 'newer')
  assert.equal(sortMessagesNewestFirst([newer, older])[0].id, 'newer')
})

test('equal timestamps use the history id tie breaker rather than source read order', () => {
  const a = { id: 'a', created_at: '2026-11-13T20:00:00.000002Z' }
  const b = { id: 'b', created_at: '2026-11-13T20:00:00.000002Z' }
  assert.equal(sortMessagesNewestFirst([a, b])[0].id, 'b')
  assert.equal(sortMessagesNewestFirst([b, a])[0].id, 'b')
})
