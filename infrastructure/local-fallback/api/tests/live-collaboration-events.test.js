const assert = require('node:assert/strict')
const { test } = require('node:test')
const { publishLiveCollaborationEvent } = require('../src/live-collaboration-events')

test('invitation changes go to the host and invitee with no private room broadcast', async () => {
  const original = global.fetch
  const deliveries = []
  global.fetch = async (url, options) => {
    deliveries.push(JSON.parse(options.body))
    return { ok: true }
  }
  try {
    const invitation = { id: 'invitation-1', live_id: 'live-1', host_user_id: 'HOST',
      invitee_user_id: 'guest', status: 'accepted', request_nonce: 'private-nonce' }
    assert.equal(await publishLiveCollaborationEvent('live-1', {
      type: 'guest_invitation_responded', invitation,
    }), true)
    assert.deepEqual(deliveries.map(item => item.channel), [
      'live-collaboration:live-1', 'user:host', 'user:guest',
    ])
    assert.equal(deliveries[0].payload.invitation, undefined)
    assert.equal(deliveries[0].payload.request_nonce, undefined)
    assert.deepEqual(deliveries[1].payload.invitation, invitation)
    assert.equal(deliveries[2].payload.event, 'live_collaboration')
  } finally { global.fetch = original }
})

test('recipient aliases are deduplicated and unrelated invitations are rejected', async () => {
  const original = global.fetch
  const channels = []
  global.fetch = async (url, options) => {
    channels.push(JSON.parse(options.body).channel)
    return { ok: true }
  }
  try {
    const invitation = { id: 'invitation-1', live_id: 'live-1', host_user_id: ' HOST ', invitee_user_id: 'host' }
    assert.equal(await publishLiveCollaborationEvent('live-1', { invitation }), true)
    assert.deepEqual(channels, ['live-collaboration:live-1', 'user:host'])
    assert.equal(await publishLiveCollaborationEvent('other-live', { invitation }), false)
    assert.equal(channels.length, 2)
  } finally { global.fetch = original }
})

test('room events keep their payload and delivery failures remain recoverable by polling', async () => {
  const original = global.fetch
  const deliveries = []
  global.fetch = async (url, options) => {
    deliveries.push(JSON.parse(options.body))
    return { ok: false }
  }
  try {
    assert.equal(await publishLiveCollaborationEvent('live-1', { type: 'battle_ended', battle: { id: 'battle-1' } }), false)
    assert.deepEqual(deliveries[0], { channel: 'live-collaboration:live-1',
      payload: { type: 'battle_ended', battle: { id: 'battle-1' }, event: 'live_collaboration', liveId: 'live-1' } })
  } finally { global.fetch = original }
})
