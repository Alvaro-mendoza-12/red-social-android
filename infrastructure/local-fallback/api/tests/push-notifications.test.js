const assert = require('node:assert/strict')
const { buildData, pushNotificationsStatus } = require('../src/push-notifications')

function testRecipientIsAuthoritative() {
  const data = buildData({
    id: 'notification-1',
    recipient_id: 'account-authoritative',
    actor_id: 'actor-1',
    actor_name: 'Ana',
    type: 'live_started',
    target_id: 'live-1',
    target_type: 'live',
    content: 'Ana inició un directo',
  }, {
    recipient_id: 'attacker-controlled',
    live_id: 'live-1',
  })

  assert.equal(data.recipient_id, 'account-authoritative')
  assert.equal(data.live_id, 'live-1')
  assert.equal(data.target_id, 'live-1')
}

function testPayloadDoesNotSerializeObjectsOrSecrets() {
  const data = buildData({
    id: 'notification-2',
    recipient_id: 'account-2',
    type: 'activity',
  }, {
    nested: { secret: 'must-not-be-serialized' },
    enabled: true,
  })

  assert.equal(data.nested, undefined)
  assert.equal(data.enabled, 'true')
}

function testPublicStatusDoesNotExposeConfigurationPaths() {
  const status = pushNotificationsStatus()
  assert.equal(Object.hasOwn(status, 'error'), false)
  assert.equal(JSON.stringify(status).includes('/run/secrets'), false)
  assert.match(status.errorCode || '', /^[A-Z0-9_.-]+$/)
}

testRecipientIsAuthoritative()
for (const body of [null, '', ' Ana ']) {
  const data = buildData({ type: 'friend_request_accepted', actor_name: 'Ana', recipient_id: 'account-2' }, { body })
  assert.equal(data.body, 'Ana aceptó tu solicitud de amistad')
  assert.equal(data.content, data.body)
}
assert.equal(buildData({ type: 'friend_request', actor_name: 'Ana' }).body, 'Ana te envió una solicitud de amistad')
assert.equal(buildData({ type: 'follow', actor_name: 'Ana', content: 'Mensaje específico' }).body, 'Mensaje específico')
testPayloadDoesNotSerializeObjectsOrSecrets()
testPublicStatusDoesNotExposeConfigurationPaths()
console.log('push notification payload tests passed')
const birthday = JSON.stringify({ version: 1, kind: 'birthday', message: '¡Feliz cumpleaños!', theme: 'garden' })
const letterPush = buildData({ type: 'message', recipient_id: 'account-2', content: birthday }, { content_type: 'birthday_card', content: birthday, body: birthday })
assert.equal(letterPush.body, '🎂 Te envió una tarjeta de cumpleaños')
assert.equal(letterPush.content, birthday, 'Keep the full letter for the local message replica')
