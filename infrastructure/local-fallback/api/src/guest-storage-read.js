'use strict'

const jwt = require('jsonwebtoken')
const { GUEST_AUDIENCE } = require('./guest-mode-routes')

/** This credential can authorize public storage reads, never a member API. */
function verifiedGuestStorageIdentity(req, {
  secret = process.env.JWT_SECRET,
  issuer = process.env.SERVICE_NAME || 'conectate-local-fallback',
  now = Date.now(),
} = {}) {
  const header = req.headers?.authorization
  if (typeof header !== 'string' || header.length > 8192 || !secret) return null
  const match = header.match(/^Bearer ([A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)$/i)
  if (!match) return null
  try {
    const claims = jwt.verify(match[1], secret, {
      issuer, audience: GUEST_AUDIENCE, algorithms: ['HS256'], clockTimestamp: Math.floor(now / 1000),
    })
    if (claims.role !== 'guest' || !/^guest_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(claims.sub)
      || !Number.isFinite(claims.exp)) return null
    return { id: claims.sub }
  } catch { return null }
}

async function guestPublicContentAllows(db, reference, object, origins, now = Date.now()) {
  if (!['post', 'video', 'moment', 'profile'].includes(reference?.access_kind)) return false
  const row = reference.row || {}
  const sensitive = row.is_sensitive ?? row.isSensitive
  if (sensitive != null && ![false, 'false', 0, '0', 'no'].includes(sensitive)) return false
  // A guest cannot prove a followers/selected/excluded-person audience.
  if (reference.access_kind === 'moment') {
    const type = String(row.visibility_type || 'public').trim().toLowerCase()
    const audience = String(row.visibility || 'all').trim().toLowerCase()
    if (type !== 'public' || !['all', 'public'].includes(audience)) return false
  }
  // Empty aliases grant no ownership, friendship, follows or membership rights.
  // Current publication, canonical creator, moderation and object ownership
  // are checked by the same gate used for normal accounts.
  return require('./storage-content-read').contentReferenceAllows(db, reference, object, origins, [], now)
}

module.exports = { verifiedGuestStorageIdentity, guestPublicContentAllows }
