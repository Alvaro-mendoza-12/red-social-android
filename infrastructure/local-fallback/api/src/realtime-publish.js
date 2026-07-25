const DEFAULT_URL = 'http://realtime-gateway:3002'
const { canonicalizeStorageUrls } = require('./storage-url-canonicalization')

async function publishRealtimeEvent(channel, payload) {
  const base = String(process.env.REALTIME_INTERNAL_URL || DEFAULT_URL).replace(/\/+$/, '')
  const cleanChannel = String(channel || '').trim()
  if (!cleanChannel) return false

  try {
    const response = await fetch(`${base}/internal/publish`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        channel: cleanChannel,
        payload: canonicalizeStorageUrls(payload || {}, process.env.PUBLIC_BASE_URL),
      }),
    })
    return response.ok
  } catch (error) {
    console.warn('[realtime-publish]', cleanChannel, error?.message || error)
    return false
  }
}

async function publishChatMessageEvent({ chatId, message, recipientIds = [], action = 'created' }) {
  const normalizedChatId = String(chatId || '').trim()
  if (!normalizedChatId) return

  const envelope = {
    event: 'message',
    action,
    chatId: normalizedChatId,
    message: message || {},
  }

  const recipients = Array.isArray(recipientIds) ? recipientIds : []
  await Promise.all([
    publishRealtimeEvent(`chat:${normalizedChatId}`, envelope),
    ...recipients
      .map((id) => String(id || '').trim())
      .filter(Boolean)
      .map((userId) =>
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
  publishChatMessageEvent,
  publishChallengeLiveEvent,
}
