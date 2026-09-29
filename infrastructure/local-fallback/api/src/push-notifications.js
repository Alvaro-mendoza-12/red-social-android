const fs = require('fs')
const { cert, getApps, initializeApp } = require('firebase-admin/app')
const { getMessaging } = require('firebase-admin/messaging')
const { stableKey } = require('./request-security')
const { safeErrorLogDetails } = require('./request-observability')

const serviceAccountFile = String(
  process.env.FIREBASE_SERVICE_ACCOUNT_FILE || '/run/secrets/firebase-service-account.json',
).trim()

let firebaseApp = null
let initializationError = null

function initializeFirebase() {
  if (firebaseApp || initializationError) return firebaseApp
  try {
    if (!serviceAccountFile || !fs.existsSync(serviceAccountFile)) {
      throw new Error(`Firebase service account not found at ${serviceAccountFile || '(empty path)'}`)
    }
    const serviceAccount = JSON.parse(fs.readFileSync(serviceAccountFile, 'utf8'))
    firebaseApp = getApps()[0] || initializeApp({ credential: cert(serviceAccount) })
    return firebaseApp
  } catch (error) {
    initializationError = error
    console.error('[push] Firebase disabled', safeErrorLogDetails(error))
    return null
  }
}

function pushNotificationsStatus() {
  const app = initializeFirebase()
  return {
    configured: Boolean(app),
    provider: 'firebase-cloud-messaging',
    errorCode: app ? null : String(initializationError?.code || 'FIREBASE_NOT_INITIALIZED')
      .replace(/[^a-z0-9_.-]/gi, '_')
      .slice(0, 80),
  }
}

function stringValue(value) {
  if (value === null || value === undefined) return null
  if (typeof value === 'string') return value.slice(0, 350)
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return null
}

function buildData(notification, extraData = {}) {
  const type = stringValue(notification.type) || 'activity'
  const targetId = stringValue(notification.target_id) || ''
  const targetType = stringValue(notification.target_type || extraData.target_type) || ''
  const actorId = stringValue(notification.actor_id) || ''
  const actorName = stringValue(notification.actor_name) || 'Conéctate'
  const content = stringValue(notification.content) || ''
  const data = {
    type,
    notification_id: stringValue(notification.id) || '',
    target_id: targetId,
    target_type: targetType,
    sender_id: actorId,
    actor_id: actorId,
    sender_name: actorName,
    sender_avatar: stringValue(notification.actor_avatar_url) || '',
    content,
    body: content,
  }

  for (const [key, value] of Object.entries(extraData || {})) {
    const serialized = stringValue(value)
    if (serialized !== null) data[key] = serialized
  }

  // Account targeting must always come from the persisted, authenticated
  // notification. Never allow caller-supplied extra data to redirect a push
  // to a different signed-in account on a multi-account device.
  data.recipient_id = stringValue(notification.recipient_id) || ''

  const contextId = data.call_context_id || data.context_id || targetId
  if (targetType === 'group' || data.is_group === 'true') {
    data.group_id ||= contextId
    data.is_group = 'true'
  } else if (targetType === 'community' || data.is_community === 'true') {
    data.community_id ||= contextId
    data.is_community = 'true'
  } else if (targetType === 'chat' || targetType === 'dm' || type === 'new_message') {
    data.chat_id ||= contextId
  }
  if (type.startsWith('call_')) data.call_id ||= targetId
  return data
}

function isInvalidTokenError(code = '') {
  return code === 'messaging/registration-token-not-registered' ||
    code === 'messaging/invalid-registration-token'
}

async function sendPushForNotification(pool, notification, extraData = {}) {
  const digestDeferred = extraData?.digest_deferred === true ||
    String(extraData?.digest_deferred || '').trim().toLowerCase() === 'true'
  if (digestDeferred) {
    return { sent: 0, failed: 0, deferred: true }
  }
  const app = initializeFirebase()
  if (!app || !notification?.recipient_id) return { sent: 0, failed: 0, disabled: true }

  const { rows } = await pool.query(
    `WITH recipient_ids AS (
       SELECT lower($1::text) AS id
       UNION
       SELECT lower(id::text)
         FROM fallback_users
        WHERE lower(id::text) = lower($1::text)
           OR lower(COALESCE(supabase_user_id::text, '')) = lower($1::text)
       UNION
       SELECT lower(supabase_user_id::text)
         FROM fallback_users
        WHERE supabase_user_id IS NOT NULL
          AND (
            lower(id::text) = lower($1::text)
            OR lower(supabase_user_id::text) = lower($1::text)
          )
     )
     SELECT token
       FROM fallback_fcm_tokens
      WHERE lower(user_id) IN (SELECT id FROM recipient_ids WHERE id <> '')
      ORDER BY updated_at DESC`,
    [notification.recipient_id],
  )
  const tokens = [...new Set(rows.map((row) => String(row.token || '').trim()).filter(Boolean))]
  if (tokens.length === 0) return { sent: 0, failed: 0 }

  const data = buildData(notification, extraData)
  const isCall = String(notification.type || '').startsWith('call_')
  let sent = 0
  let failed = 0
  const invalidTokens = []
  const failureCodes = new Map()

  for (let offset = 0; offset < tokens.length; offset += 500) {
    const batch = tokens.slice(offset, offset + 500)
    const response = await getMessaging(app).sendEachForMulticast({
      tokens: batch,
      data,
      android: {
        priority: 'high',
        ttl: isCall ? 45_000 : 24 * 60 * 60 * 1000,
        directBootOk: true,
      },
      apns: {
        headers: {
          'apns-priority': '10',
          'apns-expiration': String(Math.floor(Date.now() / 1000) + (isCall ? 45 : 86_400)),
        },
        payload: { aps: { 'content-available': 1 } },
      },
    })
    sent += response.successCount
    failed += response.failureCount
    response.responses.forEach((result, index) => {
      if (!result.success) {
        const code = String(result.error?.code || 'unknown')
        failureCodes.set(code, (failureCodes.get(code) || 0) + 1)
      }
      if (!result.success && isInvalidTokenError(result.error?.code)) invalidTokens.push(batch[index])
    })
  }

  if (invalidTokens.length > 0) {
    await pool.query('DELETE FROM fallback_fcm_tokens WHERE token = ANY($1::text[])', [invalidTokens])
  }
  console.log(`[push] type=${notification.type} recipientRef=${stableKey(notification.recipient_id)} sent=${sent} failed=${failed} errorCodes=${JSON.stringify(Object.fromEntries(failureCodes))}`)
  return { sent, failed, removedInvalidTokens: invalidTokens.length }
}

module.exports = {
  buildData,
  pushNotificationsStatus,
  sendPushForNotification,
}
