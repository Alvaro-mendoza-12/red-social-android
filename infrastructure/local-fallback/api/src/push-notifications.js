const { collectionMessageRecipients } = require('./collection-membership-policy')
const fs = require('fs')
const { notificationMessageScope, withCurrentMessage, deletionData } = require('./message-deletion-policy')
const { cert, getApps, initializeApp } = require('firebase-admin/app')
const { getMessaging } = require('firebase-admin/messaging')
const { stableKey } = require('./request-security')
const { safeErrorLogDetails } = require('./request-observability')
const { limitMessagePush } = require('./message-push-limiter')

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
  if (notification.type === 'conversation_read') return require('./conversation-read-sync').readControlData(notification, extraData) || {}
  if (notification.type === 'message_deleted') {
    const scope = notificationMessageScope(notification, extraData)
    if (!scope || !notification.recipient_id) return {}
    // Cancellation is an allowlist, independent of old notification content.
    return deletionData(scope, { channel_id: extraData.channel_id, created_at: extraData.message_created_at, updated_at: extraData.deleted_at }, notification.recipient_id)
  }
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

  if (data.content_type === 'birthday_card') data.body = '🎂 Te envió una tarjeta de cumpleaños'

  const socialActions = {
    friend_request_accepted: 'aceptó tu solicitud de amistad',
    friend_request: 'te envió una solicitud de amistad',
    follow: 'empezó a seguirte',
  }
  const action = socialActions[type]
  if (action && (!String(data.body || '').trim() || String(data.body).trim().toLowerCase() === actorName.trim().toLowerCase())) {
    data.body = `${actorName} ${action}`
    data.content = data.body
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
  const { isRetiredNotification } = require('./retired-notifications')
  if (isRetiredNotification(notification) || isRetiredNotification({ extraData })) {
    return { sent: 0, failed: 0, retired: true }
  }
  const digestDeferred = extraData?.digest_deferred === true ||
    String(extraData?.digest_deferred || '').trim().toLowerCase() === 'true'
  if (digestDeferred) {
    return { sent: 0, failed: 0, deferred: true }
  }
  const scope = notificationMessageScope(notification, extraData)
  if (scope !== undefined) {
    if (!scope || !notification?.recipient_id) return { sent: 0, failed: 0, suppressed: true }
    const cancellation = notification.type === 'message_deleted'
    // Re-read permissions, payload and tombstones only after this item reaches
    // the head of the queue. Waiting recipients must not hold DB connections.
    return limitMessagePush(() => withCurrentMessage(pool, scope, async (current, client) => {
      if (!cancellation && ['group','community'].includes(scope.kind) &&
          !(await collectionMessageRecipients(client, scope.kind, scope.scopeId, current, [notification.recipient_id])).length)
        return { sent: 0, failed: 0, suppressed: true }
      const reaction = notification.type === 'message_reaction'
      if (reaction) {
        const parties=await client.query(`SELECT id::text,supabase_user_id::text FROM fallback_users
          WHERE lower(id::text)=ANY($1::text[]) OR lower(COALESCE(supabase_user_id::text,''))=ANY($1::text[])`, [[notification.recipient_id,notification.actor_id].map(value=>String(value||'').toLowerCase())])
        const aliases=value=>parties.rows.filter(row=>[row.id,row.supabase_user_id].some(id=>String(id||'').toLowerCase()===String(value||'').toLowerCase())).flatMap(row=>[row.id,row.supabase_user_id]).filter(Boolean)
        if (!require('./message-reaction-notifications').reactionNotice({actorId:notification.actor_id,actorIds:aliases(notification.actor_id),
          ownerId:notification.recipient_id,ownerIds:aliases(notification.recipient_id),emoji:extraData.reaction_emoji,
          reactions:current.reactions,deleted:current.deleted_for_all,deletedBy:Array.isArray(current.deleted_by)?current.deleted_by:[]}))
          return {sent:0,failed:0,suppressed:true}
      }
      const persisted = cancellation || reaction ? notification : { ...notification, content: current.content || '' }
      const authoritativeData = cancellation ? deletionData(scope, current, notification.recipient_id) : reaction ? {
        ...extraData, content: notification.content || '', body: notification.content || '', content_type: 'reaction',
        media_url: '', thumbnail_url: '',
      } : {
        ...extraData, content: current.content || '', body: current.content || '', content_type: current.content_type || 'text',
        media_url: current.media_url || '', thumbnail_url: current.thumbnail_url || '', media_size: String(current.media_size || 0),
        created_at: current.created_at instanceof Date ? current.created_at.toISOString() : String(current.created_at || ''),
      }
      return sendCurrentPush(client, persisted, authoritativeData)
    }, { allowDeleted: cancellation, actorId: cancellation ? undefined : reactionOwner(notification) }))
  }
  return sendCurrentPush(pool, notification, extraData)
}

function reactionOwner(notification) { return notification.type === 'message_reaction' ? notification.recipient_id : notification.actor_id }

async function sendCurrentPush(pool, notification, extraData) {
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
     SELECT fcm.token
       FROM fallback_fcm_tokens fcm
      WHERE lower(fcm.user_id) IN (SELECT id FROM recipient_ids WHERE id <> '')
        AND (fcm.auth_session_id IS NULL OR EXISTS (
          SELECT 1 FROM fallback_auth_sessions session
           WHERE session.id=fcm.auth_session_id AND session.revoked_at IS NULL
             AND session.expires_at>NOW()
             AND lower(session.user_id::text) IN (SELECT id FROM recipient_ids WHERE id <> '')
        ))
      ORDER BY fcm.updated_at DESC`,
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
