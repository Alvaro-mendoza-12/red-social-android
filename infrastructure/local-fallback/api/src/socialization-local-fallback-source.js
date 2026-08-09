'use strict'

/**
 * PostgreSQL adapter for optional Socializar cards.
 *
 * The adapter deliberately projects only public presentation fields and
 * authoritative eligibility booleans. It never reads messages, precise
 * location, age, gender, e-mail or phone data. Missing safety tables and any
 * database error fail closed.
 */

const REQUIRED_TABLES = Object.freeze([
  'fallback_users',
  'fallback_profile_overrides',
  'fallback_profile_moderation',
  'fallback_deleted_identities',
  'fallback_user_blocks',
  'fallback_collection_rooms',
  'fallback_challenges_master',
  'fallback_active_challenges',
  'fallback_live_streams',
])

const SUPPORTED_TYPES = new Set(['person', 'community', 'challenge', 'live'])
const MAX_LIMIT = 32

const TABLE_AVAILABILITY_SQL = `
  SELECT required.table_name
  FROM unnest($1::text[]) AS required(table_name)
  WHERE to_regclass(required.table_name) IS NULL
`

const PUBLIC_ACCOUNT_SQL = `
  SELECT
    account.id::text AS local_id,
    NULLIF(account.supabase_user_id::text, '') AS external_id,
    COALESCE(NULLIF(account.supabase_user_id::text, ''), account.id::text) AS public_id,
    COALESCE(
      NULLIF(profile.profile->>'display_name', ''),
      NULLIF(profile.profile->>'displayName', ''),
      NULLIF(account.display_name, ''),
      'Usuario'
    ) AS display_name,
    COALESCE(
      NULLIF(profile.profile->>'avatar_url', ''),
      NULLIF(profile.profile->>'avatarUrl', ''),
      NULLIF(account.avatar_url, '')
    ) AS avatar_url,
    lower(COALESCE(
      NULLIF(profile.profile->>'profile_visibility', ''),
      NULLIF(profile.profile->>'profileVisibility', ''),
      'everyone'
    )) IN ('everyone', 'public') AS is_public,
    lower(COALESCE(
      NULLIF(profile.profile->>'is_private', ''),
      NULLIF(profile.profile->>'isPrivate', ''),
      'false'
    )) IN ('true', '1', 'yes') AS is_private,
    lower(COALESCE(
      NULLIF(profile.profile->>'break_mode', ''),
      NULLIF(profile.profile->>'breakMode', ''),
      'off'
    )) AS break_mode,
    EXISTS (
      SELECT 1 FROM fallback_profile_moderation moderation
      WHERE lower(moderation.user_id) IN (
        lower(account.id::text),
        lower(COALESCE(account.supabase_user_id::text, ''))
      )
        AND moderation.is_suspended = TRUE
        AND (
          COALESCE(moderation.suspended_until, moderation.suspension_end_date) IS NULL
          OR COALESCE(moderation.suspended_until, moderation.suspension_end_date) > NOW()
        )
    ) AS is_suspended,
    EXISTS (
      SELECT 1 FROM fallback_deleted_identities deleted
      WHERE lower(deleted.user_id) IN (
        lower(account.id::text),
        lower(COALESCE(account.supabase_user_id::text, ''))
      )
    ) AS is_deleted
  FROM fallback_users account
  LEFT JOIN fallback_profile_overrides profile ON profile.user_id = account.id
`

const PEOPLE_SQL = `
  ${PUBLIC_ACCOUNT_SQL}
  WHERE NOT (
    lower(account.id::text) = ANY($1::text[])
    OR lower(COALESCE(account.supabase_user_id::text, '')) = ANY($1::text[])
  )
  ORDER BY account.updated_at DESC NULLS LAST, account.id DESC
  LIMIT $2
`

const COMMUNITY_SQL = `
  SELECT id::text AS target_id, creator_id AS subject_id, name AS title,
         COALESCE(NULLIF(category, ''), NULLIF(description, '')) AS subtitle,
         image AS image_url
  FROM fallback_collection_rooms
  WHERE room_kind = 'community' AND is_public = TRUE AND is_deleted = FALSE
  ORDER BY updated_at DESC, id DESC
  LIMIT $1
`

const CHALLENGE_SQL = `
  SELECT active.id::text AS target_id,
         COALESCE(NULLIF(master.creator_id, ''), active.id::text) AS subject_id,
         master.title,
         NULLIF(master.description, '') AS subtitle,
         active.expires_at
  FROM fallback_active_challenges active
  JOIN fallback_challenges_master master ON master.id = active.challenge_id
  WHERE lower(active.status) = 'active' AND active.expires_at > NOW()
  ORDER BY active.created_at DESC, active.id DESC
  LIMIT $1
`

const LIVE_SQL = `
  SELECT id::text AS target_id, host_user_id AS subject_id,
         COALESCE(NULLIF(title, ''), NULLIF(host_name, ''), 'Directo') AS title,
         NULLIF(host_name, '') AS subtitle, host_avatar_url AS image_url
  FROM fallback_live_streams
  WHERE is_active = TRUE
  ORDER BY started_at DESC NULLS LAST, created_at DESC, id DESC
  LIMIT $1
`

function normalizeId(value, maxLength = 180) {
  const text = String(value == null ? '' : value).trim()
  return text && text.length <= maxLength ? text : null
}

function identityAliases(identity) {
  const values = identity?.ids instanceof Set ? Array.from(identity.ids) : []
  const result = new Set(values.map((value) => normalizeId(value)?.toLowerCase()).filter(Boolean))
  const display = normalizeId(identity?.displayUserId)?.toLowerCase()
  if (display) result.add(display)
  return Array.from(result).slice(0, 64)
}

function httpsUrl(value) {
  const text = normalizeId(value, 2048)
  if (!text) return null
  try {
    const url = new URL(text)
    return url.protocol === 'https:' ? url.toString() : null
  } catch {
    return null
  }
}

function cleanText(value, maxLength) {
  const text = String(value == null ? '' : value).replace(/[\u0000-\u001F\u007F]/g, ' ').trim()
  if (!text || text.length > maxLength) return null
  if (/\b[^\s@]+@[^\s@]+\.[^\s@]+\b/.test(text)) return null
  if (/(?:\+?\d[\s().-]*){7,}\d/.test(text)) return null
  return text
}

async function tablesAvailable(pool) {
  try {
    const { rows } = await pool.query(TABLE_AVAILABILITY_SQL, [REQUIRED_TABLES])
    return rows.length === 0
  } catch {
    return false
  }
}

function publicCandidate(type, row) {
  const targetId = normalizeId(row.target_id ?? row.public_id)
  const subjectId = normalizeId(row.subject_id ?? row.public_id)
  const title = cleanText(row.title ?? row.display_name, 96)
  if (!targetId || !subjectId || !title) return null
  const subtitle = cleanText(row.subtitle, 180)
  const imageUrl = httpsUrl(row.image_url ?? row.avatar_url)
  const expiresAt = row.expires_at ? new Date(row.expires_at).toISOString() : null
  return {
    type,
    targetId,
    subjectId,
    title,
    ...(subtitle ? { subtitle } : {}),
    ...(imageUrl ? { imageUrl } : {}),
    ...(expiresAt ? { expiresAt } : {}),
  }
}

function splitLimit(limit, typeCount) {
  return Math.max(1, Math.min(MAX_LIMIT, Math.ceil(limit / Math.max(1, typeCount)) + 2))
}

async function loadCandidates(pool, input) {
  const aliases = identityAliases(input?.identity)
  const allowedTypes = Array.isArray(input?.allowedTypes)
    ? input.allowedTypes.filter((type) => SUPPORTED_TYPES.has(type))
    : []
  const limit = Math.max(1, Math.min(MAX_LIMIT, Number(input?.limit) || 1))
  if (aliases.length === 0 || allowedTypes.length === 0 || !(await tablesAvailable(pool))) {
    return { items: [], nextCursor: null }
  }

  const eachLimit = splitLimit(limit, allowedTypes.length)
  const jobs = allowedTypes.map(async (type) => {
    if (type === 'person') {
      const { rows } = await pool.query(PEOPLE_SQL, [aliases, eachLimit])
      return rows
        .filter((row) => row.is_public === true && row.is_private !== true && row.break_mode !== 'hidden' && row.is_suspended !== true && row.is_deleted !== true)
        .map((row) => publicCandidate(type, row))
    }
    const sql = type === 'community' ? COMMUNITY_SQL : type === 'challenge' ? CHALLENGE_SQL : LIVE_SQL
    const { rows } = await pool.query(sql, [eachLimit])
    return rows.map((row) => publicCandidate(type, row))
  })

  try {
    const groups = await Promise.all(jobs)
    const items = []
    for (let index = 0; items.length < limit; index += 1) {
      let added = false
      for (const group of groups) {
        const item = group[index]
        if (!item) continue
        items.push(item)
        added = true
        if (items.length >= limit) break
      }
      if (!added) break
    }
    return { items, nextCursor: null }
  } catch {
    return { items: [], nextCursor: null }
  }
}

async function accountDecision(pool, viewerAliases, subjectId) {
  const subject = normalizeId(subjectId)?.toLowerCase()
  if (!subject) return null
  const { rows } = await pool.query(
    `${PUBLIC_ACCOUNT_SQL}
     WHERE lower(account.id::text) = $1
        OR lower(COALESCE(account.supabase_user_id::text, '')) = $1
     LIMIT 1`,
    [subject],
  )
  const account = rows[0]
  if (!account) return null
  const candidateAliases = [account.local_id, account.external_id, account.public_id]
    .map((value) => normalizeId(value)?.toLowerCase())
    .filter(Boolean)
  const edges = await pool.query(
    `SELECT lower(blocker_id) AS blocker_id, lower(blocked_id) AS blocked_id
     FROM fallback_user_blocks
     WHERE (lower(blocker_id) = ANY($1::text[]) AND lower(blocked_id) = ANY($2::text[]))
        OR (lower(blocker_id) = ANY($2::text[]) AND lower(blocked_id) = ANY($1::text[]))
     LIMIT 2`,
    [viewerAliases, candidateAliases],
  )
  return {
    account,
    viewerBlockedCandidate: edges.rows.some((edge) => viewerAliases.includes(edge.blocker_id)),
    candidateBlockedViewer: edges.rows.some((edge) => candidateAliases.includes(edge.blocker_id)),
  }
}

async function resourceOwner(pool, type, targetId) {
  if (type === 'person') return { exists: true, subjectId: targetId, isPublic: true, isDeleted: false }
  if (type === 'community') {
    const { rows } = await pool.query(
      `SELECT creator_id AS subject_id, is_public, is_deleted
       FROM fallback_collection_rooms WHERE id::text = $1 AND room_kind = 'community' LIMIT 1`,
      [targetId],
    )
    const row = rows[0]
    return row ? { exists: true, subjectId: row.subject_id, isPublic: row.is_public === true, isDeleted: row.is_deleted === true } : null
  }
  if (type === 'live') {
    const { rows } = await pool.query(
      `SELECT host_user_id AS subject_id, is_active FROM fallback_live_streams WHERE id::text = $1 LIMIT 1`,
      [targetId],
    )
    const row = rows[0]
    return row ? { exists: true, subjectId: row.subject_id, isPublic: row.is_active === true, isDeleted: row.is_active !== true } : null
  }
  const { rows } = await pool.query(
    `SELECT COALESCE(NULLIF(master.creator_id, ''), active.id::text) AS subject_id,
            lower(active.status) = 'active' AND active.expires_at > NOW() AS is_active,
            master.creator_id IS NULL OR master.creator_id = '' AS system_owned
     FROM fallback_active_challenges active
     JOIN fallback_challenges_master master ON master.id = active.challenge_id
     WHERE active.id::text = $1 LIMIT 1`,
    [targetId],
  )
  const row = rows[0]
  return row ? {
    exists: true,
    subjectId: row.subject_id,
    isPublic: row.is_active === true,
    isDeleted: row.is_active !== true,
    systemOwned: row.system_owned === true,
  } : null
}

async function evaluateCandidateSafety(pool, { viewerIdentity, candidate }) {
  const denied = {
    isPublic: false,
    isDeleted: true,
    isHidden: true,
    isSuspended: true,
    viewerBlockedCandidate: false,
    candidateBlockedViewer: false,
  }
  const viewerAliases = identityAliases(viewerIdentity)
  const type = String(candidate?.type || '').toLowerCase()
  const targetId = normalizeId(candidate?.targetId)
  const claimedSubject = normalizeId(candidate?.subjectId)?.toLowerCase()
  if (!SUPPORTED_TYPES.has(type) || !targetId || !claimedSubject || viewerAliases.length === 0 || !(await tablesAvailable(pool))) return denied

  try {
    const resource = await resourceOwner(pool, type, targetId)
    if (!resource || normalizeId(resource.subjectId)?.toLowerCase() !== claimedSubject) return denied
    if (type === 'challenge' && resource.systemOwned) {
      return {
        isPublic: resource.isPublic === true,
        isDeleted: resource.isDeleted === true,
        isHidden: false,
        isSuspended: false,
        viewerBlockedCandidate: false,
        candidateBlockedViewer: false,
      }
    }
    const decision = await accountDecision(pool, viewerAliases, claimedSubject)
    if (!decision) return denied
    return {
      isPublic: resource.isPublic === true && decision.account.is_public === true && decision.account.is_private !== true,
      isDeleted: resource.isDeleted === true || decision.account.is_deleted === true,
      isHidden: decision.account.break_mode === 'hidden',
      isSuspended: decision.account.is_suspended === true,
      viewerBlockedCandidate: decision.viewerBlockedCandidate,
      candidateBlockedViewer: decision.candidateBlockedViewer,
    }
  } catch {
    return denied
  }
}

function createSocializationLocalFallbackSource({ pool } = {}) {
  if (!pool || typeof pool.query !== 'function') throw new TypeError('pool.query is required')
  return {
    loadCandidates: (input) => loadCandidates(pool, input),
    evaluateCandidateSafety: (input) => evaluateCandidateSafety(pool, input),
  }
}

module.exports = {
  REQUIRED_TABLES,
  createSocializationLocalFallbackSource,
  evaluateCandidateSafety,
  identityAliases,
  loadCandidates,
  publicCandidate,
  tablesAvailable,
}
