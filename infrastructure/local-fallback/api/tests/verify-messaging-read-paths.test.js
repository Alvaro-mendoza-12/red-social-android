const assert = require('assert/strict')
const {
  opaqueRef,
  selectChatItem,
} = require('../scripts/verify-messaging-read-paths')

assert.equal(opaqueRef('chat-a').length, 16)
assert.equal(opaqueRef(''), 'missing')
assert.deepEqual(
  selectChatItem({
    items: [
      { id: 'group-a', kind: 'group' },
      { id: 'chat-a', kind: 'chat' },
    ],
  }),
  { id: 'chat-a', kind: 'chat' },
)
assert.equal(selectChatItem({ items: [] }), null)
assert.equal(selectChatItem(null), null)

console.log('verify-messaging-read-paths tests passed')
