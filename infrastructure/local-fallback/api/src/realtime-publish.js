const { collectionMessageRecipients } = require('./collection-membership-policy')
const { redactDeletedMessage, messageScope, withCurrentMessage } = require('./message-deletion-policy')
const DEFAULT_URL = 'http://realtime-gateway:3002'
const { canonicalizeStorageUrls } = require('./storage-url-canonicalization')
const { stableKey } = require('./request-security')
const { safeErrorLogDetails } = require('./request-observability')

async function publishRealtimeEvent(channel, payload, options = {}) {
  const base = String(process.env.REALTIME_INTERNAL_URL || DEFAULT_URL).replace(/\/+$/, '')
  const cleanChannel = String(channel || '').trim()
  if (!cleanChannel) return false

  try {
    const internalSecret = String(process.env.REALTIME_INTERNAL_SECRET || '').trim()
    const excludeSessionId = String(options?.excludeSessionId || '').trim()
    const correlationId = String(options?.correlationId || '').trim()
    const traceparent = String(options?.traceparent || '').trim()
    const response = await fetch(`${base}/internal/publish`, {
      method: 'POST',
      signal: AbortSignal.timeout(5_000),
      headers: {
        'Content-Type': 'application/json',
        ...(internalSecret ? { Authorization: `Bearer ${internalSecret}` } : {}),
        ...(correlationId ? { 'x-correlation-id': correlationId } : {}),
        ...(traceparent ? { traceparent } : {}),
      },
      body: JSON.stringify({
        channel: cleanChannel,
        payload: canonicalizeStorageUrls(payload || {}, process.env.PUBLIC_BASE_URL),
        ...(excludeSessionId ? { excludeSessionId } : {}),
      }),
    })
    return response.ok
  } catch (error) {
    console.warn('[realtime-publish]', {
      channelRef: stableKey(cleanChannel),
      ...safeErrorLogDetails(error),
    })
    return false
  }
}

async function publishRealtimeEvents(events) {
  const list = (Array.isArray(events) ? events : [])
    .map((item) => {
      const channel = String(item?.channel || '').trim()
      if (!channel) return null
      const excludeSessionId = String(item?.excludeSessionId || '').trim()
      return {
        channel,
        payload: canonicalizeStorageUrls(item?.payload || {}, process.env.PUBLIC_BASE_URL),
        ...(excludeSessionId ? { excludeSessionId } : {}),
      }
    })
    .filter(Boolean)
  if (list.length === 0) return true
  if (list.length === 1) {
    return publishRealtimeEvent(list[0].channel, list[0].payload, {
      excludeSessionId: list[0].excludeSessionId,
    })
  }

  const base = String(process.env.REALTIME_INTERNAL_URL || DEFAULT_URL).replace(/\/+$/, '')
  const internalSecret = String(process.env.REALTIME_INTERNAL_SECRET || '').trim()
  const chunkSize = 250
  let allOk = true
  for (let offset = 0; offset < list.length; offset += chunkSize) {
    const chunk = list.slice(offset, offset + chunkSize)
    try {
      const response = await fetch(`${base}/internal/publish-batch`, {
        method: 'POST',
        signal: AbortSignal.timeout(5_000),
        headers: {
          'Content-Type': 'application/json',
          ...(internalSecret ? { Authorization: `Bearer ${internalSecret}` } : {}),
        },
        body: JSON.stringify({ events: chunk }),
      })
      if (response.ok) continue
      if (response.status === 404 || response.status === 405) {
        const results = await Promise.all(
          chunk.map((item) =>
            publishRealtimeEvent(item.channel, item.payload, {
              excludeSessionId: item.excludeSessionId,
            }),
          ),
        )
        if (results.some((ok) => !ok)) allOk = false
      } else {
        allOk = false
      }
    } catch (error) {
      console.warn('[realtime-publish-batch]', {
        batchSize: chunk.length,
        ...safeErrorLogDetails(error),
      })
      allOk = false
    }
  }
  return allOk
}

async function publishChatMessageEvent({ pool, chatId, message, recipientIds = [], action = 'created', privateCollection = false }) {
  if (pool && ['created', 'updated'].includes(action) && message?.id) {
    const scope = messageScope({ chatId, message })
    return withCurrentMessage(pool, scope, async (current, db) => {
      const isCollection = ['group','community'].includes(scope?.kind)
      const allowed = isCollection ? await collectionMessageRecipients(db, scope.kind, scope.scopeId, current, recipientIds) : recipientIds
      return publishChatMessageEvent({ chatId, message: { ...message, ...current }, recipientIds: allowed, action, privateCollection: isCollection })
    })
  }
  const normalizedChatId = String(chatId || '').trim()
  if (!normalizedChatId) return

  const envelope = {
    event: 'message',
    action,
    chatId: normalizedChatId,
    message: redactDeletedMessage(message) || {},
  }

  const recipients = Array.isArray(recipientIds)
    ? [...new Set(recipientIds.map((id) => String(id || '').trim()).filter(Boolean))]
    : []
  const chatPayload = privateCollection
    ? { event: 'collection_changed', action, chatId: normalizedChatId }
    : envelope

  if (recipients.length > 5) {
    await publishRealtimeEvents([
      { channel: `chat:${normalizedChatId}`, payload: chatPayload },
      ...recipients.map((userId) => ({
        channel: `user:${userId}`,
        payload: { ...envelope, scope: 'inbox' },
      })),
    ])
    return
  }

  await Promise.all([
    publishRealtimeEvent(`chat:${normalizedChatId}`, chatPayload),
    ...recipients.map((userId) =>
      publishRealtimeEvent(`user:${userId}`, {
        ...envelope,
        scope: 'inbox',
      }),
    ),
  ])
}

async function publishChallengeLiveEvent(payload) {
  await publishRealtimeEvent('challenge:live', {
    event: 'challenge',
    ...(payload || {}),
  })
}

module.exports = {
  publishRealtimeEvent,
  publishRealtimeEvents,
  publishChatMessageEvent,
  publishChallengeLiveEvent,
}
