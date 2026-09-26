'use strict'

function communityMusicObject(value) {
  const raw = String(value || '').trim()
  try {
    const url = new URL(raw, 'https://storage.invalid')
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) return null
    // Match the original path before URL's dot-segment normalization. Decode
    // once per segment: an encoded separator must not select a different key.
    const match = raw.split(/[?#]/, 1)[0].match(
      /^(?:https?:\/\/[^/]+)?\/storage\/v1\/object\/(?:public|sign)\/community-music\/(.+)$/,
    )
    if (!match) return null
    const segments = match[1].split('/').map(decodeURIComponent)
    if (segments.some((part) => !part || part === '.' || part === '..' || /[\\/\u0000-\u001f\u007f]/.test(part))) return null
    return segments.join('/')
  } catch {
    return null
  }
}

/**
 * Compare a client reference with a track already authorized by catalog ID.
 * This is NOT storage authorization. Callers must use the catalog's URL for
 * rendering; a changed host/token is never a client-controlled fetch target.
 */
function sameCommunityMusicReference(requestedUrl, catalogUrl) {
  const requested = communityMusicObject(requestedUrl)
  const catalog = communityMusicObject(catalogUrl)
  if (requested || catalog) return requested !== null && requested === catalog
  // Legacy external catalog assets have no local object identity. They must
  // match exactly, including query parameters; do not generalize host rewriting.
  try {
    const url = new URL(String(catalogUrl || ''))
    return url.protocol === 'https:' && !url.username && !url.password &&
      requestedUrl === catalogUrl
  } catch {
    return false
  }
}

module.exports = { sameCommunityMusicReference }
