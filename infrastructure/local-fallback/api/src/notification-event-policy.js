'use strict'

// These events are created by their authorized transactions, never by a public alert insert.
const SERVER_MANAGED_TYPES = new Set([
  'system_announcement', 'message_deleted', 'account_suspended', 'account_enabled',
  'conversation_read', 'message_reaction',
  'message', 'new_message', 'chat_message', 'group_message', 'community_message',
  'text', 'image', 'video', 'audio', 'sticker', 'file', 'document', 'location', 'contact',
])

function notificationObject(value) {
  if (typeof value === 'string') {
    try { value = JSON.parse(value) } catch { return null }
  }
  return value && typeof value === 'object' && !Array.isArray(value) ? value : null
}

function isServerManagedNotification(row) {
  // Inspect every accepted spelling: a harmless alias must not hide a reserved
  // value in the other alias that a different client/compatibility route reads.
  const types = [row, row?.extra_data, row?.extraData, row?.data]
    .map(notificationObject).filter(Boolean)
    .flatMap(value => [value.type, value.notification_type, value.notificationType])
    .map(value => String(value || '').trim().toLowerCase())
  if (types.some(value => SERVER_MANAGED_TYPES.has(value))) return true

  return false
}

module.exports = { isServerManagedNotification }
