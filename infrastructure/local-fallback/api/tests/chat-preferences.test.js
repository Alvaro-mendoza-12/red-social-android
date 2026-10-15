const { test } = require('node:test')
const assert = require('node:assert/strict')
const { ownChatParticipant, chatPreferencesOf } = require('../src/data-routes')
const identity = { ids: new Set(['local', 'public']), displayUserId: 'public' }

test('chat details retain all saved preferences', () => {
  assert.deepEqual(chatPreferencesOf({ is_pinned: true, is_muted: true, is_archived: true, is_favorite: true }), { isPinned: true, isMuted: true, isArchived: true, isFavorite: true })
})
test('an explicit false remains false', () => {
  assert.equal(chatPreferencesOf({ is_pinned: false }).isPinned, false)
})
test('canonical local preferences supersede an imported participant snapshot', () => {
  const saved = { user_id: 'public', is_pinned: true }
  assert.equal(ownChatParticipant([{ user_id: 'public', is_pinned: false }, saved], identity), saved)
})
test('another participant never supplies personal preferences', () => {
  assert.deepEqual(ownChatParticipant([{ user_id: 'someone-else', is_pinned: true }], identity), {})
})
test('a migrated local alias still resolves without a canonical row', () => {
  const row = { user_id: 'local', is_muted: true }
  assert.equal(ownChatParticipant([row], identity), row)
})
