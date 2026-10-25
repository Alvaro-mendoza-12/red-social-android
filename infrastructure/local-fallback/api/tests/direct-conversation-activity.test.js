const { test } = require('node:test')
const assert = require('node:assert/strict')
const { directConversationHasActivity } = require('../src/direct-conversation-activity')
test('a new room or opening timestamp cannot create an inbox item', () => {
  assert.equal(directConversationHasActivity({ created_at: new Date(), last_message_at: new Date() }), false)
})
test('media, deletion tombstones and histories cleared for the viewer remain conversations', () => {
  assert.equal(directConversationHasActivity({ last_message_id: 'photo' }), true)
  assert.equal(directConversationHasActivity({}, true), true)
  assert.equal(directConversationHasActivity({ last_message: 'Hola' }), true)
})
