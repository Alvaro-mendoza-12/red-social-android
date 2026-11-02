'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { buildData } = require('../src/push-notifications')
const { isServerManagedNotification } = require('../src/notification-event-policy')

test('extra metadata cannot turn an ordinary notice into a message or impersonate its sender', () => {
  const notice = { id: 'actual-notice', type: 'follow', recipient_id: 'receiver', actor_id: 'actual-actor',
    actor_name: 'Actual sender', actor_avatar_url: 'https://example.invalid/actual.jpg',
    target_id: 'actual-target', target_type: 'profile', content: 'Actual content' }
  const data = buildData(notice, { type: 'new_message', notification_id: 'forged-notice',
    sender_id: 'someone-else', actor_id: 'someone-else', sender_name: 'Someone else',
    sender_avatar: 'https://example.invalid/forged.jpg', target_id: 'another-room', target_type: 'chat' })
  assert.equal(data.type, notice.type)
  assert.equal(data.notification_id, notice.id)
  assert.equal(data.sender_id, notice.actor_id)
  assert.equal(data.actor_id, notice.actor_id)
  assert.equal(data.sender_name, notice.actor_name)
  assert.equal(data.sender_avatar, notice.actor_avatar_url)
  assert.equal(data.target_id, notice.target_id)
  assert.equal(data.target_type, notice.target_type)
})

test('message deliveries and read or reaction controls originate from their own server transactions', () => {
  const types = ['message', 'new_message', 'chat_message', 'group_message', 'community_message',
    'text', 'image', 'video', 'audio', 'sticker', 'file', 'document', 'location', 'contact',
    'conversation_read', 'message_reaction']
  for (const type of types) {
    for (const input of [{ type }, { type: 'activity', extraData: { type } },
      { type: 'follow', extra_data: JSON.stringify({ notification_type: type }) }]) {
      assert.equal(isServerManagedNotification(input), true, JSON.stringify(input))
    }
  }
  assert.equal(isServerManagedNotification({ type: 'follow' }), false)
  assert.equal(isServerManagedNotification({ type: 'call_invite', extraData: { call_id: 'owned-call' } }), false,
    'Call alerts keep their separate persisted membership authorization')
})

test('authoritative message content and routing metadata remain available to the receiving client', () => {
  const content = '{"kind":"birthday","message":"Hola"}'
  const data = buildData({ id: 'notice', type: 'message', actor_id: 'sender', recipient_id: 'receiver',
    target_type: 'chat', target_id: 'room', content },
  { chat_id: 'room', message_id: 'persisted-message', content_type: 'birthday_card', content })
  assert.equal(data.chat_id, 'room')
  assert.equal(data.message_id, 'persisted-message')
  assert.equal(data.content, content)
  assert.equal(data.body, '🎂 Te envió una tarjeta de cumpleaños')
})
