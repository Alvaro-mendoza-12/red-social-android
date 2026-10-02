const test = require('node:test')
const assert = require('node:assert/strict')
const { notificationRealtimeEnvelope, publishEphemeralCallEvent } = require('../src/notification-realtime-envelope')
const base = { id: 'notification', recipient_id: 'callee', actor_id: 'caller', actor_name: 'Alvaro', target_id: 'call', content: 'Llamada' }

test('ephemeral invite reaches the recipient stream without storing social activity', async () => {
  const calls = []
  const notification = { ...base, type: 'call_invite', ephemeral: true }
  assert.equal(await publishEphemeralCallEvent(notification, { call_is_video: true }, async (...args) => { calls.push(args); return true }), true)
  assert.equal(calls.length, 1)
  assert.equal(calls[0][0], 'user:callee')
  assert.equal(calls[0][1].event, 'call_invite')
  assert.equal(calls[0][1].callId, 'call')
  assert.equal(calls[0][1].callerUserId, 'caller')
  assert.equal(calls[0][1].isVideo, true)
  assert.equal(calls[0][1].notification, notification)
})

test('cancel and response retain the same call ID for out of order delivery', async () => {
  const cancel = notificationRealtimeEnvelope({ ...base, type: 'call_cancel' })
  assert.equal(cancel.event, 'call_response')
  assert.equal(cancel.callId, 'call')
  assert.equal(cancel.action, 'cancel')
  assert.equal(cancel.fromUserId, 'caller')
  const response = notificationRealtimeEnvelope({ ...base, type: 'call_response' }, { callId: 'new-call', callAction: 'end', fromUserId: 'guest' })
  assert.equal(response.callId, 'new-call')
  assert.equal(response.action, 'end')
  assert.equal(response.fromUserId, 'guest')
})

test('ordinary messages do not emit duplicate notification events', async () => {
  let deliveries = 0
  const publish = async () => { deliveries++; return true }
  assert.equal(await publishEphemeralCallEvent({ ...base, type: 'message' }, {}, publish), false)
  assert.equal(await publishEphemeralCallEvent({ type: 'call_cancel' }, {}, publish), false)
  assert.equal(deliveries, 0)
})

test('unavailable gateway reports failure so push remains an independent channel', async () => {
  assert.equal(await publishEphemeralCallEvent({ ...base, type: 'call_cancel' }, {}, async () => false), false)
})
