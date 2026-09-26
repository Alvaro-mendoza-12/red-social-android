const { publishRealtimeEvent } = require('./realtime-publish')
const { sendPushForNotification } = require('./push-notifications')
const { stableKey } = require('./request-security')
const { safeErrorLogDetails } = require('./request-observability')

const DIGEST_CATEGORIES = Object.freeze([
  'mentions',
  'direct_messages',
  'community_activity',
  'upcoming_lives',
])

const DEFAULT_PREFERENCES = Object.freeze({
  enabled: false,
  frequency: 'daily',
  deliveryHour: 18,
  deliveryWeekday: 1,
  timezoneOffsetMinutes: 0,
  includeMentions: true,
  includeDirectMessages: false,
  includeCommunityActivity: true,
  includeUpcomingLives: true,
})

function cleanText(value, maxLength = 160) {
  const text = String(value || '').trim()
  return text ? text.slice(0, maxLength) : ''
}

function normalizeId(value) {
  return cleanText(value, 180).toLowerCase()
}

function booleanValue(value, fallback) {
  if (typeof value === 'boolean') return value
  if (typeof value === 'number') return value === 1
  if (typeof value === 'string') {
    const normalized = value.trim().toLowerCase()
    if (normalized === 'true') return true
    if (normalized === 'false') return false
  }
  return fallback
}

function integerInRange(value, fallback, min, max) {
  const parsed = Number(value)
  if (!Number.isInteger(parsed)) return fallback
  return Math.max(min, Math.min(max, parsed))
}

function normalizePreferences(input = {}, base = DEFAULT_PREFERENCES) {
  const frequencyCandidate = cleanText(input.frequency ?? base.frequency, 16).toLowerCase()
  return {
    enabled: booleanValue(input.enabled, base.enabled),
    frequency: frequencyCandidate === 'weekly' ? 'weekly' : 'daily',
    deliveryHour: integerInRange(
      input.deliveryHour ?? input.delivery_hour,
      base.deliveryHour,
      0,
      23,
    ),
    deliveryWeekday: integerInRange(
      input.deliveryWeekday ?? input.delivery_weekday,
      base.deliveryWeekday,
      0,
      6,
    ),
    timezoneOffsetMinutes: integerInRange(
      input.timezoneOffsetMinutes ?? input.timezone_offset_minutes,
      base.timezoneOffsetMinutes,
      -840,
      840,
    ),
    includeMentions: booleanValue(
      input.includeMentions ?? input.include_mentions,
      base.includeMentions,
    ),
    includeDirectMessages: booleanValue(
      input.includeDirectMessages ?? input.include_direct_messages,
      base.includeDirectMessages,
    ),
    includeCommunityActivity: booleanValue(
      input.includeCommunityActivity ?? input.include_community_activity,
      base.includeCommunityActivity,
    ),
    includeUpcomingLives: booleanValue(
      input.includeUpcomingLives ?? input.include_upcoming_lives,
      base.includeUpcomingLives,
    ),
  }
}

function preferenceRow(row) {
  if (!row) {
    return {
      ...DEFAULT_PREFERENCES,
      lastDeliveredAt: null,
      updatedAt: null,
    }
  }
  return {
    enabled: row.enabled === true,
    frequency: row.frequency === 'weekly' ? 'weekly' : 'daily',
    deliveryHour: integerInRange(row.delivery_hour, 18, 0, 23),
    deliveryWeekday: integerInRange(row.delivery_weekday, 1, 0, 6),
    timezoneOffsetMinutes: integerInRange(row.timezone_offset_minutes, 0, -840, 840),
    includeMentions: row.include_mentions !== false,
    includeDirectMessages: row.include_direct_messages === true,
    includeCommunityActivity: row.include_community_activity !== false,
    includeUpcomingLives: row.include_upcoming_lives !== false,
    lastDeliveredAt: row.last_delivered_at ? new Date(row.last_delivered_at).toISOString() : null,
    updatedAt: row.updated_at ? new Date(row.updated_at).toISOString() : null,
  }
}

function notificationCategory(notification = {}, extraData = {}) {
  const type = cleanText(notification.type || extraData.notification_type, 80).toLowerCase()
  const targetType = cleanText(notification.target_type || notification.targetType || extraData.target_type, 80)
    .toLowerCase()

  if (type === 'notification_digest') return null
  if (type.includes('mention')) return 'mentions'
  if (
    type.startsWith('live_') ||
    type === 'new_live' ||
    type === 'stream_reminder' ||
    type === 'stream_soon' ||
    targetType === 'live'
  ) {
    return 'upcoming_lives'
  }
  if (
    targetType === 'group' ||
    targetType === 'community' ||
    type.startsWith('group_') ||
    type.startsWith('community_')
  ) {
    return 'community_activity'
  }
  if (
    (type === 'message' || type === 'new_message') &&
    targetType !== 'group' &&
    targetType !== 'community'
  ) {
    return 'direct_messages'
  }
  return null
}

function notificationIsUrgent(notification = {}, extraData = {}) {
  const type = cleanText(notification.type || extraData.notification_type, 80).toLowerCase()
  const priority = cleanText(extraData.priority || extraData.importance, 24).toLowerCase()
  if (
    booleanValue(extraData.urgent ?? extraData.is_urgent ?? extraData.isUrgent, false) ||
    priority === 'urgent' ||
    priority === 'high' ||
    priority === 'critical'
  ) {
    return true
  }
  return type.startsWith('call_') ||
    type.startsWith('account_') ||
    type.startsWith('security_') ||
    type.startsWith('admin_') ||
    type.startsWith('moderation_') ||
    type === 'voice_access_request' ||
    type === 'friend_request'
}

function categoryEnabled(preferences, category) {
  if (category === 'mentions') return preferences.includeMentions
  if (category === 'direct_messages') return preferences.includeDirectMessages
  if (category === 'community_activity') return preferences.includeCommunityActivity
  if (category === 'upcoming_lives') return preferences.includeUpcomingLives
  return false
}

function hasEnabledCategory(preferences) {
  return DIGEST_CATEGORIES.some((category) => categoryEnabled(preferences, category))
}

async function ensureNotificationDigestTables(pool) {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_notification_digest_preferences (
      user_id TEXT PRIMARY KEY,
      enabled BOOLEAN NOT NULL DEFAULT FALSE,
      frequency TEXT NOT NULL DEFAULT 'daily'
        CHECK (frequency IN ('daily', 'weekly')),
      delivery_hour SMALLINT NOT NULL DEFAULT 18
        CHECK (delivery_hour BETWEEN 0 AND 23),
      delivery_weekday SMALLINT NOT NULL DEFAULT 1
        CHECK (delivery_weekday BETWEEN 0 AND 6),
      timezone_offset_minutes SMALLINT NOT NULL DEFAULT 0
        CHECK (timezone_offset_minutes BETWEEN -840 AND 840),
      include_mentions BOOLEAN NOT NULL DEFAULT TRUE,
      include_direct_messages BOOLEAN NOT NULL DEFAULT FALSE,
      include_community_activity BOOLEAN NOT NULL DEFAULT TRUE,
      include_upcoming_lives BOOLEAN NOT NULL DEFAULT TRUE,
      last_delivered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_notification_digest_due
     ON fallback_notification_digest_preferences(enabled, last_delivered_at)
     WHERE enabled = TRUE`,
  )
}

async function loadPreferences(pool, userId) {
  const { rows } = await pool.query(
    `SELECT *
       FROM fallback_notification_digest_preferences
      WHERE lower(user_id) IN (
        SELECT lower($1)
        UNION
        SELECT lower(id::text)
          FROM fallback_users
         WHERE lower(id::text) = lower($1)
            OR lower(COALESCE(supabase_user_id::text, '')) = lower($1)
        UNION
        SELECT lower(COALESCE(supabase_user_id::text, ''))
          FROM fallback_users
         WHERE lower(id::text) = lower($1)
            OR lower(COALESCE(supabase_user_id::text, '')) = lower($1)
      )
      LIMIT 1`,
    [userId],
  )
  return preferenceRow(rows[0])
}

async function shouldDeferNotificationPush(pool, notification, extraData = {}) {
  if (!notification?.recipient_id && !notification?.recipientId) {
    return { defer: false, category: null }
  }
  if (notificationIsUrgent(notification, extraData)) return { defer: false, category: null }
  const category = notificationCategory(notification, extraData)
  if (!category) return { defer: false, category: null }
  const preferences = await loadPreferences(
    pool,
    notification.recipient_id || notification.recipientId,
  )
  return {
    defer: preferences.enabled && categoryEnabled(preferences, category),
    category,
  }
}

function shiftedUtcParts(date, offsetMinutes) {
  const shifted = new Date(date.getTime() + offsetMinutes * 60_000)
  return {
    year: shifted.getUTCFullYear(),
    month: shifted.getUTCMonth(),
    day: shifted.getUTCDate(),
    weekday: shifted.getUTCDay(),
    hour: shifted.getUTCHours(),
  }
}

function localDateKey(parts) {
  return `${parts.year}-${String(parts.month + 1).padStart(2, '0')}-${String(parts.day).padStart(2, '0')}`
}

function digestIsDue(preferences, now = new Date()) {
  if (!preferences.enabled) return false
  const offset = preferences.timezoneOffsetMinutes
  const current = shiftedUtcParts(now, offset)
  if (current.hour < preferences.deliveryHour) return false
  if (preferences.frequency === 'weekly' && current.weekday !== preferences.deliveryWeekday) {
    return false
  }
  if (!preferences.lastDeliveredAt) return true
  const lastDate = new Date(preferences.lastDeliveredAt)
  if (Number.isNaN(lastDate.getTime())) return true
  return localDateKey(shiftedUtcParts(lastDate, offset)) !== localDateKey(current)
}

function digestPeriodStart(preferences, now = new Date()) {
  const days = preferences.frequency === 'weekly' ? 7 : 1
  return new Date(now.getTime() - days * 24 * 60 * 60 * 1000)
}

function emptyCategoryCounts() {
  return {
    mentions: 0,
    directMessages: 0,
    communityActivity: 0,
    upcomingLives: 0,
  }
}

function categoryResponseKey(category) {
  if (category === 'direct_messages') return 'directMessages'
  if (category === 'community_activity') return 'communityActivity'
  if (category === 'upcoming_lives') return 'upcomingLives'
  return 'mentions'
}

async function buildDigestSummary(pool, userId, preferences, since = null, now = new Date()) {
  const start = since ? new Date(since) : digestPeriodStart(preferences, now)
  const safeStart = Number.isNaN(start.getTime()) ? digestPeriodStart(preferences, now) : start
  const { rows } = await pool.query(
    `SELECT id::text, actor_id, actor_name, actor_avatar_url, type, target_id, target_type,
            content, is_read, extra_data, created_at
       FROM fallback_notifications
      WHERE lower(recipient_id) IN (
        SELECT lower($1)
        UNION
        SELECT lower(id::text)
          FROM fallback_users
         WHERE lower(id::text) = lower($1)
            OR lower(COALESCE(supabase_user_id::text, '')) = lower($1)
        UNION
        SELECT lower(COALESCE(supabase_user_id::text, ''))
          FROM fallback_users
         WHERE lower(id::text) = lower($1)
            OR lower(COALESCE(supabase_user_id::text, '')) = lower($1)
      )
        AND created_at >= $2
        AND created_at <= $3
        AND COALESCE(extra_data->>'digest_deferred', 'false') = 'true'
      ORDER BY created_at DESC
      LIMIT 500`,
    [userId, safeStart.toISOString(), now.toISOString()],
  )
  const categories = emptyCategoryCounts()
  const items = []
  let unreadCount = 0
  for (const row of rows) {
    const extraData = row.extra_data && typeof row.extra_data === 'object' ? row.extra_data : {}
    const storedCategory = DIGEST_CATEGORIES.includes(extraData.digest_category)
      ? extraData.digest_category
      : null
    const category = storedCategory || notificationCategory(row, extraData)
    if (!category || !categoryEnabled(preferences, category)) continue
    categories[categoryResponseKey(category)] += 1
    if (row.is_read !== true) unreadCount += 1
    if (items.length < 6) {
      items.push({
        id: String(row.id || ''),
        actorName: cleanText(row.actor_name, 160) || 'Novvin',
        actorAvatarUrl: cleanText(row.actor_avatar_url, 700) || null,
        type: cleanText(row.type, 80) || 'activity',
        targetId: cleanText(row.target_id, 180) || null,
        targetType: cleanText(row.target_type, 80) || null,
        content: cleanText(row.content, 700) || null,
        category,
        createdAt: new Date(row.created_at).toISOString(),
      })
    }
  }
  const total = Object.values(categories).reduce((sum, value) => sum + value, 0)
  return {
    frequency: preferences.frequency,
    periodStart: safeStart.toISOString(),
    periodEnd: now.toISOString(),
    total,
    unreadCount,
    categories,
    items,
  }
}

function digestContent(summary) {
  const parts = []
  if (summary.categories.mentions) parts.push(`${summary.categories.mentions} menciones`)
  if (summary.categories.directMessages) parts.push(`${summary.categories.directMessages} mensajes`)
  if (summary.categories.communityActivity) {
    parts.push(`${summary.categories.communityActivity} actividades de comunidades`)
  }
  if (summary.categories.upcomingLives) parts.push(`${summary.categories.upcomingLives} directos próximos`)
  return `Tu resumen de Novvin: ${parts.join(', ')}.`
}

async function deliverDigestForPreference(pool, row, now = new Date()) {
  const userId = normalizeId(row.user_id)
  if (!userId) return { delivered: false, reason: 'USER_ID_MISSING' }
  const preferences = preferenceRow(row)
  if (!digestIsDue(preferences, now)) return { delivered: false, reason: 'NOT_DUE' }

  const client = await pool.connect()
  const lockKey = `notification-digest:${userId}`
  let locked = false
  try {
    const lockResult = await client.query(
      `SELECT pg_try_advisory_lock(hashtext($1)) AS locked`,
      [lockKey],
    )
    locked = lockResult.rows[0]?.locked === true
    if (!locked) return { delivered: false, reason: 'LOCKED' }

    const currentResult = await client.query(
      `SELECT *
         FROM fallback_notification_digest_preferences
        WHERE lower(user_id) = lower($1)
        LIMIT 1`,
      [userId],
    )
    const currentRow = currentResult.rows[0]
    const currentPreferences = preferenceRow(currentRow)
    if (!currentRow || !digestIsDue(currentPreferences, now)) {
      return { delivered: false, reason: 'NO_LONGER_DUE' }
    }
    const summary = await buildDigestSummary(
      client,
      currentRow.user_id,
      currentPreferences,
      currentRow.last_delivered_at,
      now,
    )
    let notification = null
    if (summary.total > 0) {
      const extraData = {
        notification_type: 'notification_digest',
        digest_frequency: currentPreferences.frequency,
        digest_total: summary.total,
        digest_unread_count: summary.unreadCount,
        digest_categories: summary.categories,
        digest_period_start: summary.periodStart,
        digest_period_end: summary.periodEnd,
      }
      const inserted = await client.query(
        `INSERT INTO fallback_notifications(
            recipient_id, actor_name, type, target_type, content, extra_data
         )
         VALUES ($1, 'Novvin', 'notification_digest', 'notifications', $2, $3::jsonb)
         RETURNING id::text, recipient_id, actor_id, actor_name, actor_avatar_url, type,
                   target_id, target_type, content, is_read, extra_data, created_at, updated_at`,
        [currentRow.user_id, digestContent(summary), JSON.stringify(extraData)],
      )
      notification = inserted.rows[0]
      await publishRealtimeEvent(`user:${currentRow.user_id}`, {
        event: 'notification',
        notification,
        extraData,
      }).catch(() => {})
      await sendPushForNotification(client, notification, extraData).catch((error) => {
        console.error('[notification-digest] push failed', {
          userRef: stableKey(userId),
          ...safeErrorLogDetails(error),
        })
      })
    }
    await client.query(
      `UPDATE fallback_notification_digest_preferences
          SET last_delivered_at = $2, updated_at = NOW()
        WHERE lower(user_id) = lower($1)`,
      [userId, now.toISOString()],
    )
    return { delivered: Boolean(notification), total: summary.total }
  } finally {
    if (locked) {
      await client.query(`SELECT pg_advisory_unlock(hashtext($1))`, [lockKey]).catch(() => {})
    }
    client.release()
  }
}

async function processDueNotificationDigests(pool, now = new Date()) {
  const { rows } = await pool.query(
    `SELECT *
       FROM fallback_notification_digest_preferences
      WHERE enabled = TRUE
      ORDER BY last_delivered_at
      LIMIT 500`,
  )
  const result = { checked: rows.length, delivered: 0, notifications: 0 }
  for (const row of rows) {
    const delivery = await deliverDigestForPreference(pool, row, now)
    if (delivery.reason === 'NOT_DUE') continue
    result.delivered += 1
    if (delivery.delivered) result.notifications += 1
  }
  return result
}

function registerNotificationDigestRoutes(app, { pool, authRequired, getCurrentUser, userIdentity }) {
  app.get('/v1/data/notification-digest/preferences', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      res.json({ preferences: await loadPreferences(pool, identity.displayUserId) })
    } catch (error) {
      next(error)
    }
  })

  app.put('/v1/data/notification-digest/preferences', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const current = await loadPreferences(pool, identity.displayUserId)
      const preferences = normalizePreferences(req.body || {}, current)
      if (preferences.enabled && !hasEnabledCategory(preferences)) {
        return res.status(400).json({ error: 'DIGEST_CATEGORY_REQUIRED' })
      }
      const { rows } = await pool.query(
        `INSERT INTO fallback_notification_digest_preferences(
            user_id, enabled, frequency, delivery_hour, delivery_weekday,
            timezone_offset_minutes, include_mentions, include_direct_messages,
            include_community_activity, include_upcoming_lives, last_delivered_at,
            created_at, updated_at
         )
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, NOW(), NOW(), NOW())
         ON CONFLICT (user_id) DO UPDATE SET
            enabled = EXCLUDED.enabled,
            frequency = EXCLUDED.frequency,
            delivery_hour = EXCLUDED.delivery_hour,
            delivery_weekday = EXCLUDED.delivery_weekday,
            timezone_offset_minutes = EXCLUDED.timezone_offset_minutes,
            include_mentions = EXCLUDED.include_mentions,
            include_direct_messages = EXCLUDED.include_direct_messages,
            include_community_activity = EXCLUDED.include_community_activity,
            include_upcoming_lives = EXCLUDED.include_upcoming_lives,
            last_delivered_at = CASE
              WHEN fallback_notification_digest_preferences.enabled = FALSE
               AND EXCLUDED.enabled = TRUE THEN NOW()
              ELSE fallback_notification_digest_preferences.last_delivered_at
            END,
            updated_at = NOW()
         RETURNING *`,
        [
          identity.displayUserId,
          preferences.enabled,
          preferences.frequency,
          preferences.deliveryHour,
          preferences.deliveryWeekday,
          preferences.timezoneOffsetMinutes,
          preferences.includeMentions,
          preferences.includeDirectMessages,
          preferences.includeCommunityActivity,
          preferences.includeUpcomingLives,
        ],
      )
      res.json({ preferences: preferenceRow(rows[0]) })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/notification-digest', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const preferences = await loadPreferences(pool, identity.displayUserId)
      const summary = await buildDigestSummary(pool, identity.displayUserId, preferences)
      res.json({ preferences, summary })
    } catch (error) {
      next(error)
    }
  })
}

module.exports = {
  DEFAULT_PREFERENCES,
  DIGEST_CATEGORIES,
  buildDigestSummary,
  categoryEnabled,
  digestIsDue,
  ensureNotificationDigestTables,
  hasEnabledCategory,
  notificationCategory,
  notificationIsUrgent,
  normalizePreferences,
  processDueNotificationDigests,
  registerNotificationDigestRoutes,
  shouldDeferNotificationPush,
  _test: {
    digestContent,
    digestPeriodStart,
    preferenceRow,
    shiftedUtcParts,
  },
}
