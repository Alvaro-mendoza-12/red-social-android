'use strict'

/** Aliases of one account may have independent imported and local cursor rows. */
function latestConversationReadAt(previous, candidate) {
  const at = value => value instanceof Date ? value.getTime() : Date.parse(String(value || '')) || 0
  return at(candidate) > at(previous) ? candidate : previous
}

function readControlData(notification, extra = {}) {
  const kind = notification?.target_type, id = String(notification?.target_id || '').trim().toLowerCase()
  const account = String(notification?.recipient_id || '').trim().toLowerCase()
  if (!['chat','group','community'].includes(kind) || !id || !account) return null
  let ids
  try { ids = typeof extra.message_ids === 'string' ? JSON.parse(extra.message_ids) : extra.message_ids || [] } catch { return null }
  if (!Array.isArray(ids) || ids.length > 32 || ids.some(value => typeof value !== 'string' || !/^[0-9a-f-]{36}$/i.test(value))) return null
  const at = extra.read_through ? Date.parse(extra.read_through) : 0
  if (extra.read_through && !Number.isFinite(at)) return null
  const through = at ? new Date(at).toISOString() : ''
  if (!ids.length && !through) return null
  return { type: 'conversation_read', recipient_id: account, target_type: kind, conversation_id: id, message_ids: JSON.stringify(ids), read_through: through, read_at: new Date().toISOString() }
}

function createConversationReadPublisher({ pool, publish = require('./realtime-publish').publishRealtimeEvent, push = require('./push-notifications').sendPushForNotification }) {
  return async function publishConversationRead({ identity, kind, id, messageIds = [], through = '' }) {
    if (!identity?.displayUserId || !['chat','group','community'].includes(kind) || !id) return
    const ids = [...new Set(messageIds)].filter(value => /^[0-9a-f-]{36}$/i.test(value))
    if (!ids.length && !through) return
    // Private account channels only. Do not announce a reader's state to a room.
    for (let offset = 0; offset < Math.max(1, ids.length); offset += 32) {
      const batch = ids.slice(offset, offset + 32)
      await Promise.allSettled([...identity.ids].map(accountId => publish(`user:${accountId}`, {
        event: 'conversation_read', accountId, kind, conversationId: id, messageIds: batch, through,
      })))
      await push(pool, { type: 'conversation_read', recipient_id: identity.displayUserId, target_type: kind, target_id: id }, { message_ids: batch, read_through: through })
    }
  }
}
module.exports = { readControlData, createConversationReadPublisher, latestConversationReadAt }
