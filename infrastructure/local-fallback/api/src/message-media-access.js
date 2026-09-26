'use strict'

function normalizedId(value) {
  return String(value || '').trim().toLowerCase()
}

function enabled(value) {
  return value === true || value === 1 || value === '1' || normalizedId(value) === 'true'
}

/** A conversation tombstone can be displayed, but must not authorize a new copy. */
function mayReuseMessageMedia(message, viewerIds, nowMs = Date.now()) {
  if (!message || !viewerIds?.size) return false
  if (enabled(message.deleted_for_all) || enabled(message.deletedForAll) ||
      enabled(message.is_deleted) || enabled(message.deleted)) return false
  const type = normalizedId(message.content_type || message.contentType)
  if (type.startsWith('view_once') || enabled(message.is_view_once) || enabled(message.viewOnce)) return false
  let deletedBy = message.deleted_by ?? message.deletedBy ?? []
  if (typeof deletedBy === 'string') {
    try { deletedBy = JSON.parse(deletedBy) } catch { return false }
  }
  if (!Array.isArray(deletedBy)) return false
  const hiddenFor = new Set(deletedBy.map(normalizedId))
  if ([...viewerIds].some((id) => hiddenFor.has(normalizedId(id)))) return false
  const expiry = message.expires_at || message.expiresAt
  if (expiry) {
    const expiresAt = new Date(expiry).getTime()
    if (!Number.isFinite(expiresAt) || expiresAt <= nowMs) return false
  }
  return true
}

module.exports = { mayReuseMessageMedia }
