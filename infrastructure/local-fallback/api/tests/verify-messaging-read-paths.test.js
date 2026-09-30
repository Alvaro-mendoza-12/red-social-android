const assert = require('assert/strict')
const {
  opaqueRef,
  selectChatItem,
  verifyMessagingImages,
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

const test = require('node:test')
test('profiles without avatar URLs are valid but image delivery is explicitly untested', async () => {
  const result = await verifyMessagingImages({ payload: [{ avatar_url: null }],
    verifyImageImpl: async () => assert.fail('no image should be fetched') })
  assert.deepEqual(result, { images: [], avatarDelivery: 'not-exercised-no-avatar-url' })
})
test('available avatar failures still fail messaging verification', async () => {
  await assert.rejects(verifyMessagingImages({ payload: { avatar_url: 'https://example.test/avatar.jpg' },
    verifyImageImpl: async () => { throw new Error('avatar download failed') } }), /avatar download failed/)
})
test('successful avatars report verified coverage', async () => {
  const result = await verifyMessagingImages({ payload: { avatar_url: 'https://example.test/avatar.jpg' },
    verifyImageImpl: async () => ({ status: 200, ref: 'opaque' }) })
  assert.equal(result.avatarDelivery, 'verified')
  assert.equal(result.images.length, 1)
})
