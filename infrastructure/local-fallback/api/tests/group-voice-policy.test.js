const { test } = require('node:test')
const assert = require('node:assert/strict')
const { voiceInvitees, selectVoiceInvitees, groupVoiceRequestHash } = require('../src/group-voice-policy')
const members = [{ id: 'creator', name: 'Owner' }, { id: 'a', name: 'Ana', avatarUrl: '/a.webp' },
  { id: 'b', name: 'Bea' }, { id: 'former', role: 'former_member' }]
const select = (mode, recipientIds, override = {}) => selectVoiceInvitees({ members, creatorIds: ['creator'], mode, recipientIds, ...override })

test('all includes each eligible member and excludes its creator', () => {
  assert.deepEqual(select('all', ['a', 'b']).map(member => member.id), ['a', 'b'])
})
test('individual selection preserves the selected public profile', () => {
  assert.deepEqual(select('selected', ['a']), [{ id: 'a', name: 'Ana', avatarUrl: '/a.webp' }])
})
test('creator aliases never become recipients', () => {
  assert.deepEqual(voiceInvitees([...members, { id: 'owner-alias' }], ['creator', 'owner-alias']).map(member => member.id), ['a', 'b'])
})
test('equivalent IDs and repeated selections produce only one invitation', () => {
  assert.deepEqual(select('selected', [' A ', 'a', 'A']).map(member => member.id), ['a'])
})
test('a member removed after opening the modal is rejected', () => {
  assert.throws(() => select('selected', ['former']), { code: 'GROUP_VOICE_MEMBERS_CHANGED', status: 409 })
})
test('an outside account cannot be invited by passing its identifier', () => {
  assert.throws(() => select('selected', ['outside']), { code: 'GROUP_VOICE_MEMBERS_CHANGED' })
})
test('all does not silently use an incomplete cached roster', () => {
  assert.throws(() => select('all', ['a']), { code: 'GROUP_VOICE_MEMBERS_CHANGED' })
})
test('all requires refreshing when another member joins before confirmation', () => {
  assert.throws(() => select('all', ['a', 'b'], { members: [...members, { id: 'c' }] }), { code: 'GROUP_VOICE_MEMBERS_CHANGED' })
})
test('selected mode allows new unselected members without expanding recipients', () => {
  assert.deepEqual(select('selected', ['a'], { members: [...members, { id: 'c' }] }).map(member => member.id), ['a'])
})
test('an empty selection cannot create an uninvited room', () => {
  assert.throws(() => select('selected', []), { code: 'GROUP_VOICE_INVITEES_REQUIRED' })
})
test('malformed selection is rejected rather than coerced into identifiers', () => {
  assert.throws(() => select('selected', [{}]), { code: 'GROUP_VOICE_RECIPIENT_INVALID' })
})
test('the request identity is stable across ordering and distinct across intentions', () => {
  const first = groupVoiceRequestHash({ groupId: ' group ', mode: 'selected', recipientIds: ['a', 'b'] })
  assert.equal(first, groupVoiceRequestHash({ groupId: 'group', mode: 'selected', recipientIds: ['B', 'a', 'a'] }))
  assert.notEqual(first, groupVoiceRequestHash({ groupId: 'group', mode: 'selected', recipientIds: ['a'] }))
})
