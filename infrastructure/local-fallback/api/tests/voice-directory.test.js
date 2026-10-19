const test = require('node:test')
const assert = require('node:assert/strict')
const { isStandaloneVoiceRoom } = require('../src/data-routes')

test('voice directories exclude explicit community rooms without guessing from their names', () => {
  assert.equal(isStandaloneVoiceRoom({ id: 'ordinary', name: 'Comunidad de amigos' }), true)
  assert.equal(isStandaloneVoiceRoom({ community_id: 'community' }), false)
  assert.equal(isStandaloneVoiceRoom({ communityId: 'community' }), false)
  assert.equal(isStandaloneVoiceRoom({ community_channel_id: 'channel' }), false)
  assert.equal(isStandaloneVoiceRoom({ communityChannelId: 'channel' }), false)
  assert.equal(isStandaloneVoiceRoom({ community_id: '  ', community_channel_id: null }), true)
})
