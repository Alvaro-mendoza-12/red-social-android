'use strict'

// These events are created by their authorized transactions, never by a public alert insert.
const SERVER_MANAGED_TYPES = new Set([
  'system_announcement', 'message_deleted', 'account_suspended', 'account_enabled',
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

  // Older installed clients infer suspension from these legacy message notices.
  // Normal chat messages use their own message endpoints, not this alert insert.
  if (!types.includes('message')) return false
  const content = String(row?.content || '').trim().toLowerCase()
  return ['cuenta fue suspendida', 'cuenta ha sido suspendida',
    'cuenta ha sido habilitada', 'cuenta fue habilitada', 'cuenta habilitada nuevamente']
    .some(fragment => content.includes(fragment))
}

module.exports = { isServerManagedNotification }
