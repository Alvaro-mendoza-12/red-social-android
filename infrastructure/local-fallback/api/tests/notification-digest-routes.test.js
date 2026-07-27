const assert = require('assert/strict')
const {
  DEFAULT_PREFERENCES,
  categoryEnabled,
  digestIsDue,
  hasEnabledCategory,
  notificationCategory,
  notificationIsUrgent,
  normalizePreferences,
} = require('../src/notification-digest-routes')

function run() {
  assert.equal(notificationCategory({ type: 'mention_post' }), 'mentions')
  assert.equal(
    notificationCategory({ type: 'new_message', target_type: 'chat' }),
    'direct_messages',
  )
  assert.equal(
    notificationCategory({ type: 'new_message', target_type: 'community' }),
    'community_activity',
  )
  assert.equal(notificationCategory({ type: 'stream_reminder' }), 'upcoming_lives')
  assert.equal(notificationCategory({ type: 'like_post' }), null)

  assert.equal(notificationIsUrgent({ type: 'call_invite' }), true)
  assert.equal(notificationIsUrgent({ type: 'account_suspended' }), true)
  assert.equal(
    notificationIsUrgent({ type: 'new_message' }, { priority: 'urgent' }),
    true,
  )
  assert.equal(notificationIsUrgent({ type: 'new_message' }), false)

  const preferences = normalizePreferences({
    enabled: true,
    frequency: 'weekly',
    deliveryHour: 22,
    deliveryWeekday: 6,
    timezoneOffsetMinutes: -300,
    includeMentions: false,
    includeDirectMessages: true,
  })
  assert.equal(preferences.enabled, true)
  assert.equal(preferences.frequency, 'weekly')
  assert.equal(preferences.deliveryHour, 22)
  assert.equal(preferences.deliveryWeekday, 6)
  assert.equal(preferences.timezoneOffsetMinutes, -300)
  assert.equal(categoryEnabled(preferences, 'direct_messages'), true)
  assert.equal(categoryEnabled(preferences, 'mentions'), false)
  assert.equal(hasEnabledCategory(preferences), true)

  const noCategories = normalizePreferences({
    ...DEFAULT_PREFERENCES,
    includeMentions: false,
    includeDirectMessages: false,
    includeCommunityActivity: false,
    includeUpcomingLives: false,
  })
  assert.equal(hasEnabledCategory(noCategories), false)

  const daily = {
    ...DEFAULT_PREFERENCES,
    enabled: true,
    deliveryHour: 18,
    timezoneOffsetMinutes: -300,
    lastDeliveredAt: '2026-07-25T23:30:00.000Z',
  }
  assert.equal(digestIsDue(daily, new Date('2026-07-26T22:59:00.000Z')), false)
  assert.equal(digestIsDue(daily, new Date('2026-07-26T23:01:00.000Z')), true)
  assert.equal(
    digestIsDue(
      { ...daily, lastDeliveredAt: '2026-07-26T23:00:30.000Z' },
      new Date('2026-07-26T23:01:00.000Z'),
    ),
    false,
  )

  const weekly = {
    ...daily,
    frequency: 'weekly',
    deliveryWeekday: 0,
  }
  assert.equal(digestIsDue(weekly, new Date('2026-07-26T23:01:00.000Z')), true)
  assert.equal(digestIsDue(weekly, new Date('2026-07-27T23:01:00.000Z')), false)

  console.log('notification-digest-routes tests passed')
}

run()
