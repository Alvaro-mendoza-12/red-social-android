'use strict'

const id = value => String(value ?? '').trim().toLowerCase()
const ids = values => new Set(Array.from(values || []).map(id).filter(Boolean))
const aliasesOf = profile => ids(['id', 'user_id', 'userId', 'profile_id', 'profileId',
  'supabase_user_id', 'supabaseUserId', 'email', 'username', 'handle'].map(key => profile?.[key]))
const overlaps = (a, b) => Array.from(a).some(key => b.has(key))

// Author visibility is checked before pagination and enrichment, including in
// the legacy feed used when recommendations are unavailable. Display names
// never identify an author. Missing profiles and safety-query errors deny access.
function publicCreatorVisibility({ profiles = [], viewerIds = [], followingIds = [], deniedIds = [] } = {}) {
  const viewer = ids(viewerIds), followed = ids(followingIds), denied = ids(deniedIds)
  const index = new Map()
  for (const profile of profiles) for (const alias of aliasesOf(profile)) index.set(alias, profile)
  return rawOwnerId => {
    const ownerId = id(rawOwnerId), profile = index.get(ownerId)
    if (!profile) return false
    const aliases = aliasesOf(profile)
    if (overlaps(aliases, denied)) return false
    if (overlaps(aliases, viewer)) return true
    if (id(profile.break_mode ?? profile.breakMode ?? 'off') === 'hidden') return false
    const visibility = id(profile.profile_visibility ?? profile.profileVisibility ?? 'everyone')
    const isFollowing = overlaps(aliases, followed)
    if (!['everyone', 'public', 'followers', 'connections'].includes(visibility)) return false
    if (['followers', 'connections'].includes(visibility) && !isFollowing) return false
    const isPrivate = profile.is_private ?? profile.isPrivate ?? false
    if ((isPrivate === true || id(isPrivate) === 'true') && !isFollowing) return false
    return true
  }
}

async function deniedPublicCreatorIds(pool, { profiles = [], ownerIds = [], viewerIds = [] } = {}) {
  const candidates = ids([...ownerIds, ...profiles.flatMap(p => Array.from(aliasesOf(p)))])
  const viewer = Array.from(ids(viewerIds))
  if (!candidates.size) return new Set()
  const { rows } = await pool.query(`
    WITH candidate_ids AS (SELECT unnest($2::text[]) AS id)
    SELECT candidate.id FROM candidate_ids candidate
    WHERE EXISTS (
      SELECT 1 FROM fallback_user_blocks edge
      WHERE (lower(edge.blocker_id)=ANY($1::text[]) AND lower(edge.blocked_id)=candidate.id)
         OR (lower(edge.blocked_id)=ANY($1::text[]) AND lower(edge.blocker_id)=candidate.id)
    ) OR EXISTS (
      SELECT 1 FROM fallback_profile_moderation moderation
      WHERE lower(moderation.user_id)=candidate.id AND moderation.is_suspended=TRUE
        AND (COALESCE(moderation.suspended_until,moderation.suspension_end_date) IS NULL
          OR COALESCE(moderation.suspended_until,moderation.suspension_end_date)>NOW())
    ) OR EXISTS (
      SELECT 1 FROM fallback_deleted_identities deleted WHERE lower(deleted.user_id)=candidate.id
    )`, [viewer, Array.from(candidates)])
  return ids(rows.map(row => row.id))
}

module.exports = { aliasesOf, deniedPublicCreatorIds, publicCreatorVisibility }
