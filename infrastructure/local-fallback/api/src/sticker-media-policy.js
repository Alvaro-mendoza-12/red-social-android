'use strict'

const idOf = (value) => String(value || '').trim().toLowerCase()

function stickerRowDeleted(row) {
  if (!row || typeof row !== 'object') return true
  return Boolean(row.deleted_at) || ['is_deleted', 'deleted', 'deleted_for_all'].some((field) =>
    [true, 1, '1', 'true'].includes(row[field]),
  )
}

function stickerPackReadable(pack, viewerIds) {
  if (stickerRowDeleted(pack) || !viewerIds?.size) return false
  return pack.is_public === true || ['owner_id', 'user_id', 'creator_id'].some((field) =>
    viewerIds.has(idOf(pack[field])),
  )
}

/** Compare storage identity, not the temporary tunnel hostname or an expired signature. */
function stickerStorageKey(value) {
  try {
    const url = new URL(String(value || '').trim())
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) return ''
    const match = url.pathname.match(/^\/storage\/v1\/object\/(?:public|sign)\/([^/]+)\/(.+)$/)
    if (!match) return ''
    const parts = [match[1], ...match[2].split('/')].map(decodeURIComponent)
    if (parts.some((part) => !part || part === '.' || part === '..' || /[\/\\\u0000-\u001f]/.test(part))) return ''
    return parts.join('/')
  } catch {
    return ''
  }
}

function sameStickerMedia(first, second) {
  const key = stickerStorageKey(first)
  if (key) return key === stickerStorageKey(second)
  // Preserve existing externally hosted catalog stickers, but never equate distinct URLs.
  try {
    const url = new URL(String(first || '').trim())
    return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password &&
      String(first).trim() === String(second || '').trim()
  } catch {
    return false
  }
}

function catalogAllowsStickerMedia({ items, packs, viewerIds, mediaUrl, stickerId = null }) {
  if (!viewerIds?.size || !mediaUrl) return false
  const ids = new Set([...viewerIds].map(idOf).filter(Boolean))
  const allowedPacks = new Set(packs
    .filter((pack) => stickerPackReadable(pack, ids))
    .map((pack) => idOf(pack.id)).filter(Boolean))
  return items.some((item) => !stickerRowDeleted(item) &&
    allowedPacks.has(idOf(item.pack_id)) &&
    (stickerId == null || idOf(item.sticker_id || item.id) === idOf(stickerId)) &&
    sameStickerMedia(item.media_url || item.url, mediaUrl))
}

module.exports = { catalogAllowsStickerMedia, stickerPackReadable, stickerRowDeleted, stickerStorageKey }
