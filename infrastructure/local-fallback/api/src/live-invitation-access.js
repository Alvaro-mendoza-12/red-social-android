'use strict'

const { blockedProfileIds } = require('./blocked-profile-ids')
const { findActiveSuspension } = require('./account-status')

const normalize = value => String(value || '').trim().toLowerCase()

/** Resolve only exact account identifiers, never names or email addresses. */
async function liveAccountIdentity(db, rawId) {
  const id = normalize(rawId)
  if (!id || id.length > 160) return null
  const { rows } = await db.query(`SELECT id::text, supabase_user_id::text
    FROM fallback_users WHERE lower(id::text)=$1
       OR lower(COALESCE(supabase_user_id::text,''))=$1 LIMIT 2`, [id])
  // An ambiguous historical alias must not choose a different person by order.
  if (rows.length !== 1) return null
  const user = rows[0]
  const values = [...new Set([normalize(user.id), normalize(user.supabase_user_id)].filter(Boolean))]
  return { ids: new Set(values), values, localUserId: normalize(user.id),
    displayUserId: normalize(user.supabase_user_id || user.id) }
}

async function hasLiveHostBlock(db, hostId, identity) {
  const blocked = await blockedProfileIds(db, identity)
  if (!blocked.size) return false
  if (blocked.has(normalize(hostId))) return true
  const host = await liveAccountIdentity(db, hostId)
  return !host || host.values.some(id => blocked.has(id))
}

async function invitableLiveAccount(db, { hostIdentity, liveId, targetId, isBanned }) {
  const target = await liveAccountIdentity(db, targetId)
  if (!target || target.values.some(id => hostIdentity.ids.has(id))) return null
  const blocked = await blockedProfileIds(db, hostIdentity)
  if (target.values.some(id => blocked.has(id))) return null
  if (await findActiveSuspension(db, target.localUserId)) return null
  if (await isBanned(db, liveId, target)) return null
  return target
}

module.exports = { hasLiveHostBlock, invitableLiveAccount, liveAccountIdentity }
