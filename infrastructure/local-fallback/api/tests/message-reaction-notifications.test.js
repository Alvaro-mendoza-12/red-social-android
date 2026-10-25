const { test } = require('node:test')
const assert = require('node:assert/strict')
const { reactionNotice, reactionNotificationsMuted } = require('../src/message-reaction-notifications')
test('notify only additions by another account, not own, removed or deleted reactions', () => {
  const input = { actorId: 'actor', actorIds: ['actor-alias'], ownerId: 'owner', emoji: '👍', reactions: { '👍': ['actor-alias'] } }
  assert.ok(reactionNotice(input))
  assert.equal(reactionNotice({ ...input, ownerId: 'actor-alias' }), null)
  assert.equal(reactionNotice({ ...input, reactions: {} }), null)
  assert.equal(reactionNotice({ ...input, deleted: true }), null)
  assert.equal(reactionNotice({ ...input, deletedBy: ['owner'] }), null)
})
test('the latest per-user mute preference takes precedence over imported settings', () => {
  assert.equal(reactionNotificationsMuted([{ user_id: 'owner', is_muted: true }], ['owner']), true)
  assert.equal(reactionNotificationsMuted([{ user_id: 'other', is_muted: true }], ['owner']), false)
  assert.equal(reactionNotificationsMuted([{ user_id: 'owner', is_muted: true }, { user_id: 'owner-alias', is_muted: false }], ['owner', 'owner-alias']), false)
})
