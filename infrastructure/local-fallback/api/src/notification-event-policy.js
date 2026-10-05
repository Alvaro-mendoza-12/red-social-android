'use strict'

// Public notification endpoints must not impersonate the transactional admin outbox.
function isServerManagedNotification(row) {
  let extra = row?.extra_data || row?.extraData || row?.data || {}
  if (typeof extra === 'string') {
    try { extra = JSON.parse(extra) } catch { extra = {} }
  }
  return [row?.type, row?.notification_type, row?.notificationType,
    extra?.notification_type, extra?.notificationType]
    .some(value => String(value || '').trim().toLowerCase() === 'system_announcement')
}

module.exports = { isServerManagedNotification }
