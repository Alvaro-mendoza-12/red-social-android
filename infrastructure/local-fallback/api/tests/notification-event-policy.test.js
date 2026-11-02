'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { isServerManagedNotification } = require('../src/notification-event-policy')
const { _test } = require('../src/postgrest-compat-routes')

const userId = '11111111-1111-4111-8111-111111111111'
const identity = { preferredUserId: userId, ids: new Set([userId]), values: [userId] }
const noQueries = { query() { throw Error('Rejected notification must not query or write') } }

test('reserved notices cover top-level and wrapped legacy event names', () => {
  for (const row of [{ type: 'system_announcement' }, { type: ' SYSTEM_ANNOUNCEMENT ' },
    { notification_type: 'system_announcement' }, { notificationType: 'system_announcement' },
    { type: 'activity', extra_data: { notification_type: 'system_announcement' } },
    { extraData: { notificationType: 'system_announcement' } },
    { data: '{"notification_type":"system_announcement"}' }]) {
    assert.equal(isServerManagedNotification(row), true)
  }
  for (const row of [null, {}, { type: 'follow' }, { extra_data: 'invalid' }, { data: 'null' }]) {
    assert.equal(isServerManagedNotification(row), false)
  }
})

test('compatibility inserts cannot bypass the admin notice outbox', async () => {
  for (const row of [{ type: 'system_announcement' },
    { type: 'activity', extra_data: { notificationType: 'SYSTEM_ANNOUNCEMENT' } }]) {
    await assert.rejects(_test.prepareInsertRow(noQueries, 'notifications', identity,
      { ...row, recipient_id: userId, actor_id: userId }),
    error => error.status === 403 && error.message === 'SERVER_MANAGED_NOTIFICATION')
  }
})

test('public and compatibility callers cannot forge account security events through any alias', async () => {
  for (const type of ['account_suspended', 'account_enabled']) {
    for (const row of [{ type }, { notificationType: type }, { notification_type: type },
      { type: 'message', extraData: { notification_type: type } },
      { extra_data: {}, extraData: { notificationType: type } },
      { extraData: {}, extra_data: JSON.stringify({ type }) },
      { data: JSON.stringify({ notification_type: type.toUpperCase() }) }]) {
      assert.equal(isServerManagedNotification(row), true)
      await assert.rejects(_test.prepareInsertRow(noQueries, 'notifications', identity,
        { ...row, recipient_id: userId, actor_id: userId }),
      error => error.status === 403 && error.message === 'SERVER_MANAGED_NOTIFICATION')
    }
  }
})

test('legacy message alerts are server managed while ordinary informational activity remains supported', () => {
  for (const content of ['Tu cuenta fue suspendida.', 'TU CUENTA HA SIDO SUSPENDIDA temporalmente.',
    'Tu cuenta ha sido habilitada nuevamente.', 'Tu cuenta fue habilitada.', 'Cuenta habilitada nuevamente']) {
    assert.equal(isServerManagedNotification({ type: 'message', content }), true)
  }
  assert.equal(isServerManagedNotification({ type: 'message', content: 'Te invitó a un grupo' }), true)
  assert.equal(isServerManagedNotification({ type: 'activity', content: 'Te invitó a un grupo' }), false)
  assert.equal(isServerManagedNotification({ type: 'follow', content: 'Cuenta fue suspendida' }), false)
})

test('recipients can read or delete their notice but cannot rewrite its event or sender', async () => {
  const row = { recipient_id: userId, actor_id: 'server', type: 'system_announcement' }
  assert.equal(await _test.authorizeMutation(noQueries, 'notifications', identity, row,
    { is_read: true, read_at: new Date().toISOString() }, 'patch'), true)
  assert.equal(await _test.authorizeMutation(noQueries, 'notifications', identity, row, {}, 'delete'), true)
  for (const patch of [{ type: 'follow' }, { actor_id: userId }, { recipient_id: 'other' },
    { extra_data: { notification_type: 'system_announcement' } }]) {
    assert.equal(await _test.authorizeMutation(noQueries, 'notifications', identity, row, patch, 'patch'), false)
  }
  assert.equal(await _test.authorizeMutation(noQueries, 'notifications', identity,
    { recipient_id: 'other' }, { is_read: true }, 'patch'), false)
})
