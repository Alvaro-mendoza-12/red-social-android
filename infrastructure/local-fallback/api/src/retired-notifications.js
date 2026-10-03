'use strict'

function isRetiredNotification(row) {
  let extra = row?.extra_data || row?.extraData || row?.data || {}
  if (typeof extra === 'string') {
    try { extra = JSON.parse(extra) } catch { extra = {} }
  }
  return [row?.type, row?.notification_type, row?.notificationType,
    extra?.notification_type, extra?.notificationType]
    .some(value => String(value || '').trim().toLowerCase() === 'view_moment')
}

module.exports = { isRetiredNotification }
