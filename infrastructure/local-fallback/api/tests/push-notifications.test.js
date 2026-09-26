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
testPayloadDoesNotSerializeObjectsOrSecrets()
testPublicStatusDoesNotExposeConfigurationPaths()
console.log('push notification payload tests passed')
