'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { isRetiredNotification } = require('../src/retired-notifications')
const { createLocalNotificationBestEffort, localActivityNotificationsByRecipientIds } = require('../src/data-routes')
const { sendPushForNotification } = require('../src/push-notifications')

test('retired moment views cover legacy, wrapped and compatibility payloads', () => {
  for (const row of [{ type: 'view_moment' }, { type: ' VIEW_MOMENT ' },
    { notification_type: 'view_moment' }, { type: 'activity', extra_data: { notificationType: 'view_moment' } },
    { data: '{"notification_type":"view_moment"}' }]) assert.equal(isRetiredNotification(row), true)
  for (const type of ['new_moment', 'like_moment', 'system_announcement', 'follow']) {
    assert.equal(isRetiredNotification({ type }), false)
  }
  assert.equal(isRetiredNotification({ extra_data: 'invalid' }), false)
})

test('retired notifications never write or look up recipient push tokens', async () => {
  const pool = { query: () => { throw Error('Retired event must not query PostgreSQL') } }
  assert.equal(await createLocalNotificationBestEffort(pool,
    { recipientId: 'owner', actorId: 'viewer', type: 'view_moment' }), null)
  assert.deepEqual(await sendPushForNotification(pool,
    { recipient_id: 'owner', type: 'view_moment' }), { sent: 0, failed: 0, retired: true })
  assert.deepEqual(await sendPushForNotification(pool,
    { recipient_id: 'owner', type: 'activity' }, { notification_type: 'view_moment' }),
    { sent: 0, failed: 0, retired: true })
})

test('activity pagination and unread counts exclude retired rows before LIMIT', async () => {
  const pool = { query: async sql => {
    assert.match(sql, /effective_type <> 'view_moment'/)
    assert.match(sql, /lower\(trim\(type\)\) <> 'view_moment'/)
    assert.ok(sql.indexOf("effective_type <> 'view_moment'") < sql.indexOf('LIMIT $6'))
    return { rows: [] }
  } }
  assert.deepEqual(await localActivityNotificationsByRecipientIds(pool, ['owner']),
    { rows: [], unreadCount: 0 })
})
