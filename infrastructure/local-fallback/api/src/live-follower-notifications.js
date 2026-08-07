const { publishRealtimeEvent } = require('./realtime-publish')
const { sendPushForNotification } = require('./push-notifications')
const { shouldDeferNotificationPush } = require('./notification-digest-routes')

const LIVE_FOLLOWER_NOTIFICATIONS_FLAG = 'LIVE_FOLLOWER_NOTIFICATIONS_ENABLED'
const MAX_DELIVERY_ATTEMPTS = 3

function envFlag(name) {
  return /^(1|true|yes|on)$/i.test(String(process.env[name] || '').trim())
}

function liveFollowerNotificationsEnabled() {
  return envFlag(LIVE_FOLLOWER_NOTIFICATIONS_FLAG)
}

function normalizeId(value) {
  return String(value || '').trim().toLowerCase()
}

function cleanText(value, maxLength = 160) {
  const text = String(value || '').trim()
  return text ? text.slice(0, maxLength) : ''
}

function objectValue(value) {
  if (value && typeof value === 'object' && !Array.isArray(value)) return value
  if (typeof value !== 'string') return {}
  try {
    const parsed = JSON.parse(value)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}
  } catch {
    return {}
  }
}

function didLiveStart(previousIsActive, hasRequestedState, requestedIsActive) {
  return previousIsActive === false && hasRequestedState === true && requestedIsActive === true
}

async function ensureLiveFollowerNotificationTables(pool) {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_live_follower_notification_outbox (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      live_id UUID NOT NULL REFERENCES fallback_live_streams(id) ON DELETE CASCADE,
      recipient_id TEXT NOT NULL,
      host_user_id TEXT NOT NULL,
      host_name TEXT NOT NULL DEFAULT 'Usuario',
      host_avatar_url TEXT,
      notification_type TEXT NOT NULL DEFAULT 'live_started'
        CHECK (notification_type IN ('live_started')),
      payload JSONB NOT NULL DEFAULT '{}'::jsonb,
      notification_id UUID REFERENCES fallback_notifications(id) ON DELETE SET NULL,
      status TEXT NOT NULL DEFAULT 'queued'
        CHECK (status IN ('queued', 'processing', 'delivered', 'failed', 'suppressed')),
      attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
      available_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      locked_at TIMESTAMPTZ,
      delivered_at TIMESTAMPTZ,
      last_error_code TEXT,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE UNIQUE INDEX IF NOT EXISTS idx_live_follower_notification_outbox_unique
    ON fallback_live_follower_notification_outbox(
      live_id,
      lower(recipient_id),
      notification_type
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_live_follower_notification_outbox_due
    ON fallback_live_follower_notification_outbox(status, available_at, created_at)
    WHERE status IN ('queued', 'failed', 'processing')
  `)
  // The delivery key is only present on rows created by this worker, so this
  // does not rewrite or delete historical activity-center notifications.
  await pool.query(`
    CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_notifications_live_started_delivery
    ON fallback_notifications(
      lower(recipient_id),
      lower(target_id),
      lower(type),
      (extra_data->>'delivery_key')
    )
    WHERE lower(type) = 'live_started'
      AND extra_data ? 'delivery_key'
  `)
}

function normalizedHostIds(event = {}) {
  const values = [
    ...(Array.isArray(event.hostIdentityIds) ? event.hostIdentityIds : []),
    event.hostUserId,
  ]
  return [...new Set(values.map(normalizeId).filter(Boolean))]
}

async function enqueueLiveStartedFollowerNotifications(client, event = {}) {
  if (!liveFollowerNotificationsEnabled()) return { enqueued: 0, disabled: true }

  const liveId = cleanText(event.liveId, 80)
  const hostUserId = normalizeId(event.hostUserId)
  const hostIds = normalizedHostIds(event)
  if (!liveId || !hostUserId || hostIds.length === 0) return { enqueued: 0, invalid: true }

  const payload = {
    notification_type: 'live_started',
    live_id: liveId,
    host_id: hostUserId,
    target_id: liveId,
    target_type: 'live',
  }
  const { rows } = await client.query(
    `WITH follower_candidates AS (
       SELECT lower(follower_id) AS recipient_id
         FROM fallback_follows
        WHERE lower(following_id) = ANY($2::text[])
     ), eligible_followers AS (
       SELECT DISTINCT follower_candidates.recipient_id
         FROM follower_candidates
        WHERE follower_candidates.recipient_id <> ALL($2::text[])
          AND NOT EXISTS (
            SELECT 1
              FROM fallback_user_blocks blocks
             WHERE (
               lower(blocks.blocker_id) = follower_candidates.recipient_id
               AND lower(blocks.blocked_id) = ANY($2::text[])
             ) OR (
               lower(blocks.blocked_id) = follower_candidates.recipient_id
               AND lower(blocks.blocker_id) = ANY($2::text[])
             )
          )
     ), eligible_with_preferences AS (
       SELECT eligible_followers.recipient_id
         FROM eligible_followers
         LEFT JOIN LATERAL (
           SELECT lower(COALESCE(
             overrides.profile->>'break_mode',
             overrides.profile->>'breakMode',
             'off'
           )) AS break_mode
             FROM fallback_profile_overrides overrides
            WHERE lower(overrides.user_id::text) = eligible_followers.recipient_id
            LIMIT 1
         ) preference ON TRUE
        WHERE COALESCE(preference.break_mode, 'off') NOT IN ('mute', 'hidden')
     ), inserted AS (
       INSERT INTO fallback_live_follower_notification_outbox(
         live_id,
         recipient_id,
         host_user_id,
         host_name,
         host_avatar_url,
         notification_type,
         payload,
         status,
         available_at
       )
       SELECT $1::uuid, recipient_id, $3, $4, $5, 'live_started', $6::jsonb, 'queued', NOW()
         FROM eligible_with_preferences
       ON CONFLICT DO NOTHING
       RETURNING id
     )
     SELECT COUNT(*)::int AS enqueued FROM inserted`,
    [
      liveId,
      hostIds,
      hostUserId,
      cleanText(event.hostName, 160) || 'Usuario',
      cleanText(event.hostAvatarUrl, 900) || null,
      JSON.stringify(payload),
    ],
  )
  return { enqueued: Number(rows[0]?.enqueued || 0), disabled: false }
}

function notificationForOutbox(outbox) {
  const payload = objectValue(outbox.payload)
  const liveId = cleanText(payload.live_id || outbox.live_id, 80)
  const recipientId = normalizeId(outbox.recipient_id)
  return {
    id: outbox.notification_id || null,
    recipient_id: recipientId,
    actor_id: normalizeId(outbox.host_user_id),
    actor_name: cleanText(outbox.host_name, 160) || 'Usuario',
    actor_avatar_url: cleanText(outbox.host_avatar_url, 900) || null,
    type: 'live_started',
    target_id: liveId,
    target_type: 'live',
    content: `${cleanText(outbox.host_name, 120) || 'Alguien'} inició un directo`,
    extra_data: {
      ...payload,
      notification_type: 'live_started',
      live_id: liveId,
      host_id: normalizeId(outbox.host_user_id),
      target_id: liveId,
      target_type: 'live',
      delivery_key: `live_started:${outbox.id}`,
    },
  }
}

async function recipientMayReceiveLiveNotification(pool, outbox) {
  const { rows } = await pool.query(
    `SELECT
       COALESCE((
         SELECT lower(COALESCE(
           overrides.profile->>'break_mode',
           overrides.profile->>'breakMode',
           'off'
         ))
           FROM fallback_profile_overrides overrides
          WHERE lower(overrides.user_id::text) = lower($1)
          LIMIT 1
       ), 'off') AS break_mode,
       EXISTS(
         SELECT 1
           FROM fallback_user_blocks blocks
          WHERE (
            lower(blocks.blocker_id) = lower($1)
            AND lower(blocks.blocked_id) = lower($2)
          ) OR (
            lower(blocks.blocked_id) = lower($1)
            AND lower(blocks.blocker_id) = lower($2)
          )
       ) AS blocked`,
    [outbox.recipient_id, outbox.host_user_id],
  )
  const row = rows[0] || {}
  const breakMode = normalizeId(row.break_mode || 'off')
  return !row.blocked && breakMode !== 'mute' && breakMode !== 'hidden'
}

async function claimPendingLiveFollowerNotifications(pool, limit = 25) {
  const safeLimit = Math.max(1, Math.min(Number.parseInt(limit, 10) || 25, 100))
  const { rows } = await pool.query(
    `WITH candidates AS (
       SELECT id
         FROM fallback_live_follower_notification_outbox
        WHERE (
          status = 'queued'
          AND available_at <= NOW()
        ) OR (
          status = 'processing'
          AND locked_at < NOW() - INTERVAL '5 minutes'
        ) OR (
          status = 'failed'
          AND attempts < $1
          AND available_at <= NOW()
        )
        ORDER BY created_at ASC
        LIMIT $2
        FOR UPDATE SKIP LOCKED
     )
     UPDATE fallback_live_follower_notification_outbox outbox
        SET status = 'processing',
            attempts = outbox.attempts + 1,
            locked_at = NOW(),
            updated_at = NOW()
       FROM candidates
      WHERE outbox.id = candidates.id
     RETURNING outbox.*`,
    [MAX_DELIVERY_ATTEMPTS, safeLimit],
  )
  return rows
}

async function persistLiveStartedNotification(pool, notification) {
  const { rows } = await pool.query(
    `INSERT INTO fallback_notifications(
       recipient_id,
       actor_id,
       actor_name,
       actor_avatar_url,
       type,
       target_id,
       target_type,
       content,
       extra_data
     )
     VALUES ($1, $2, $3, $4, 'live_started', $5, 'live', $6, $7::jsonb)
     ON CONFLICT DO NOTHING
     RETURNING id::text,
       recipient_id,
       actor_id,
       actor_name,
       actor_avatar_url,
       type,
       target_id,
       target_type,
       content,
       is_read,
       extra_data,
       created_at,
       updated_at`,
    [
      notification.recipient_id,
      notification.actor_id || null,
      notification.actor_name,
      notification.actor_avatar_url,
      notification.target_id,
      notification.content,
      JSON.stringify(notification.extra_data),
    ],
  )
  if (rows[0]) return rows[0]

  const existing = await pool.query(
    `SELECT id::text,
       recipient_id,
       actor_id,
       actor_name,
       actor_avatar_url,
       type,
       target_id,
       target_type,
       content,
       is_read,
       extra_data,
       created_at,
       updated_at
       FROM fallback_notifications
      WHERE lower(recipient_id) = lower($1)
        AND lower(target_id) = lower($2)
        AND lower(type) = 'live_started'
        AND extra_data->>'delivery_key' = $3
      LIMIT 1`,
    [notification.recipient_id, notification.target_id, notification.extra_data.delivery_key],
  )
  return existing.rows[0] || null
}

async function updateOutboxStatus(pool, outboxId, status, options = {}) {
  const errorCode = cleanText(options.errorCode, 80) || null
  const retry = options.retry === true
  const notificationId = cleanText(options.notificationId, 80) || null
  const retryDelaySeconds = Math.max(15, Math.min(Number(options.retryDelaySeconds) || 30, 3600))
  await pool.query(
    `UPDATE fallback_live_follower_notification_outbox
        SET status = $2,
            notification_id = COALESCE($3::uuid, notification_id),
            last_error_code = $4,
            available_at = CASE
              WHEN $5::boolean THEN NOW() + ($6::int * INTERVAL '1 second')
              ELSE available_at
            END,
            delivered_at = CASE WHEN $2 = 'delivered' THEN NOW() ELSE delivered_at END,
            locked_at = NULL,
            updated_at = NOW()
      WHERE id = $1::uuid`,
    [outboxId, status, notificationId, errorCode, retry, retryDelaySeconds],
  )
}

function retryDelaySeconds(attempts) {
  return Math.min(15 * (2 ** Math.max(0, Number(attempts || 1) - 1)), 15 * 60)
}

async function processPendingLiveFollowerNotifications(pool, dependencies = {}) {
  if (!liveFollowerNotificationsEnabled()) return { processed: 0, disabled: true }

  const claim = dependencies.claim || claimPendingLiveFollowerNotifications
  const isRecipientEligible = dependencies.isRecipientEligible || recipientMayReceiveLiveNotification
  const persist = dependencies.persist || persistLiveStartedNotification
  const sendPush = dependencies.sendPush || sendPushForNotification
  const publishRealtime = dependencies.publishRealtime || publishRealtimeEvent
  const shouldDefer = dependencies.shouldDefer || shouldDeferNotificationPush
  const setStatus = dependencies.setStatus || updateOutboxStatus
  const outboxRows = await claim(pool, dependencies.limit)
  const result = { processed: 0, delivered: 0, suppressed: 0, failed: 0 }

  for (const outbox of outboxRows) {
    result.processed += 1
    try {
      if (!(await isRecipientEligible(pool, outbox))) {
        await setStatus(pool, outbox.id, 'suppressed')
        result.suppressed += 1
        continue
      }

      const notification = await persist(pool, notificationForOutbox(outbox))
      if (!notification) throw new Error('LIVE_NOTIFICATION_PERSIST_FAILED')
      const extraData = notificationForOutbox(outbox).extra_data
      const delivery = await shouldDefer(pool, notification, extraData)
      const pushData = delivery?.defer
        ? { ...extraData, digest_deferred: true, digest_category: delivery.category }
        : extraData

      await publishRealtime(`user:${notification.recipient_id}`, {
        event: 'notification',
        notification,
        extraData: pushData,
      })
      const pushResult = await sendPush(pool, notification, pushData)
      const shouldRetry = Number(pushResult?.sent || 0) === 0 &&
        Number(pushResult?.failed || 0) > 0 &&
        Number(outbox.attempts || 0) < MAX_DELIVERY_ATTEMPTS
      if (shouldRetry) {
        await setStatus(pool, outbox.id, 'failed', {
          notificationId: notification.id,
          errorCode: 'PUSH_DELIVERY_FAILED',
          retry: true,
          retryDelaySeconds: retryDelaySeconds(outbox.attempts),
        })
        result.failed += 1
      } else {
        await setStatus(pool, outbox.id, 'delivered', { notificationId: notification.id })
        result.delivered += 1
      }
    } catch (error) {
      const attempts = Number(outbox.attempts || 0)
      const retry = attempts < MAX_DELIVERY_ATTEMPTS
      await setStatus(pool, outbox.id, 'failed', {
        errorCode: cleanText(error?.code || error?.message || 'LIVE_NOTIFICATION_FAILED', 80),
        retry,
        retryDelaySeconds: retryDelaySeconds(attempts),
      }).catch(() => {})
      result.failed += 1
    }
  }
  return result
}

module.exports = {
  LIVE_FOLLOWER_NOTIFICATIONS_FLAG,
  claimPendingLiveFollowerNotifications,
  didLiveStart,
  enqueueLiveStartedFollowerNotifications,
  ensureLiveFollowerNotificationTables,
  liveFollowerNotificationsEnabled,
  notificationForOutbox,
  processPendingLiveFollowerNotifications,
  recipientMayReceiveLiveNotification,
}
