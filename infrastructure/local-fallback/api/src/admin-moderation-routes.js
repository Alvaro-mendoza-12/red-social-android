const { purgeFriendRequestIdentity } = require('./friend-request-state')
const crypto = require('crypto')
const { purgeImportedRows } = require('./imported-mutation-capabilities')
const fs = require('fs')
const path = require('path')
const { publishRealtimeEvent } = require('./realtime-publish')
const { stableKey } = require('./request-security')
const { safeErrorLogDetails } = require('./request-observability')
const {
  ADMIN_ACCESS_RANK,
  ADMIN_CAPABILITY,
  adminAccessContract,
  hasAdminCapability,
  normalizeAdminAccessLevel,
} = require('./admin-capabilities')

const DEFAULT_STORAGE_ROOT = path.resolve(
  process.env.STORAGE_ROOT || path.join(process.cwd(), 'storage-data'),
)
const HARD_DELETE_CONFIRMATION_PREFIX = 'ELIMINAR '
const HARD_DELETE_LOCK_TABLES = [
  'fallback_active_challenges',
  'fallback_challenge_submissions',
  'fallback_challenge_votes',
  'fallback_challenges_master',
  'fallback_chat_hidden',
  'fallback_chat_participants',
  'fallback_chats',
  'fallback_collection_invites',
  'fallback_collection_members',
  'fallback_collection_message_receipts',
  'fallback_collection_read_states',
  'fallback_collection_rooms',
  'fallback_community_channels',
  'fallback_community_event_attendees',
  'fallback_community_events',
  'fallback_community_member_profiles',
  'fallback_compat_rows',
  'fallback_fcm_tokens',
  'fallback_follows',
  'fallback_friend_legacy_barriers',
  'fallback_friend_legacy_requests',
  'fallback_friends',
  'fallback_live_comments',
  'fallback_live_streams',
  'fallback_message_pin_events',
  'fallback_message_pins',
  'fallback_messages',
  'fallback_moderation_appeals',
  'fallback_moderation_decisions',
  'fallback_moderation_events',
  'fallback_moment_views',
  'fallback_moments',
  'fallback_nicknames',
  'fallback_notification_digest_preferences',
  'fallback_notifications',
  'fallback_password_reset_tokens',
  'fallback_post_comment_reactions',
  'fallback_post_comments',
  'fallback_post_reactions',
  'fallback_posts',
  'fallback_profile_login_emails',
  'fallback_profile_moderation',
  'fallback_profile_overrides',
  'fallback_proximity_presence',
  'fallback_random_bottle_availability',
  'fallback_random_chat_bottles',
  'fallback_random_chat_messages',
  'fallback_random_chat_queue',
  'fallback_random_chat_sessions',
  'fallback_reports',
  'fallback_room_messages',
  'fallback_scheduled_message_jobs',
  'fallback_saved_collections',
  'fallback_saved_messages',
  'fallback_saved_posts',
  'fallback_shares',
  'fallback_storage_objects',
  'fallback_user_blocks',
  'fallback_users',
  'fallback_video_comment_reactions',
  'fallback_video_comment_reports',
  'fallback_video_comments',
  'fallback_video_collection_items',
  'fallback_video_collections',
  'fallback_video_favorites',
  'fallback_video_playback_history',
  'fallback_video_watch_later',
  'fallback_video_likes',
  'fallback_video_metrics',
  'fallback_video_reports',
  'fallback_video_views',
  'fallback_videos',
  'fallback_view_once_consumptions',
  'fallback_voice_room_participants',
  'fallback_voice_rooms',
  'fallback_web_login_pairings',
  'fallback_web_sessions',
]
const IMPORT_IDENTITY_FIELDS = [
  'actor_id',
  'approved_by_user_id',
  'author_id',
  'blocked_id',
  'blocked_user_id',
  'blocker_id',
  'created_by',
  'creator_id',
  'current_recipient_id',
  'follower_id',
  'following_id',
  'forwarded_from_user_id',
  'from_user_id',
  'host_user_id',
  'member_id',
  'owner_id',
  'profile_id',
  'receiver_id',
  'recipient_id',
  'reported_user_id',
  'reporter_id',
  'requester_id',
  'sender_id',
  'session_user_id',
  'target_user_id',
  'user_a',
  'user_b',
  'user_id',
  'userid',
  'voter_id',
]
const IMPORT_IDENTITY_ARRAY_FIELDS = [
  'attempted_recipient_ids',
  'excluded_ids',
  'member_ids',
  'participant_ids',
  'participants',
  'user_ids',
]
const IMPORT_RELATED_FIELDS = [
  ...IMPORT_IDENTITY_FIELDS,
  'active_challenge_id',
  'challenge_id',
  'chat_id',
  'comment_id',
  'community_id',
  'group_id',
  'id',
  'live_id',
  'message_id',
  'moment_id',
  'post_id',
  'room_id',
  'submission_id',
  'video_id',
  'voice_room_id',
]

// Email addresses are mutable and registration does not prove ownership in the
// local fallback. Authorization therefore comes from an immutable ADMIN_USER_IDS
// entry or a server-side moderation row. The three product-owner accounts below
// are converted into moderation rows during schema bootstrap; route guards never
// need to trust their email on every request.
const DEFAULT_BOOTSTRAP_ADMIN_EMAILS = [
  'gamergamerff004@gmail.com',
  'alvaromendozamarin004@gmail.com',
  'gamergamerff017@gmail.com',
]
const BOOTSTRAP_ADMIN_EMAILS = Array.from(new Set([
  ...DEFAULT_BOOTSTRAP_ADMIN_EMAILS,
  ...String(process.env.ADMIN_EMAILS || '').split(','),
].map(normalizeId).filter(Boolean)))
const DEFAULT_ADMIN_EMAILS = []
const DESIGNATED_ADMIN_EMAILS = new Set(
  String(
    /^(1|true|yes|on)$/i.test(String(process.env.ALLOW_LEGACY_ADMIN_EMAIL_ADMINS || '').trim())
      ? (process.env.ADMIN_EMAILS || DEFAULT_ADMIN_EMAILS.join(','))
      : '',
  )
    .split(',')
    .map(normalizeId)
    .filter(Boolean),
)
const DESIGNATED_ADMIN_USER_IDS = new Set(
  String(process.env.ADMIN_USER_IDS || '')
    .split(',')
    .map(normalizeId)
    .filter(Boolean),
)
const ADMIN_BADGE_CATALOG = Object.freeze([
  { id: 'founder', name: 'Fundador', description: 'Miembro fundador de Conéctate', icon: '👑', color: '#F59E0B', type: 'manual', is_admin_badge: false },
  { id: 'creator', name: 'Creador', description: 'Creador destacado', icon: '🎬', color: '#8B5CF6', type: 'manual', is_admin_badge: false },
  { id: 'moderator', name: 'Moderador', description: 'Moderador de la comunidad', icon: '🛡️', color: '#0EA5E9', type: 'manual', is_admin_badge: false },
  { id: 'collaborator', name: 'Colaborador oficial', description: 'Colaborador oficial de Conéctate', icon: '🤝', color: '#22C55E', type: 'manual', is_admin_badge: false },
  { id: 'bug-hunter', name: 'Bug Hunter', description: 'Ayudó a mejorar la aplicación', icon: '🐛', color: '#06B6D4', type: 'manual', is_admin_badge: false },
])
const PROFILE_FRAME_CATALOG = Object.freeze([
  { id: 'gold', name: 'Dorado' },
  { id: 'neon', name: 'Neón' },
  { id: 'ruby', name: 'Rubí' },
  { id: 'ocean', name: 'Océano' },
])
const MODERATION_RULE_CATALOG = Object.freeze([
  {
    id: 'harassment_abuse',
    title: 'Acoso o abuso',
    description: 'Ataques, amenazas, hostigamiento o conducta abusiva contra otras personas.',
  },
  {
    id: 'fraud_scam',
    title: 'Fraude o estafa',
    description: 'Engaño, suplantación o solicitud fraudulenta de dinero o datos.',
  },
  {
    id: 'hate_discrimination',
    title: 'Odio o discriminación',
    description: 'Contenido que ataca a personas por una característica protegida.',
  },
  {
    id: 'sexual_content',
    title: 'Contenido sexual no permitido',
    description: 'Contenido sexual que infringe las normas o involucra riesgo para menores.',
  },
  {
    id: 'violence_threats',
    title: 'Violencia o amenazas',
    description: 'Amenazas creíbles, promoción de violencia o contenido extremadamente gráfico.',
  },
  {
    id: 'spam_manipulation',
    title: 'Spam o manipulación',
    description: 'Actividad repetitiva, automatizada o diseñada para manipular la plataforma.',
  },
  {
    id: 'privacy_impersonation',
    title: 'Privacidad o suplantación',
    description: 'Publicación de datos privados, identidad falsa o uso indebido de otra identidad.',
  },
  {
    id: 'community_standards_other',
    title: 'Otra infracción de las normas',
    description: 'Incumplimiento documentado que no corresponde a otra categoría.',
  },
])
const ADMIN_BADGES_BY_ID = new Map(ADMIN_BADGE_CATALOG.map((badge) => [badge.id, badge]))
const PROFILE_FRAME_IDS = new Set(PROFILE_FRAME_CATALOG.map((frame) => frame.id))
const MODERATION_RULES_BY_ID = new Map(MODERATION_RULE_CATALOG.map((rule) => [rule.id, rule]))
function normalizeId(value) {
  return String(value || '').trim().toLowerCase()
}

function normalizeBadgeIds(value, catalog = ADMIN_BADGE_CATALOG) {
  if (!Array.isArray(value)) return null
  const badgesById = new Map(catalog.map((badge) => [normalizeId(badge?.id), badge]))
  const ids = Array.from(new Set(value.map(normalizeId).filter(Boolean)))
  if (ids.length > badgesById.size || ids.some((id) => !badgesById.has(id))) {
    return null
  }
  return ids
}

function normalizeProfileFrame(value) {
  const frame = normalizeId(value)
  if (!frame) return ''
  return PROFILE_FRAME_IDS.has(frame) ? frame : null
}

function isTestAccountTarget(target) {
  const email = normalizeId(target?.email)
  const source = normalizeId(target?.sync_source)
  return target?.is_test_account === true ||
    source === 'test' ||
    /(?:^|[+._-])test(?:[+._-]|@)/i.test(email) ||
    /@(example\.com|example\.test|test\.invalid)$/i.test(email)
}

function profileBadgesFromRow(row) {
  const raw = Array.isArray(row?.badges) ? row.badges : []
  return raw
    .map((entry) => {
      const id = normalizeId(typeof entry === 'string' ? entry : entry?.id)
      const catalogBadge = ADMIN_BADGES_BY_ID.get(id)
      if (catalogBadge) return catalogBadge
      if (!id || !entry || typeof entry !== 'object') return null
      const name = String(entry.name || '').trim().slice(0, 60)
      if (!name) return null
      const color = /^#[0-9a-f]{6}$/i.test(String(entry.color || '').trim())
        ? String(entry.color).trim().toUpperCase()
        : '#14B8A6'
      return {
        id,
        name,
        description: String(entry.description || '').trim().slice(0, 180),
        icon: String(entry.icon || '🏅').trim().slice(0, 16) || '🏅',
        color,
        type: 'manual',
        is_admin_badge: false,
        custom: true,
      }
    })
    .filter(Boolean)
}

function customAdminBadgeFromRow(row, options = {}) {
  const createdBy = normalizeId(row?.created_by)
  return {
    id: normalizeId(row?.id),
    name: String(row?.name || '').trim(),
    description: String(row?.description || '').trim(),
    icon: String(row?.icon || '🏅').trim() || '🏅',
    color: String(row?.color || '#14B8A6').trim().toUpperCase(),
    type: 'manual',
    is_admin_badge: false,
    custom: true,
    created_by: createdBy,
    can_delete: options.canWrite === true,
  }
}

async function adminBadgeCatalog(pool, options = {}) {
  const { rows } = await pool.query(
    `SELECT id, name, description, icon, color, created_by
     FROM fallback_admin_badges
     ORDER BY created_at ASC, id ASC`,
  )
  const catalog = new Map(ADMIN_BADGE_CATALOG.map((badge) => [badge.id, badge]))
  rows.map((row) => customAdminBadgeFromRow(row, options)).forEach((badge) => {
    if (badge.id && badge.name && !catalog.has(badge.id)) catalog.set(badge.id, badge)
  })
  return Array.from(catalog.values())
}

function adminUserFromRow(row) {
  const identityValues = [row?.local_id, row?.public_id].map(normalizeId).filter(Boolean)
  const isOwner = row?.admin_is_owner === true ||
    identityValues.some((value) => DESIGNATED_ADMIN_USER_IDS.has(value))
  const accessLevel = isOwner
    ? 'full'
    : normalizeAdminAccessLevel(row?.admin_access_level, row?.is_admin === true ? 'full' : 'none')
  return {
    id: normalizeId(row?.public_id || row?.local_id),
    local_id: normalizeId(row?.local_id),
    email: String(row?.email || '').trim(),
    display_name: String(row?.display_name || '').trim(),
    username: String(row?.username || '').trim(),
    avatar_url: String(row?.avatar_url || '').trim(),
    created_at: row?.created_at || null,
    last_login_at: row?.last_login_at || null,
    sync_source: String(row?.sync_source || 'local'),
    is_admin: accessLevel !== 'none' ||
      DESIGNATED_ADMIN_EMAILS.has(normalizeId(row?.email)) ||
      identityValues.some((value) => DESIGNATED_ADMIN_USER_IDS.has(value)),
    admin_access_level: accessLevel,
    admin_is_owner: isOwner,
    is_test_account: isTestAccountTarget(row),
    is_verified: row?.is_verified === true,
    is_suspended: row?.is_suspended === true,
    suspended_until: row?.suspended_until || null,
    suspension_reason: row?.suspension_reason || null,
    badges: profileBadgesFromRow(row),
    profile_frame: normalizeProfileFrame(row?.profile_frame) || '',
  }
}

const ADMIN_USER_SELECT = `
  SELECT
    u.id::text AS local_id,
    NULLIF(u.supabase_user_id::text, '') AS public_id,
    u.email,
    COALESCE(NULLIF(o.profile->>'display_name', ''), NULLIF(o.profile->>'full_name', ''), u.display_name, u.email) AS display_name,
    COALESCE(o.profile->>'username', '') AS username,
    COALESCE(NULLIF(o.profile->>'avatar_url', ''), u.avatar_url, '') AS avatar_url,
    u.created_at,
    u.last_login_at,
    u.sync_source,
    COALESCE(m.is_admin, FALSE) AS is_admin,
    COALESCE(m.admin_access_level, CASE WHEN m.is_admin THEN 'full' ELSE 'none' END) AS admin_access_level,
    COALESCE(m.admin_is_owner, FALSE) AS admin_is_owner,
    lower(COALESCE(o.profile->>'is_test_account', 'false')) IN ('true', '1') AS is_test_account,
    lower(COALESCE(o.profile->>'is_verified', 'false')) IN ('true', '1') AS is_verified,
    (COALESCE(m.is_suspended, FALSE) AND (m.suspended_until IS NULL OR m.suspended_until > NOW())) AS is_suspended,
    m.suspended_until,
    m.suspension_reason,
    CASE WHEN jsonb_typeof(o.profile->'badges') = 'array' THEN o.profile->'badges' ELSE '[]'::jsonb END AS badges,
    COALESCE(o.profile->>'profile_frame', '') AS profile_frame
  FROM fallback_users u
  LEFT JOIN fallback_profile_overrides o ON o.user_id = u.id
  LEFT JOIN LATERAL (
    SELECT moderation.*
    FROM fallback_profile_moderation moderation
    WHERE lower(moderation.user_id) = lower(u.id::text)
       OR lower(moderation.user_id) = lower(COALESCE(u.supabase_user_id::text, ''))
    ORDER BY moderation.is_admin DESC, moderation.updated_at DESC
    LIMIT 1
  ) m ON TRUE
`

async function findAdminTarget(pool, targetId, { forUpdate = false } = {}) {
  const id = normalizeId(targetId)
  if (!id) return null
  const { rows } = await pool.query(
    `${ADMIN_USER_SELECT}
     WHERE lower(u.id::text) = $1
        OR lower(COALESCE(u.supabase_user_id::text, '')) = $1
     LIMIT 1${forUpdate ? ' FOR UPDATE OF u' : ''}`,
    [id],
  )
  return rows[0] ? adminUserFromRow(rows[0]) : null
}

async function upsertAdminProfilePatch(pool, localUserId, patch) {
  await pool.query(
    `INSERT INTO fallback_profile_overrides(user_id, profile, updated_at)
     VALUES ($1::uuid, $2::jsonb, NOW())
     ON CONFLICT (user_id)
     DO UPDATE SET
       profile = fallback_profile_overrides.profile || EXCLUDED.profile,
       updated_at = NOW()`,
    [localUserId, JSON.stringify(patch)],
  )
}

function identityValuesOf(identity) {
  return Array.from(new Set([
    identity?.localUserId,
    identity?.preferredUserId,
    identity?.displayUserId,
    ...(identity?.values || []),
    ...Array.from(identity?.ids || []),
  ].map(normalizeId).filter(Boolean)))
}

function validUuidOrNull(value) {
  const id = normalizeId(value)
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(id)
    ? id
    : null
}

function quoteIdentifier(value) {
  const identifier = String(value || '')
  if (!/^[a-z_][a-z0-9_]*$/i.test(identifier)) {
    throw hardDeleteError('HARD_DELETE_SCHEMA_IDENTIFIER_INVALID', 500)
  }
  return `"${identifier.replace(/"/g, '""')}"`
}

function hardDeleteConfirmation(userId) {
  return `${HARD_DELETE_CONFIRMATION_PREFIX}${normalizeId(userId)}`
}

function isHardDeleteConfirmationValid(userId, confirmation) {
  const targetId = normalizeId(userId)
  return Boolean(targetId) && String(confirmation || '').trim() === hardDeleteConfirmation(targetId)
}

function hardDeleteError(code, status = 500, details = {}) {
  const error = new Error(code)
  error.code = code
  error.status = status
  Object.assign(error, details)
  return error
}

function textOf(row, keys, fallback = '') {
  for (const key of keys) {
    const value = row?.[key]
    if (value !== undefined && value !== null && String(value).trim() !== '') {
      return String(value).trim()
    }
  }
  return fallback
}

function boolOf(row, keys, fallback = false) {
  for (const key of keys) {
    const value = row?.[key]
    if (value === undefined || value === null) continue
    if (typeof value === 'boolean') return value
    const text = String(value).toLowerCase()
    if (text === 'true' || text === '1') return true
    if (text === 'false' || text === '0') return false
  }
  return fallback
}

function normalizeSuspensionDays(value) {
  const days = Number(value)
  if (!Number.isInteger(days) || days < 0 || days > 3650) return null
  return days
}

function suspensionWindow(days, now = Date.now()) {
  const normalizedDays = normalizeSuspensionDays(days)
  if (normalizedDays === null) return null
  return {
    days: normalizedDays,
    suspendedUntil: normalizedDays === 0
      ? null
      : new Date(now + normalizedDays * 86400000).toISOString(),
  }
}

function normalizeModerationRule(value) {
  const id = normalizeId(value) || 'community_standards_other'
  return MODERATION_RULES_BY_ID.get(id) || null
}

function normalizeModerationSeverity(value) {
  const severity = normalizeId(value)
  return ['low', 'medium', 'high', 'critical'].includes(severity) ? severity : 'high'
}

function normalizeModerationEvidence(value) {
  if (!Array.isArray(value)) return []
  const seen = new Set()
  return value
    .map((entry) => {
      const source = typeof entry === 'object' && entry !== null
        ? textOf(entry, ['source', 'type'], 'manual')
        : 'manual'
      const reference = typeof entry === 'object' && entry !== null
        ? textOf(entry, ['reference', 'content_ref', 'id'], '')
        : ''
      const label = typeof entry === 'object' && entry !== null
        ? textOf(entry, ['label', 'summary', 'description'], '')
        : String(entry || '').trim()
      return {
        source: source.slice(0, 40),
        reference: reference.slice(0, 160),
        label: label.slice(0, 240),
      }
    })
    .filter((entry) => {
      if (!entry.label) return false
      const fingerprint = `${entry.source}|${entry.reference}|${entry.label}`.toLowerCase()
      if (seen.has(fingerprint)) return false
      seen.add(fingerprint)
      return true
    })
    .slice(0, 12)
}

function moderationDecisionPayload(row, appeal = null) {
  if (!row) return null
  const evidence = Array.isArray(row.evidence) ? row.evidence : []
  return {
    id: String(row.id || ''),
    user_id: String(row.user_id || ''),
    action: String(row.action || 'suspension'),
    rule_code: String(row.rule_code || 'community_standards_other'),
    rule_title: String(row.rule_title || 'Otra infracción de las normas'),
    explanation: String(row.explanation || ''),
    evidence: evidence.map((entry) => ({
      source: String(entry?.source || 'manual'),
      label: String(entry?.label || ''),
    })).filter((entry) => entry.label),
    severity: String(row.severity || 'high'),
    status: String(row.status || 'active'),
    effective_until: row.effective_until || null,
    appeal_deadline: row.appeal_deadline || null,
    decided_at: row.decided_at || null,
    appeal: appeal
      ? {
        id: String(appeal.id || ''),
        status: String(appeal.status || 'pending'),
        statement: String(appeal.statement || ''),
        reviewer_note: appeal.reviewer_note || null,
        created_at: appeal.created_at || null,
        reviewed_at: appeal.reviewed_at || null,
      }
      : null,
  }
}

async function ensureAdminModerationTables(pool) {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_profile_moderation (
      user_id TEXT PRIMARY KEY,
      is_suspended BOOLEAN NOT NULL DEFAULT FALSE,
      suspended_until TIMESTAMPTZ,
      suspension_end_date TIMESTAMPTZ,
      suspension_reason TEXT,
      suspension_count INT NOT NULL DEFAULT 0,
      is_admin BOOLEAN NOT NULL DEFAULT FALSE,
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_profile_moderation_active
     ON fallback_profile_moderation(lower(user_id), is_suspended, suspended_until)`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_moderation_decisions (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      user_id TEXT NOT NULL,
      action TEXT NOT NULL DEFAULT 'suspension',
      rule_code TEXT NOT NULL,
      rule_title TEXT NOT NULL,
      explanation TEXT NOT NULL,
      evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
      severity TEXT NOT NULL DEFAULT 'high',
      status TEXT NOT NULL DEFAULT 'active',
      decided_by TEXT,
      decided_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      effective_until TIMESTAMPTZ,
      appeal_deadline TIMESTAMPTZ,
      reversed_at TIMESTAMPTZ,
      reversal_reason TEXT,
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_moderation_appeals (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      decision_id UUID NOT NULL REFERENCES fallback_moderation_decisions(id) ON DELETE CASCADE,
      user_id TEXT NOT NULL,
      statement TEXT NOT NULL,
      evidence_urls JSONB NOT NULL DEFAULT '[]'::jsonb,
      status TEXT NOT NULL DEFAULT 'pending',
      reviewer_id TEXT,
      reviewer_note TEXT,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      reviewed_at TIMESTAMPTZ
    )
  `)
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_moderation_decisions_user
     ON fallback_moderation_decisions(lower(user_id), decided_at DESC)`,
  )
  await pool.query(
    `CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_moderation_appeals_open
     ON fallback_moderation_appeals(decision_id)
     WHERE status IN ('pending', 'reviewing')`,
  )
  await pool.query(
    `CREATE INDEX IF NOT EXISTS idx_fallback_moderation_appeals_status
     ON fallback_moderation_appeals(status, created_at)`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_moderation_events (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      actor_id TEXT,
      context TEXT,
      content_ref TEXT,
      verdict TEXT,
      metadata_preview TEXT,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_video_reports (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      video_id TEXT,
      video_owner_id TEXT,
      video_owner_name TEXT,
      video_title TEXT,
      reporter_id TEXT,
      reporter_name TEXT,
      reason TEXT,
      details TEXT,
      status TEXT NOT NULL DEFAULT 'pendiente',
      warn_sent BOOLEAN NOT NULL DEFAULT FALSE,
      strike_applied BOOLEAN NOT NULL DEFAULT FALSE,
      action_note TEXT,
      reviewed_at TIMESTAMPTZ,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_admin_badges (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      description TEXT NOT NULL DEFAULT '',
      icon TEXT NOT NULL DEFAULT '🏅',
      color TEXT NOT NULL DEFAULT '#14B8A6',
      created_by TEXT,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  for (const statement of [
    `ALTER TABLE fallback_profile_moderation ADD COLUMN IF NOT EXISTS is_admin BOOLEAN NOT NULL DEFAULT FALSE`,
    `ALTER TABLE fallback_profile_moderation ADD COLUMN IF NOT EXISTS admin_access_level TEXT NOT NULL DEFAULT 'none'`,
    `ALTER TABLE fallback_profile_moderation ADD COLUMN IF NOT EXISTS admin_is_owner BOOLEAN NOT NULL DEFAULT FALSE`,
    `ALTER TABLE fallback_profile_moderation ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
    `ALTER TABLE fallback_profile_moderation ADD COLUMN IF NOT EXISTS decision_id UUID`,
    `ALTER TABLE fallback_video_reports ADD COLUMN IF NOT EXISTS warn_sent BOOLEAN NOT NULL DEFAULT FALSE`,
    `ALTER TABLE fallback_video_reports ADD COLUMN IF NOT EXISTS strike_applied BOOLEAN NOT NULL DEFAULT FALSE`,
    `ALTER TABLE fallback_video_reports ADD COLUMN IF NOT EXISTS action_note TEXT`,
    `ALTER TABLE fallback_video_reports ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMPTZ`,
    `ALTER TABLE fallback_video_reports ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
  ]) {
    await pool.query(statement)
  }
  if (BOOTSTRAP_ADMIN_EMAILS.length > 0) {
    await pool.query(
      `INSERT INTO fallback_profile_moderation(
         user_id, is_admin, admin_access_level, admin_is_owner, updated_at
       )
       SELECT u.id::text, TRUE, 'full', TRUE, NOW()
       FROM fallback_users u
       WHERE lower(trim(u.email)) = ANY($1::text[])
       ON CONFLICT (user_id) DO UPDATE SET
         is_admin = TRUE,
         admin_access_level = 'full',
         admin_is_owner = TRUE,
         updated_at = NOW()`,
      [BOOTSTRAP_ADMIN_EMAILS],
    )
  }
}

async function isAppAdmin(pool, identity) {
  const access = await appAdminAccess(pool, identity)
  return access.level !== 'none'
}

async function appAdminHasCapability(pool, identity, capability) {
  return hasAdminCapability(await appAdminAccess(pool, identity), capability)
}

async function appAdminAccess(pool, identity) {
  const identityValues = Array.from(new Set([
    identity?.localUserId,
    identity?.preferredUserId,
    ...(identity?.values || []),
    ...Array.from(identity?.ids || []),
  ].map(normalizeId).filter(Boolean)))
  if (identityValues.length === 0) return { level: 'none', isOwner: false }
  if (identityValues.some((value) => DESIGNATED_ADMIN_USER_IDS.has(value))) {
    return { level: 'full', isOwner: true }
  }
  const { rows } = await pool.query(
    `SELECT u.email,
            u.display_name,
            COALESCE(m.is_admin, FALSE) AS is_admin,
            COALESCE(m.admin_access_level, CASE WHEN m.is_admin THEN 'full' ELSE 'none' END) AS admin_access_level,
            COALESCE(m.admin_is_owner, FALSE) AS admin_is_owner
     FROM fallback_users u
     LEFT JOIN fallback_profile_moderation m
       ON lower(m.user_id) = lower(u.id::text)
       OR lower(m.user_id) = lower(COALESCE(u.supabase_user_id::text, ''))
     WHERE lower(u.id::text) = ANY($1::text[])
        OR lower(COALESCE(u.supabase_user_id::text, '')) = ANY($1::text[])
     ORDER BY m.is_admin DESC NULLS LAST
     LIMIT 1`,
    [identityValues],
  )
  return adminAccessFromStoredRow(rows[0], identityValues)
}

// Accept only database role columns and immutable IDs, never profile display names.
function adminAccessFromStoredRow(row, identityValues = []) {
  if (identityValues.some((value) => DESIGNATED_ADMIN_USER_IDS.has(normalizeId(value)))) {
    return { level: 'full', isOwner: true }
  }
  const email = String(row?.email || '').trim().toLowerCase()
  const isOwner = row?.admin_is_owner === true ||
    Boolean(email && DESIGNATED_ADMIN_EMAILS.has(email))
  if (isOwner) return { level: 'full', isOwner: true }
  const level = normalizeAdminAccessLevel(
    row?.admin_access_level,
    row?.is_admin === true ? 'full' : 'none',
  )
  return { level, isOwner: false }
}

async function recordModerationEvent(pool, actorId, payload) {
  await pool.query(
    `INSERT INTO fallback_moderation_events(actor_id, context, content_ref, verdict, metadata_preview)
     VALUES ($1, $2, $3, $4, $5)`,
    [
      actorId || null,
      textOf(payload, ['context'], 'admin'),
      textOf(payload, ['contentRef', 'content_ref'], ''),
      textOf(payload, ['verdict'], ''),
      textOf(payload, ['metadataPreview', 'metadata_preview'], ''),
    ],
  )
}

function hardDeleteTargetFingerprint(identityValues) {
  return `sha256:${crypto.createHash('sha256').update(identityValues.slice().sort().join('|')).digest('hex')}`
}

function isProtectedAdminTarget(target, identityValues) {
  return target?.is_admin === true ||
    DESIGNATED_ADMIN_EMAILS.has(normalizeId(target?.email)) ||
    identityValues.some((value) => DESIGNATED_ADMIN_USER_IDS.has(value))
}

async function lockHardDeleteTables(client) {
  const { rows } = await client.query(
    `SELECT table_name
     FROM information_schema.tables
     WHERE table_schema = 'public'
       AND table_name = ANY($1::text[])
     ORDER BY table_name`,
    [HARD_DELETE_LOCK_TABLES],
  )
  const existing = rows
    .map((row) => normalizeId(row.table_name))
    .filter((tableName) => HARD_DELETE_LOCK_TABLES.includes(tableName))
  if (existing.length > 0) {
    await client.query(
      `LOCK TABLE ${existing.map(quoteIdentifier).join(', ')} IN SHARE ROW EXCLUSIVE MODE`,
    )
  }
}

function storagePathForRow(row, storageRoot) {
  const root = path.resolve(storageRoot)
  const expectedPath = path.resolve(root, String(row?.bucket_id || ''), String(row?.name || ''))
  const filePath = path.resolve(String(row?.local_path || '').trim() || expectedPath)
  if (
    expectedPath === root ||
    !expectedPath.startsWith(`${root}${path.sep}`) ||
    filePath !== expectedPath
  ) {
    throw hardDeleteError('HARD_DELETE_STORAGE_PATH_OUTSIDE_ROOT', 500)
  }
  return filePath
}

async function stageStorageRows(rows, options) {
  const storageFs = options.storageFs
  const storageRoot = path.resolve(options.storageRoot)
  const state = options.state
  const seen = new Set()
  state.quarantineRoot = path.join(storageRoot, '.admin-hard-delete', state.deletionId)

  for (const [index, row] of (rows || []).entries()) {
    const objectPath = storagePathForRow(row, storageRoot)
    const variantDigest = crypto
      .createHash('sha256')
      .update(`${String(row?.bucket_id || '')}:${String(row?.name || '')}`)
      .digest('hex')
    const variantPath = path.join(storageRoot, '_variants', variantDigest.slice(0, 2), variantDigest)
    for (const [kind, sourcePath] of [['object', objectPath], ['variants', variantPath]]) {
      if (seen.has(sourcePath)) continue
      seen.add(sourcePath)
      const destinationPath = path.join(
        state.quarantineRoot,
        `${String(index).padStart(6, '0')}-${kind}-${crypto.createHash('sha256').update(sourcePath).digest('hex').slice(0, 20)}`,
      )
      try {
        await storageFs.mkdir(state.quarantineRoot, { recursive: true })
        await storageFs.rename(sourcePath, destinationPath)
        state.entries.push({ sourcePath, destinationPath })
      } catch (error) {
        if (error?.code === 'ENOENT') {
          state.alreadyMissing += 1
          continue
        }
        throw hardDeleteError('HARD_DELETE_STORAGE_STAGE_FAILED', 500, { cause: error })
      }
    }
  }
}

async function restoreStagedStorage(state, storageFs) {
  const failures = []
  for (const entry of state.entries.slice().reverse()) {
    try {
      await storageFs.mkdir(path.dirname(entry.sourcePath), { recursive: true })
      await storageFs.rename(entry.destinationPath, entry.sourcePath)
    } catch (error) {
      failures.push({ entry, error })
    }
  }
  if (state.quarantineRoot && failures.length === 0) {
    await storageFs.rm(state.quarantineRoot, { recursive: true, force: true }).catch(() => {})
  }
  return failures
}

async function finalizeStagedStorage(state, storageFs) {
  if (!state.quarantineRoot || state.entries.length === 0) return
  await storageFs.rm(state.quarantineRoot, { recursive: true, force: true })
}

function idsFrom(result) {
  return result.rows.map((row) => normalizeId(row.id)).filter(Boolean)
}

async function collectHardDeleteRelations(client, identityValues) {
  const postIds = idsFrom(await client.query(
    `SELECT id::text FROM fallback_posts WHERE lower(user_id) = ANY($1::text[])`,
    [identityValues],
  ))
  const videoIds = idsFrom(await client.query(
    `SELECT id::text FROM fallback_videos WHERE lower(user_id) = ANY($1::text[])`,
    [identityValues],
  ))
  const momentIds = idsFrom(await client.query(
    `SELECT id::text FROM fallback_moments WHERE lower(user_id) = ANY($1::text[])`,
    [identityValues],
  ))
  const liveIds = idsFrom(await client.query(
    `SELECT id::text FROM fallback_live_streams WHERE lower(host_user_id) = ANY($1::text[])`,
    [identityValues],
  ))
  const challengeIds = idsFrom(await client.query(
    `SELECT id::text FROM fallback_challenges_master WHERE lower(COALESCE(creator_id, '')) = ANY($1::text[])`,
    [identityValues],
  ))
  const submissionIds = idsFrom(await client.query(
    `SELECT id::text
     FROM fallback_challenge_submissions
     WHERE lower(user_id) = ANY($1::text[])
        OR lower(challenge_id::text) = ANY($2::text[])`,
    [identityValues, challengeIds],
  ))
  const chatIds = idsFrom(await client.query(
    `SELECT DISTINCT c.id::text AS id
     FROM fallback_chats c
     LEFT JOIN fallback_chat_participants p ON p.chat_id = c.id
     WHERE EXISTS (
       SELECT 1 FROM unnest(c.participants) participant
       WHERE lower(participant) = ANY($1::text[])
     ) OR lower(COALESCE(p.user_id, '')) = ANY($1::text[])`,
    [identityValues],
  ))
  const roomIds = idsFrom(await client.query(
    `SELECT id::text FROM fallback_collection_rooms
     WHERE lower(creator_id) = ANY($1::text[])`,
    [identityValues],
  ))
  const voiceRoomIds = idsFrom(await client.query(
    `SELECT id::text FROM fallback_voice_rooms
     WHERE lower(creator_id) = ANY($1::text[])`,
    [identityValues],
  ))
  const randomSessionIds = idsFrom(await client.query(
    `SELECT id::text FROM fallback_random_chat_sessions
     WHERE lower(user_a) = ANY($1::text[]) OR lower(user_b) = ANY($1::text[])`,
    [identityValues],
  ))
  const postCommentIds = idsFrom(await client.query(
    `SELECT id::text FROM fallback_post_comments
     WHERE lower(user_id) = ANY($1::text[]) OR lower(post_id) = ANY($2::text[])`,
    [identityValues, postIds],
  ))
  const videoCommentIds = idsFrom(await client.query(
    `SELECT id::text FROM fallback_video_comments
     WHERE lower(user_id) = ANY($1::text[]) OR lower(video_id) = ANY($2::text[])`,
    [identityValues, videoIds],
  ))
  return {
    challengeIds,
    chatIds,
    liveIds,
    momentIds,
    postCommentIds,
    postIds,
    randomSessionIds,
    roomIds,
    submissionIds,
    videoCommentIds,
    videoIds,
    voiceRoomIds,
  }
}

async function insertHardDeleteTombstones(client, identityValues, relations) {
  for (const userId of identityValues) {
    await client.query(
      `INSERT INTO fallback_deleted_identities(user_id, deleted_at)
       VALUES ($1, NOW())
       ON CONFLICT (user_id) DO UPDATE SET deleted_at = NOW()`,
      [userId],
    )
  }
  for (const videoId of relations.videoIds) {
    await client.query(
      `INSERT INTO fallback_video_deletions(video_id, user_id, deleted_at)
       VALUES ($1, $2, NOW())
       ON CONFLICT (video_id) DO UPDATE SET user_id = EXCLUDED.user_id, deleted_at = NOW()`,
      [videoId, identityValues[0]],
    )
  }
  for (const momentId of relations.momentIds) {
    await client.query(
      `INSERT INTO fallback_moment_deletions(moment_id, user_id, deleted_at)
       VALUES ($1, $2, NOW())
       ON CONFLICT (moment_id) DO UPDATE SET user_id = EXCLUDED.user_id, deleted_at = NOW()`,
      [momentId, identityValues[0]],
    )
  }
}

async function deleteCounted(client, counts, label, statement, params) {
  const result = await client.query(statement, params)
  const count = Number(result.rowCount) || 0
  counts[label] = (counts[label] || 0) + count
  return result
}

async function purgeFallbackUserData(client, localUserId, identityValues, relations) {
  const r = relations
  const counts = {}
  const markerRoomIds = [...r.chatIds, ...r.roomIds]
  const steps = [
    ['saved_posts', `DELETE FROM fallback_saved_posts WHERE lower(user_id) = ANY($1::text[]) OR lower(post_id) = ANY($2::text[])`, [identityValues, r.postIds]],
    ['saved_collections', `DELETE FROM fallback_saved_collections WHERE lower(user_id) = ANY($1::text[])`, [identityValues]],
    ['saved_messages', `DELETE FROM fallback_saved_messages WHERE lower(user_id) = ANY($1::text[])
      OR lower(room_id) = ANY($2::text[]) OR lower(message_id) IN
      (SELECT lower(id::text) FROM fallback_messages WHERE lower(sender_id) = ANY($1::text[])
       UNION SELECT lower(id::text) FROM fallback_room_messages WHERE lower(sender_id) = ANY($1::text[]))`,
    [identityValues, markerRoomIds]],
    ['message_pins', `DELETE FROM fallback_message_pins WHERE lower(pinned_by) = ANY($1::text[])
      OR lower(room_id) = ANY($2::text[]) OR lower(message_id) IN
      (SELECT lower(id::text) FROM fallback_messages WHERE lower(sender_id) = ANY($1::text[])
       UNION SELECT lower(id::text) FROM fallback_room_messages WHERE lower(sender_id) = ANY($1::text[]))`,
    [identityValues, markerRoomIds]],
    ['message_pin_events', `DELETE FROM fallback_message_pin_events WHERE lower(actor_id) = ANY($1::text[])
      OR lower(room_id) = ANY($2::text[]) OR lower(message_id) IN
      (SELECT lower(id::text) FROM fallback_messages WHERE lower(sender_id) = ANY($1::text[])
       UNION SELECT lower(id::text) FROM fallback_room_messages WHERE lower(sender_id) = ANY($1::text[]))`,
    [identityValues, markerRoomIds]],
    ['post_comment_reactions', `DELETE FROM fallback_post_comment_reactions WHERE lower(user_id) = ANY($1::text[]) OR lower(comment_id) = ANY($2::text[])`, [identityValues, r.postCommentIds]],
    ['post_comments', `DELETE FROM fallback_post_comments WHERE lower(user_id) = ANY($1::text[]) OR lower(post_id) = ANY($2::text[])`, [identityValues, r.postIds]],
    ['post_reactions', `DELETE FROM fallback_post_reactions WHERE lower(user_id) = ANY($1::text[]) OR lower(post_id) = ANY($2::text[])`, [identityValues, r.postIds]],
    ['shares', `DELETE FROM fallback_shares WHERE lower(user_id) = ANY($1::text[]) OR (content_type = 'post' AND lower(post_id) = ANY($2::text[])) OR (content_type = 'video' AND lower(post_id) = ANY($3::text[]))`, [identityValues, r.postIds, r.videoIds]],
    ['reports', `DELETE FROM fallback_reports WHERE lower(reporter_id) = ANY($1::text[]) OR lower(reported_user_id) = ANY($1::text[]) OR lower(COALESCE(content_id, '')) = ANY($2::text[])`, [identityValues, [...r.postIds, ...r.postCommentIds]]],
    ['posts', `DELETE FROM fallback_posts WHERE lower(id::text) = ANY($1::text[]) OR lower(user_id) = ANY($2::text[])`, [r.postIds, identityValues]],
    ['video_comment_reactions', `DELETE FROM fallback_video_comment_reactions WHERE lower(user_id) = ANY($1::text[]) OR lower(comment_id) = ANY($2::text[])`, [identityValues, r.videoCommentIds]],
    ['video_comment_reports', `DELETE FROM fallback_video_comment_reports WHERE lower(reporter_id) = ANY($1::text[]) OR lower(comment_id) = ANY($2::text[]) OR lower(video_id) = ANY($3::text[])`, [identityValues, r.videoCommentIds, r.videoIds]],
    ['video_comments', `DELETE FROM fallback_video_comments WHERE lower(user_id) = ANY($1::text[]) OR lower(video_id) = ANY($2::text[])`, [identityValues, r.videoIds]],
    ['video_likes', `DELETE FROM fallback_video_likes WHERE lower(user_id) = ANY($1::text[]) OR lower(video_id) = ANY($2::text[])`, [identityValues, r.videoIds]],
    ['video_favorites', `DELETE FROM fallback_video_favorites WHERE lower(user_id) = ANY($1::text[]) OR lower(video_id) = ANY($2::text[])`, [identityValues, r.videoIds]],
    ['video_collection_items', `DELETE FROM fallback_video_collection_items WHERE lower(video_id) = ANY($1::text[])`, [r.videoIds]],
    ['video_collections', `DELETE FROM fallback_video_collections WHERE lower(user_id) = ANY($1::text[])`, [identityValues]],
    ['video_views', `DELETE FROM fallback_video_views WHERE lower(user_id) = ANY($1::text[]) OR lower(video_id) = ANY($2::text[])`, [identityValues, r.videoIds]],
    ['video_playback_history', `DELETE FROM fallback_video_playback_history WHERE lower(user_id) = ANY($1::text[]) OR lower(video_id) = ANY($2::text[])`, [identityValues, r.videoIds]],
    ['video_watch_later', `DELETE FROM fallback_video_watch_later WHERE lower(user_id) = ANY($1::text[]) OR lower(video_id) = ANY($2::text[])`, [identityValues, r.videoIds]],
    ['video_reports', `DELETE FROM fallback_video_reports WHERE lower(COALESCE(reporter_id, '')) = ANY($1::text[]) OR lower(COALESCE(video_owner_id, '')) = ANY($1::text[]) OR lower(COALESCE(video_id, '')) = ANY($2::text[])`, [identityValues, r.videoIds]],
    ['video_metrics', `DELETE FROM fallback_video_metrics WHERE lower(video_id) = ANY($1::text[])`, [r.videoIds]],
    ['videos', `DELETE FROM fallback_videos WHERE lower(id::text) = ANY($1::text[]) OR lower(user_id) = ANY($2::text[])`, [r.videoIds, identityValues]],
    ['moment_views', `DELETE FROM fallback_moment_views WHERE lower(user_id) = ANY($1::text[]) OR lower(moment_id::text) = ANY($2::text[])`, [identityValues, r.momentIds]],
    ['moments', `DELETE FROM fallback_moments WHERE lower(id::text) = ANY($1::text[]) OR lower(user_id) = ANY($2::text[])`, [r.momentIds, identityValues]],
    ['challenge_votes', `DELETE FROM fallback_challenge_votes WHERE lower(voter_id) = ANY($1::text[]) OR lower(submission_id) = ANY($2::text[])`, [identityValues, r.submissionIds]],
    ['challenge_submissions', `DELETE FROM fallback_challenge_submissions WHERE lower(id::text) = ANY($1::text[]) OR lower(user_id) = ANY($2::text[])`, [r.submissionIds, identityValues]],
    ['active_challenges', `DELETE FROM fallback_active_challenges WHERE lower(challenge_id::text) = ANY($1::text[])`, [r.challengeIds]],
    ['challenges_master', `DELETE FROM fallback_challenges_master WHERE lower(id::text) = ANY($1::text[]) OR lower(COALESCE(creator_id, '')) = ANY($2::text[])`, [r.challengeIds, identityValues]],
    ['live_comments', `DELETE FROM fallback_live_comments WHERE lower(user_id) = ANY($1::text[]) OR lower(live_id::text) = ANY($2::text[])`, [identityValues, r.liveIds]],
    ['live_streams', `DELETE FROM fallback_live_streams WHERE lower(id::text) = ANY($1::text[]) OR lower(host_user_id) = ANY($2::text[])`, [r.liveIds, identityValues]],
    ['random_bottle_availability', `DELETE FROM fallback_random_bottle_availability WHERE lower(user_id) = ANY($1::text[])`, [identityValues]],
    ['random_chat_bottles', `DELETE FROM fallback_random_chat_bottles WHERE lower(sender_id) = ANY($1::text[]) OR lower(COALESCE(current_recipient_id, '')) = ANY($1::text[]) OR lower(COALESCE(session_id::text, '')) = ANY($2::text[])`, [identityValues, r.randomSessionIds]],
    ['random_chat_messages', `DELETE FROM fallback_random_chat_messages WHERE lower(sender_id) = ANY($1::text[]) OR lower(session_id::text) = ANY($2::text[])`, [identityValues, r.randomSessionIds]],
    ['random_chat_sessions', `DELETE FROM fallback_random_chat_sessions WHERE lower(id::text) = ANY($1::text[]) OR lower(user_a) = ANY($2::text[]) OR lower(user_b) = ANY($2::text[])`, [r.randomSessionIds, identityValues]],
    ['random_chat_queue', `DELETE FROM fallback_random_chat_queue WHERE lower(user_id) = ANY($1::text[])`, [identityValues]],
    ['messages', `DELETE FROM fallback_messages WHERE lower(sender_id) = ANY($1::text[]) OR lower(COALESCE(forwarded_from_user_id, '')) = ANY($1::text[])`, [identityValues]],
    ['chat_hidden', `DELETE FROM fallback_chat_hidden WHERE lower(user_id) = ANY($1::text[])`, [identityValues]],
    ['chat_participants', `DELETE FROM fallback_chat_participants WHERE lower(user_id) = ANY($1::text[])`, [identityValues]],
    ['chats_participant_reference', `UPDATE fallback_chats SET participants = ARRAY(SELECT participant FROM unnest(participants) participant WHERE lower(participant) <> ALL($1::text[])), updated_at = NOW() WHERE lower(id::text) = ANY($2::text[])`, [identityValues, r.chatIds]],
    ['collection_message_receipts', `DELETE FROM fallback_collection_message_receipts WHERE lower(room_id) = ANY($1::text[]) OR lower(user_id) = ANY($2::text[])`, [r.roomIds, identityValues]],
    ['room_messages', `DELETE FROM fallback_room_messages WHERE lower(room_id) = ANY($1::text[]) OR lower(sender_id) = ANY($2::text[]) OR lower(COALESCE(forwarded_from_user_id, '')) = ANY($2::text[])`, [r.roomIds, identityValues]],
    ['community_event_attendees', `DELETE FROM fallback_community_event_attendees WHERE lower(user_id) = ANY($1::text[]) OR event_id IN (SELECT id FROM fallback_community_events WHERE lower(community_id) = ANY($2::text[]))`, [identityValues, r.roomIds]],
    ['community_events', `DELETE FROM fallback_community_events WHERE lower(community_id) = ANY($1::text[]) OR lower(created_by) = ANY($2::text[])`, [r.roomIds, identityValues]],
    ['community_member_profiles', `DELETE FROM fallback_community_member_profiles WHERE lower(community_id) = ANY($1::text[]) OR lower(user_id) = ANY($2::text[])`, [r.roomIds, identityValues]],
    ['community_channels', `DELETE FROM fallback_community_channels WHERE lower(community_id) = ANY($1::text[]) OR lower(created_by) = ANY($2::text[]) OR lower(COALESCE(paused_by, '')) = ANY($2::text[])`, [r.roomIds, identityValues]],
    ['collection_members', `DELETE FROM fallback_collection_members WHERE lower(room_id) = ANY($1::text[]) OR lower(user_id) = ANY($2::text[])`, [r.roomIds, identityValues]],
    ['collection_invites', `DELETE FROM fallback_collection_invites WHERE lower(room_id) = ANY($1::text[]) OR lower(created_by) = ANY($2::text[])`, [r.roomIds, identityValues]],
    ['collection_read_states', `DELETE FROM fallback_collection_read_states WHERE lower(room_id) = ANY($1::text[]) OR lower(user_id) = ANY($2::text[])`, [r.roomIds, identityValues]],
    ['collection_rooms', `DELETE FROM fallback_collection_rooms WHERE lower(id::text) = ANY($1::text[]) OR lower(creator_id) = ANY($2::text[])`, [r.roomIds, identityValues]],
    ['voice_room_participants', `DELETE FROM fallback_voice_room_participants WHERE lower(room_id) = ANY($1::text[]) OR lower(user_id) = ANY($2::text[])`, [r.voiceRoomIds, identityValues]],
    ['voice_rooms', `DELETE FROM fallback_voice_rooms WHERE lower(id::text) = ANY($1::text[]) OR lower(creator_id) = ANY($2::text[])`, [r.voiceRoomIds, identityValues]],
    ['follows', `DELETE FROM fallback_follows WHERE lower(follower_id) = ANY($1::text[]) OR lower(following_id) = ANY($1::text[])`, [identityValues]],
    ['friends', `DELETE FROM fallback_friends WHERE lower(sender_id) = ANY($1::text[]) OR lower(receiver_id) = ANY($1::text[])`, [identityValues]],
    ['notifications', `DELETE FROM fallback_notifications WHERE lower(recipient_id) = ANY($1::text[]) OR lower(COALESCE(actor_id, '')) = ANY($1::text[])`, [identityValues]],
    ['notification_digest_preferences', `DELETE FROM fallback_notification_digest_preferences WHERE lower(user_id) = ANY($1::text[])`, [identityValues]],
    ['nicknames', `DELETE FROM fallback_nicknames WHERE lower(user_id) = ANY($1::text[]) OR lower(target_user_id) = ANY($1::text[])`, [identityValues]],
    ['user_blocks', `DELETE FROM fallback_user_blocks WHERE lower(blocker_id) = ANY($1::text[]) OR lower(blocked_id) = ANY($1::text[])`, [identityValues]],
    ['proximity_presence', `DELETE FROM fallback_proximity_presence WHERE lower(user_id) = ANY($1::text[])`, [identityValues]],
    ['view_once_consumptions', `DELETE FROM fallback_view_once_consumptions WHERE lower(user_id) = ANY($1::text[])`, [identityValues]],
    ['scheduled_message_jobs', `DELETE FROM fallback_scheduled_message_jobs WHERE owner_id = $1::uuid OR lower(sender_id) = ANY($2::text[])`, [localUserId, identityValues]],
    ['fcm_tokens', `DELETE FROM fallback_fcm_tokens WHERE lower(user_id) = ANY($1::text[])`, [identityValues]],
    ['web_sessions', `DELETE FROM fallback_web_sessions WHERE user_id = $1::uuid`, [localUserId]],
    ['password_reset_tokens', `DELETE FROM fallback_password_reset_tokens WHERE user_id = $1::uuid`, [localUserId]],
    ['profile_login_emails', `DELETE FROM fallback_profile_login_emails WHERE user_id = $1::uuid`, [localUserId]],
    ['profile_overrides', `DELETE FROM fallback_profile_overrides WHERE user_id = $1::uuid`, [localUserId]],
    ['web_login_pairings', `DELETE FROM fallback_web_login_pairings WHERE approved_by_user_id = $1::uuid`, [localUserId]],
    ['moderation_appeals', `DELETE FROM fallback_moderation_appeals WHERE lower(user_id) = ANY($1::text[]) OR decision_id IN (SELECT id FROM fallback_moderation_decisions WHERE lower(user_id) = ANY($1::text[]))`, [identityValues]],
    ['moderation_decisions', `DELETE FROM fallback_moderation_decisions WHERE lower(user_id) = ANY($1::text[])`, [identityValues]],
    ['profile_moderation', `DELETE FROM fallback_profile_moderation WHERE lower(user_id) = ANY($1::text[])`, [identityValues]],
    ['moderation_events', `DELETE FROM fallback_moderation_events WHERE lower(COALESCE(actor_id, '')) = ANY($1::text[]) OR lower(COALESCE(content_ref, '')) = ANY($1::text[])`, [identityValues]],
  ]
  for (const [label, statement, params] of steps) {
    await deleteCounted(client, counts, label, statement, params)
  }
  return counts
}

async function purgeImportedUserData(client, relatedValues) {
  return purgeImportedRows(client, relatedValues, 'related')
}

async function purgeCompatUserData(client, relatedValues) {
  const result = await client.query(
    `DELETE FROM fallback_compat_rows
     WHERE lower(row_id) = ANY($1::text[])
        OR EXISTS (
          SELECT 1
          FROM jsonb_each(row) AS entry(key, value)
          WHERE lower(entry.key) = ANY($2::text[])
            AND (
              (jsonb_typeof(entry.value) = 'string' AND lower(entry.value #>> '{}') = ANY($1::text[]))
              OR (
                jsonb_typeof(entry.value) = 'array'
                AND EXISTS (
                  SELECT 1 FROM jsonb_array_elements_text(entry.value) AS item(value)
                  WHERE lower(item.value) = ANY($1::text[])
                )
              )
            )
        )`,
    [relatedValues, [...IMPORT_RELATED_FIELDS, ...IMPORT_IDENTITY_ARRAY_FIELDS]],
  )
  return Number(result.rowCount) || 0
}

function flattenRelationIds(relations) {
  return Array.from(new Set(Object.values(relations).flat().map(normalizeId).filter(Boolean)))
}

async function executeAdminHardDelete(options) {
  const targetId = normalizeId(options.targetId)
  const reason = String(options.reason || '').trim().slice(0, 1000)
  if (!targetId) throw hardDeleteError('USER_ID_REQUIRED', 400)
  if (!reason) throw hardDeleteError('HARD_DELETE_REASON_REQUIRED', 400)
  const storageFs = options.storageFs || fs.promises
  const storageRoot = path.resolve(options.storageRoot || DEFAULT_STORAGE_ROOT)
  const deletionId = crypto.randomUUID()
  const storageState = { alreadyMissing: 0, deletionId, entries: [], quarantineRoot: null }
  const client = await options.pool.connect()
  let result
  let transactionStarted = false
  try {
    await client.query('BEGIN')
    transactionStarted = true
    if (!(await appAdminHasCapability(client, options.actorIdentity, ADMIN_CAPABILITY.USERS_DELETE))) {
      throw hardDeleteError('ADMIN_OWNER_REQUIRED', 403)
    }
    const targetResult = await client.query(
       `SELECT u.id::text,
               NULLIF(u.supabase_user_id::text, '') AS public_id,
               u.email,
               u.display_name,
               u.sync_source,
               EXISTS (
                 SELECT 1
                 FROM fallback_profile_overrides o
                 WHERE o.user_id = u.id
                   AND lower(COALESCE(o.profile->>'is_test_account', 'false')) IN ('true', '1')
               ) AS is_test_account,
               EXISTS (
                 SELECT 1 FROM fallback_profile_moderation suspended
                 WHERE suspended.is_suspended = TRUE
                   AND (suspended.suspended_until IS NULL OR suspended.suspended_until > NOW())
                   AND (
                     lower(suspended.user_id) = lower(u.id::text)
                     OR lower(suspended.user_id) = lower(COALESCE(u.supabase_user_id::text, ''))
                   )
               ) AS is_suspended,
               EXISTS (
                SELECT 1 FROM fallback_profile_moderation m
                WHERE m.is_admin = TRUE
                  AND (
                    lower(m.user_id) = lower(u.id::text)
                    OR lower(m.user_id) = lower(COALESCE(u.supabase_user_id::text, ''))
                  )
              ) AS is_admin
       FROM fallback_users u
       WHERE lower(u.id::text) = $1
          OR lower(COALESCE(u.supabase_user_id::text, '')) = $1
       LIMIT 1
       FOR UPDATE OF u`,
      [targetId],
    )
    const target = targetResult.rows[0]
    if (!target) throw hardDeleteError('USER_NOT_FOUND', 404)
    const localUserId = validUuidOrNull(target.id)
    if (!localUserId) throw hardDeleteError('INVALID_USER_ID', 500)
    const targetIdentityValues = Array.from(new Set([
      target.id,
      target.public_id,
    ].map(normalizeId).filter(Boolean)))
    const actorIdentityValues = identityValuesOf(options.actorIdentity)
    if (targetIdentityValues.some((value) => actorIdentityValues.includes(value))) {
      throw hardDeleteError('SELF_DELETE_FORBIDDEN', 403)
    }
    // An active suspension is the explicit administrative prerequisite for a
    // permanent purge. Once another owner suspended the target, its former
    // role must not leave an undeletable account behind.
    if (isProtectedAdminTarget(target, targetIdentityValues) && !target.is_suspended) {
      throw hardDeleteError('ADMIN_CANNOT_BE_DELETED', 403)
    }
    const isTestAccount = isTestAccountTarget(target)
    const expectedConfirmation = isTestAccount
      ? hardDeleteConfirmation(target.id)
      : `ELIMINAR ${normalizeId(target.id)} ${String(target.email || '').trim().toLowerCase()}`
    if (String(options.confirmation || '').trim() !== expectedConfirmation) {
      throw hardDeleteError('HARD_DELETE_CONFIRMATION_INVALID', 400)
    }
    if (!isTestAccount) {
      if (!target.is_suspended) {
        throw hardDeleteError('ACCOUNT_MUST_BE_SUSPENDED_BEFORE_DELETE', 409)
      }
      if (
        options.allowSuspendedAccountDelete !== true ||
        options.acknowledgeIrreversible !== true ||
        options.acknowledgeFullPurge !== true ||
        String(options.expectedEmail || '').trim().toLowerCase() !== String(target.email || '').trim().toLowerCase()
      ) {
        throw hardDeleteError('SUSPENDED_ACCOUNT_DELETE_ACKNOWLEDGEMENTS_REQUIRED', 400)
      }
    }

    // Block writes to every related table before taking the storage snapshot so
    // an upload or relationship cannot appear between discovery and deletion.
    await lockHardDeleteTables(client)
    const storage = await client.query(
      `SELECT id::text, bucket_id, name, local_path
       FROM fallback_storage_objects
       WHERE owner_id = $1::uuid
          OR (
            owner_id IS NULL
            AND lower(split_part(name, '/', 1)) = ANY($2::text[])
          )
       FOR UPDATE`,
      [localUserId, targetIdentityValues],
    )
    await stageStorageRows(storage.rows, {
      state: storageState,
      storageFs,
      storageRoot,
    })

    const relations = await collectHardDeleteRelations(client, targetIdentityValues)
    await insertHardDeleteTombstones(client, targetIdentityValues, relations)
    const deletedByTable = await purgeFallbackUserData(
      client,
      localUserId,
      targetIdentityValues,
      relations,
    )
    deletedByTable.storage_objects = Number((await client.query(
      `DELETE FROM fallback_storage_objects
       WHERE owner_id = $1::uuid
          OR (owner_id IS NULL AND lower(split_part(name, '/', 1)) = ANY($2::text[]))`,
      [localUserId, targetIdentityValues],
    )).rowCount) || 0

    const relatedValues = Array.from(new Set([
      ...targetIdentityValues,
      ...flattenRelationIds(relations),
    ]))
    const importedRowsDeleted = await purgeImportedUserData(client, relatedValues)
    const compatRowsDeleted = await purgeCompatUserData(client, relatedValues)
    await purgeFriendRequestIdentity(client, targetIdentityValues)
    const authTable = await client.query(`SELECT to_regclass('auth.users') AS oid`)
    let authUsersDeleted = 0
    let externalAuthUserDeleted = false
    const publicId = validUuidOrNull(target.public_id)
    if (publicId && authTable.rows[0]?.oid) {
      authUsersDeleted = Number((await client.query(
        `DELETE FROM auth.users WHERE id = $1::uuid`,
        [publicId],
      )).rowCount) || 0
    } else if (publicId && ['supabase', 'both'].includes(normalizeId(target.sync_source))) {
      if (typeof options.deleteExternalAuthUser !== 'function') {
        throw hardDeleteError('EXTERNAL_AUTH_DELETE_UNAVAILABLE', 409)
      }
      const externalResult = await options.deleteExternalAuthUser(publicId)
      externalAuthUserDeleted = externalResult?.deleted === true || externalResult?.alreadyMissing === true
      if (!externalAuthUserDeleted) {
        throw hardDeleteError('EXTERNAL_AUTH_DELETE_FAILED', 502)
      }
    }

    const deletedRows = Object.values(deletedByTable)
      .reduce((total, value) => total + (Number(value) || 0), 0) +
      importedRowsDeleted + compatRowsDeleted + authUsersDeleted
    const targetFingerprint = hardDeleteTargetFingerprint(targetIdentityValues)
    await recordModerationEvent(client, options.actorIdentity?.displayUserId, {
      context: 'admin_hard_delete_user',
      contentRef: targetFingerprint,
      verdict: 'hard_deleted',
      metadataPreview: JSON.stringify({
        deletionId,
        deletedRows,
        reason,
        storageObjects: storage.rows.length,
      }).slice(0, 2000),
    })
    const deletedUser = await client.query(
      `DELETE FROM fallback_users WHERE id = $1::uuid`,
      [localUserId],
    )
    if (deletedUser.rowCount !== 1) {
      throw hardDeleteError('HARD_DELETE_CONFLICT', 409)
    }
    const remaining = await client.query(
      `SELECT
         EXISTS(SELECT 1 FROM fallback_users WHERE id = $1::uuid) AS user_row,
         EXISTS(SELECT 1 FROM fallback_storage_objects WHERE owner_id = $1::uuid) AS storage_rows,
         EXISTS(SELECT 1 FROM fallback_profile_overrides WHERE user_id = $1::uuid) AS profile_override,
         EXISTS(SELECT 1 FROM fallback_web_sessions WHERE user_id = $1::uuid) AS web_sessions,
         EXISTS(SELECT 1 FROM fallback_profile_moderation WHERE lower(user_id) = ANY($2::text[])) AS moderation_rows`,
      [localUserId, targetIdentityValues],
    )
    if (Object.values(remaining.rows[0] || {}).some(Boolean)) {
      throw hardDeleteError('HARD_DELETE_ORPHANS_REMAIN', 500)
    }
    await client.query('COMMIT')
    transactionStarted = false
    result = {
      authUsersDeleted,
      externalAuthUserDeleted,
      compatRowsDeleted,
      deletedRows: deletedRows + 1,
      deletionId,
      importedRowsDeleted,
      storageObjects: storage.rows.length,
    }
  } catch (error) {
    if (transactionStarted) {
      await client.query('ROLLBACK').catch((rollbackError) => {
        error.rollbackError = rollbackError
      })
    }
    const restoreFailures = await restoreStagedStorage(storageState, storageFs)
    if (restoreFailures.length > 0 || error.rollbackError) {
      console.error('[admin-hard-delete] rollback was incomplete', {
        restoreFailures: restoreFailures.length,
        rollbackFailed: Boolean(error.rollbackError),
        ...safeErrorLogDetails(error),
      })
      throw hardDeleteError('HARD_DELETE_ROLLBACK_INCOMPLETE', 500, {
        cause: error,
        restoreFailures,
      })
    }
    throw error
  } finally {
    client.release()
  }

  try {
    await finalizeStagedStorage(storageState, storageFs)
  } catch (error) {
    console.error('[admin-hard-delete] database committed but quarantined storage purge failed', {
      deletionRef: stableKey(deletionId),
      stagedObjects: storageState.entries.length,
      ...safeErrorLogDetails(error),
    })
    throw hardDeleteError('HARD_DELETE_COMMITTED_STORAGE_PURGE_FAILED', 500, {
      cause: error,
      deletionCommitted: true,
      deletionId,
    })
  }

  return {
    ...result,
    mediaAlreadyMissing: storageState.alreadyMissing,
    mediaRemoved: storageState.entries.length,
  }
}

function registerAdminModerationRoutes(app, {
  pool,
  authRequired,
  appealAuthRequired = authRequired,
  getCurrentUser,
  userIdentity,
  deleteExternalAuthUser,
  invalidateProfileCache = () => {},
  signStorageObjectReference = (_req, value) => value,
}) {
  function signEvidenceUrls(req, values) {
    return (Array.isArray(values) ? values : [])
      .map((value) => signStorageObjectReference(req, value))
      .filter(Boolean)
  }

  function signReportEvidence(req, row) {
    if (!row) return row
    return {
      ...row,
      evidence_url: signStorageObjectReference(req, row.evidence_url),
    }
  }
  async function adminGuard(req, res, {
    capability = null,
    minimum,
    ownerOnly = false,
  } = {}) {
    const user = await getCurrentUser(pool, req)
    if (!user) {
      res.status(404).json({ error: 'USER_NOT_FOUND' })
      return null
    }
    const identity = userIdentity(user)
    const access = await appAdminAccess(pool, identity)
    if (access.level === 'none') {
      res.status(403).json({ error: 'ADMIN_REQUIRED' })
      return null
    }
    const requiredCapability = ownerOnly
      ? ADMIN_CAPABILITY.ACCESS_MANAGE
      : capability || (minimum === 'view' ? ADMIN_CAPABILITY.READ : ADMIN_CAPABILITY.MODERATION_MANAGE)
    if (ownerOnly && !hasAdminCapability(access, requiredCapability)) {
      res.status(403).json({ error: 'ADMIN_OWNER_REQUIRED' })
      return null
    }
    if (!ownerOnly && !hasAdminCapability(access, requiredCapability)) {
      res.status(403).json({ error: 'ADMIN_CAPABILITY_REQUIRED', capability: requiredCapability })
      return null
    }
    return { user, identity, access }
  }

  app.get('/v1/moderation/decision', appealAuthRequired, async (req, res, next) => {
    try {
      const { rows } = await pool.query(
        `SELECT d.*
         FROM fallback_users u
         JOIN fallback_profile_moderation m
           ON lower(m.user_id) = lower(u.id::text)
           OR lower(m.user_id) = lower(COALESCE(u.supabase_user_id::text, ''))
         JOIN fallback_moderation_decisions d ON d.id = m.decision_id
         WHERE u.id = $1::uuid
         ORDER BY d.decided_at DESC
         LIMIT 1`,
        [req.auth.sub],
      )
      const decision = rows[0]
      if (!decision) return res.status(404).json({ error: 'MODERATION_DECISION_NOT_FOUND' })
      const appeals = await pool.query(
        `SELECT *
         FROM fallback_moderation_appeals
         WHERE decision_id = $1::uuid
         ORDER BY created_at DESC
         LIMIT 1`,
        [decision.id],
      )
      res.json({ decision: moderationDecisionPayload(decision, appeals.rows[0]) })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/moderation/appeals', appealAuthRequired, async (req, res, next) => {
    try {
      const statement = textOf(req.body, ['statement', 'message']).slice(0, 2000)
      if (statement.length < 20) {
        return res.status(400).json({ error: 'APPEAL_STATEMENT_TOO_SHORT' })
      }
      const evidenceUrls = Array.from(new Set(
        (Array.isArray(req.body?.evidence_urls) ? req.body.evidence_urls : [])
          .map((value) => String(value || '').trim())
          .filter((value) => /^https:\/\//i.test(value))
          .map((value) => value.slice(0, 500)),
      )).slice(0, 5)
      const decisionResult = await pool.query(
        `SELECT d.*, m.user_id AS moderation_user_id
         FROM fallback_users u
         JOIN fallback_profile_moderation m
           ON lower(m.user_id) = lower(u.id::text)
           OR lower(m.user_id) = lower(COALESCE(u.supabase_user_id::text, ''))
         JOIN fallback_moderation_decisions d ON d.id = m.decision_id
         WHERE u.id = $1::uuid
           AND d.status = 'active'
         ORDER BY d.decided_at DESC
         LIMIT 1`,
        [req.auth.sub],
      )
      const decision = decisionResult.rows[0]
      if (!decision) return res.status(404).json({ error: 'ACTIVE_MODERATION_DECISION_NOT_FOUND' })
      if (decision.appeal_deadline && new Date(decision.appeal_deadline).getTime() < Date.now()) {
        return res.status(409).json({ error: 'APPEAL_DEADLINE_EXPIRED' })
      }
      const existing = await pool.query(
        `SELECT id::text, status
         FROM fallback_moderation_appeals
         WHERE decision_id = $1::uuid
           AND status IN ('pending', 'reviewing')
         LIMIT 1`,
        [decision.id],
      )
      if (existing.rows[0]) {
        return res.status(409).json({
          error: 'APPEAL_ALREADY_OPEN',
          appeal_id: existing.rows[0].id,
          status: existing.rows[0].status,
        })
      }
      const { rows } = await pool.query(
        `INSERT INTO fallback_moderation_appeals(
           decision_id, user_id, statement, evidence_urls, status
         )
         VALUES ($1::uuid, $2, $3, $4::jsonb, 'pending')
         RETURNING *`,
        [
          decision.id,
          decision.moderation_user_id,
          statement,
          JSON.stringify(evidenceUrls),
        ],
      )
      await recordModerationEvent(pool, decision.moderation_user_id, {
        context: 'moderation_appeal_submitted',
        contentRef: decision.id,
        verdict: 'pending_human_review',
        metadataPreview: `Apelación ${rows[0].id}`,
      })
      res.status(201).json({
        appeal: {
          id: rows[0].id,
          decision_id: rows[0].decision_id,
          status: rows[0].status,
          statement: rows[0].statement,
          created_at: rows[0].created_at,
        },
      })
    } catch (error) {
      if (error?.code === '23505') {
        return res.status(409).json({ error: 'APPEAL_ALREADY_OPEN' })
      }
      next(error)
    }
  })

  app.get('/v1/data/admin/access', authRequired, async (req, res, next) => {
    try {
      const context = await adminGuard(req, res, { minimum: 'view' })
      if (!context) return
      res.json(adminAccessContract(context.access))
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/admin/catalog', authRequired, async (req, res, next) => {
    try {
      const context = await adminGuard(req, res, { minimum: 'view' })
      if (!context) return
      res.json({
        badges: await adminBadgeCatalog(pool, {
          canWrite: ADMIN_ACCESS_RANK[context.access.level] >= ADMIN_ACCESS_RANK.write,
        }),
        frames: PROFILE_FRAME_CATALOG,
      })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/admin/badges', authRequired, async (req, res, next) => {
    try {
      const context = await adminGuard(req, res)
      if (!context) return
      const name = String(req.body?.name || '').trim().slice(0, 60)
      const description = String(req.body?.description || '').trim().slice(0, 180)
      const icon = String(req.body?.icon || '🏅').trim().slice(0, 16) || '🏅'
      const colorInput = String(req.body?.color || '#14B8A6').trim().toUpperCase()
      const color = /^#[0-9A-F]{6}$/.test(colorInput) ? colorInput : null
      const requestedId = normalizeId(req.body?.id)
      const generatedId = name
        .normalize('NFD')
        .replace(/[\u0300-\u036f]/g, '')
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-+|-+$/g, '')
        .slice(0, 40)
      const id = requestedId
        ? (requestedId.startsWith('custom-') ? requestedId : `custom-${requestedId}`)
        : `custom-${generatedId}`
      if (!name) return res.status(400).json({ error: 'BADGE_NAME_REQUIRED' })
      if (!/^custom-[a-z0-9][a-z0-9-]{0,32}$/.test(id)) {
        return res.status(400).json({ error: 'BADGE_ID_INVALID' })
      }
      if (!color) return res.status(400).json({ error: 'BADGE_COLOR_INVALID' })
      if (ADMIN_BADGES_BY_ID.has(id)) return res.status(409).json({ error: 'BADGE_ALREADY_EXISTS' })

      const { rows } = await pool.query(
        `INSERT INTO fallback_admin_badges(id, name, description, icon, color, created_by)
         VALUES ($1, $2, $3, $4, $5, $6)
         RETURNING id, name, description, icon, color, created_by`,
        [id, name, description, icon, color, context.identity.displayUserId],
      )
      const badge = customAdminBadgeFromRow(rows[0], {
        canWrite: true,
      })
      await recordModerationEvent(pool, context.identity.displayUserId, {
        context: 'admin_badge_created',
        contentRef: badge.id,
        verdict: 'created',
        metadataPreview: badge.name,
      })
      res.status(201).json({ badge })
    } catch (error) {
      if (error?.code === '23505') return res.status(409).json({ error: 'BADGE_ALREADY_EXISTS' })
      next(error)
    }
  })

  app.get('/v1/data/admin/users', authRequired, async (req, res, next) => {
    try {
      const context = await adminGuard(req, res, { minimum: 'view' })
      if (!context) return
      const search = String(req.query?.search || '').trim().slice(0, 120)
      const status = normalizeId(req.query?.status || 'all')
      const limit = Math.min(Math.max(Number.parseInt(req.query?.limit, 10) || 50, 1), 100)
      const offset = Math.max(Number.parseInt(req.query?.offset, 10) || 0, 0)
      if (!['all', 'active', 'suspended', 'test'].includes(status)) {
        return res.status(400).json({ error: 'ADMIN_USER_STATUS_INVALID' })
      }
      const predicates = []
      const params = []
      if (search) {
        params.push(`%${search}%`)
        predicates.push(`(
          u.email ILIKE $${params.length}
          OR COALESCE(o.profile->>'username', '') ILIKE $${params.length}
          OR COALESCE(o.profile->>'display_name', o.profile->>'full_name', u.display_name, '') ILIKE $${params.length}
          OR u.id::text ILIKE $${params.length}
          OR COALESCE(u.supabase_user_id::text, '') ILIKE $${params.length}
        )`)
      }
      if (status === 'active') predicates.push(`NOT (COALESCE(m.is_suspended, FALSE) AND (m.suspended_until IS NULL OR m.suspended_until > NOW()))`)
      if (status === 'suspended') predicates.push(`COALESCE(m.is_suspended, FALSE) AND (m.suspended_until IS NULL OR m.suspended_until > NOW())`)
      if (status === 'test') predicates.push(`(
        lower(COALESCE(o.profile->>'is_test_account', 'false')) IN ('true', '1')
        OR lower(COALESCE(u.sync_source, '')) = 'test'
        OR lower(u.email) ~ '(^|[+._-])test([+._-]|@)'
        OR lower(u.email) ~ '@(example\\.com|example\\.test|test\\.invalid)$'
      )`)
      params.push(limit, offset)
      const where = predicates.length > 0 ? `WHERE ${predicates.join(' AND ')}` : ''
      const { rows } = await pool.query(
        `${ADMIN_USER_SELECT}
         ${where}
         ORDER BY u.created_at DESC, u.id DESC
         LIMIT $${params.length - 1} OFFSET $${params.length}`,
        params,
      )
      const [{ count: total = 0 } = {}] = (await pool.query(
        `SELECT COUNT(*)::int AS count
         FROM fallback_users u
         LEFT JOIN fallback_profile_overrides o ON o.user_id = u.id
         LEFT JOIN LATERAL (
           SELECT moderation.* FROM fallback_profile_moderation moderation
           WHERE lower(moderation.user_id) = lower(u.id::text)
              OR lower(moderation.user_id) = lower(COALESCE(u.supabase_user_id::text, ''))
           ORDER BY moderation.is_admin DESC, moderation.updated_at DESC LIMIT 1
         ) m ON TRUE
         ${where}`,
        params.slice(0, -2),
      )).rows
      res.json({ items: rows.map(adminUserFromRow), total, limit, offset })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/admin/users/:id', authRequired, async (req, res, next) => {
    try {
      const context = await adminGuard(req, res, { minimum: 'view' })
      if (!context) return
      const user = await findAdminTarget(pool, req.params.id)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identityValues = Array.from(new Set([user.id, user.local_id].map(normalizeId).filter(Boolean)))
      const { rows } = await pool.query(
        `SELECT
           (SELECT COUNT(*)::int FROM fallback_posts WHERE lower(user_id) = ANY($1::text[])) AS posts,
           (SELECT COUNT(*)::int FROM fallback_videos WHERE lower(user_id) = ANY($1::text[])) AS videos,
           (SELECT COUNT(*)::int FROM fallback_moments WHERE lower(user_id) = ANY($1::text[])) AS moments,
           (SELECT COUNT(*)::int FROM fallback_reports WHERE lower(reported_user_id) = ANY($1::text[])) AS reports,
           (SELECT COUNT(*)::int FROM fallback_storage_objects WHERE owner_id = $2::uuid) AS storage_objects,
           (SELECT COALESCE(SUM(size_bytes), 0)::bigint FROM fallback_storage_objects WHERE owner_id = $2::uuid) AS storage_bytes`,
        [identityValues, user.local_id],
      )
      res.json({ user: { ...user, content: rows[0] || {} } })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/admin/badges/:id', authRequired, async (req, res, next) => {
    const badgeId = normalizeId(req.params?.id)
    if (!badgeId) return res.status(400).json({ error: 'BADGE_ID_REQUIRED' })
    if (ADMIN_BADGES_BY_ID.has(badgeId)) {
      return res.status(409).json({ error: 'BUILT_IN_BADGE_CANNOT_BE_DELETED' })
    }

    const client = await pool.connect()
    let transactionStarted = false
    try {
      const context = await adminGuard(req, res)
      if (!context) return
      await client.query('BEGIN')
      transactionStarted = true
      const { rows } = await client.query(
        `SELECT id, name, created_by
         FROM fallback_admin_badges
         WHERE lower(id) = $1
         LIMIT 1
         FOR UPDATE`,
        [badgeId],
      )
      const badge = rows[0]
      if (!badge) {
        await client.query('ROLLBACK')
        transactionStarted = false
        return res.status(404).json({ error: 'BADGE_NOT_FOUND' })
      }
      const updatedProfiles = await client.query(
        `UPDATE fallback_profile_overrides
         SET profile = jsonb_set(
               profile,
               '{badges}',
               COALESCE((
                 SELECT jsonb_agg(entry.value)
                 FROM jsonb_array_elements(profile->'badges') AS entry(value)
                 WHERE lower(
                   CASE
                     WHEN jsonb_typeof(entry.value) = 'string' THEN entry.value #>> '{}'
                     ELSE COALESCE(entry.value->>'id', '')
                   END
                 ) <> $1
               ), '[]'::jsonb),
               TRUE
             ),
             updated_at = NOW()
         WHERE jsonb_typeof(profile->'badges') = 'array'
           AND EXISTS (
             SELECT 1
             FROM jsonb_array_elements(profile->'badges') AS assigned(value)
             WHERE lower(
               CASE
                 WHEN jsonb_typeof(assigned.value) = 'string' THEN assigned.value #>> '{}'
                 ELSE COALESCE(assigned.value->>'id', '')
               END
             ) = $1
           )`,
        [badgeId],
      )
      await client.query(
        `DELETE FROM fallback_admin_badges WHERE lower(id) = $1`,
        [badgeId],
      )
      await recordModerationEvent(client, context.identity.displayUserId, {
        context: 'admin_badge_deleted',
        contentRef: badgeId,
        verdict: 'deleted',
        metadataPreview: JSON.stringify({
          name: String(badge.name || '').trim(),
          profilesUpdated: Number(updatedProfiles.rowCount) || 0,
        }),
      })
      await client.query('COMMIT')
      transactionStarted = false
      invalidateProfileCache()
      res.json({
        success: true,
        badgeId,
        profilesUpdated: Number(updatedProfiles.rowCount) || 0,
      })
    } catch (error) {
      if (transactionStarted) await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client.release()
    }
  })

  app.put('/v1/data/admin/users/:id/access', authRequired, async (req, res, next) => {
    try {
      const context = await adminGuard(req, res, { ownerOnly: true })
      if (!context) return
      const level = normalizeAdminAccessLevel(req.body?.access_level, '')
      if (!Object.prototype.hasOwnProperty.call(ADMIN_ACCESS_RANK, level)) {
        return res.status(400).json({ error: 'ADMIN_ACCESS_LEVEL_INVALID' })
      }
      const target = await findAdminTarget(pool, req.params.id)
      if (!target) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      if (target.admin_is_owner && level !== 'full') {
        return res.status(403).json({ error: 'ADMIN_OWNER_ACCESS_IMMUTABLE' })
      }
      if (target.admin_is_owner) {
        return res.json({ user: target, unchanged: true })
      }

      await pool.query(
        `INSERT INTO fallback_profile_moderation(
           user_id, is_admin, admin_access_level, admin_is_owner, updated_at
         )
         VALUES ($1, $2, $3, FALSE, NOW())
         ON CONFLICT (user_id) DO UPDATE SET
           is_admin = EXCLUDED.is_admin,
           admin_access_level = EXCLUDED.admin_access_level,
           admin_is_owner = FALSE,
           updated_at = NOW()`,
        [target.id, level !== 'none', level],
      )
      const actorName = String(context.user?.display_name || context.user?.email || 'Administrador').trim()
      const accessLabel = {
        none: 'retirado',
        view: 'solo lectura',
        write: 'lectura y moderación',
        full: 'administración total sin eliminación de cuentas',
      }[level]
      await pool.query(
        `INSERT INTO fallback_notifications(
           recipient_id, actor_id, actor_name, type, target_id, target_type, content, extra_data
         )
         VALUES ($1, $2, $3, 'admin_access_changed', $1, 'profile', $4, $5::jsonb)`,
        [
          target.id,
          context.identity.displayUserId,
          actorName,
          `Tu acceso administrativo fue ${accessLabel}.`,
          JSON.stringify({ access_level: level, delegated: level !== 'none' }),
        ],
      )
      await pool.query(
        `INSERT INTO fallback_notifications(
           recipient_id, actor_id, actor_name, type, target_id, target_type, content, extra_data
         )
         SELECT DISTINCT m.user_id, $1, $2, 'admin_access_audit', $3, 'profile', $4, $5::jsonb
         FROM fallback_profile_moderation m
         WHERE m.admin_is_owner = TRUE
           AND lower(m.user_id) <> lower($1)
           AND lower(m.user_id) <> lower($3)`,
        [
          context.identity.displayUserId,
          actorName,
          target.id,
          `${actorName} cambió el acceso administrativo de ${target.display_name} a ${accessLabel}.`,
          JSON.stringify({ access_level: level, target_user_id: target.id }),
        ],
      )
      await recordModerationEvent(pool, context.identity.displayUserId, {
        context: 'admin_access_changed',
        contentRef: target.id,
        verdict: level,
        metadataPreview: `Acceso delegado: ${accessLabel}`,
      })
      invalidateProfileCache()
      void publishRealtimeEvent(`user:${target.id}`, {
        event: 'admin_access_changed',
        accessLevel: level,
      }).catch(() => {})
      res.json({ user: await findAdminTarget(pool, target.id) })
    } catch (error) {
      next(error)
    }
  })

  app.patch('/v1/data/admin/users/:id/decorations', authRequired, async (req, res, next) => {
    try {
      const context = await adminGuard(req, res)
      if (!context) return
      const hasVerified = Object.prototype.hasOwnProperty.call(req.body || {}, 'verified')
      const hasBadges = Object.prototype.hasOwnProperty.call(req.body || {}, 'badge_ids')
      const hasFrame = Object.prototype.hasOwnProperty.call(req.body || {}, 'profile_frame')
      if (!hasVerified && !hasBadges && !hasFrame) {
        return res.status(400).json({ error: 'ADMIN_PROFILE_PATCH_EMPTY' })
      }
      if (hasVerified && typeof req.body.verified !== 'boolean') {
        return res.status(400).json({ error: 'VERIFIED_VALUE_INVALID' })
      }
      const requestedBadgeIds = hasBadges && Array.isArray(req.body.badge_ids)
        ? Array.from(new Set(req.body.badge_ids.map(normalizeId).filter(Boolean)))
        : null
      if (
        hasBadges &&
        (
          requestedBadgeIds === null ||
          requestedBadgeIds.some((id) => !ADMIN_BADGES_BY_ID.has(id) && !id.startsWith('custom-'))
        )
      ) {
        return res.status(400).json({ error: 'BADGE_IDS_INVALID' })
      }
      const badgeCatalog = hasBadges ? await adminBadgeCatalog(pool) : ADMIN_BADGE_CATALOG
      const badgesById = new Map(badgeCatalog.map((badge) => [badge.id, badge]))
      const badgeIds = hasBadges ? normalizeBadgeIds(requestedBadgeIds, badgeCatalog) : undefined
      if (hasBadges && badgeIds === null) return res.status(400).json({ error: 'BADGE_IDS_INVALID' })
      const profileFrame = hasFrame ? normalizeProfileFrame(req.body.profile_frame) : undefined
      if (hasFrame && profileFrame === null) return res.status(400).json({ error: 'PROFILE_FRAME_INVALID' })
      const user = await findAdminTarget(pool, req.params.id)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const patch = {}
      if (hasVerified) patch.is_verified = req.body.verified
      if (hasBadges) patch.badges = badgeIds.map((id) => badgesById.get(id))
      if (hasFrame) patch.profile_frame = profileFrame
      await upsertAdminProfilePatch(pool, user.local_id, patch)
      invalidateProfileCache()
      await recordModerationEvent(pool, context.identity.displayUserId, {
        context: 'admin_profile_decorations',
        contentRef: user.id,
        verdict: 'updated',
        metadataPreview: JSON.stringify({ fields: Object.keys(patch) }),
      })
      const updated = await findAdminTarget(pool, user.id)
      void publishRealtimeEvent(`user:${user.id}`, {
        event: 'profile_updated',
        fields: Object.keys(patch),
      }).catch(() => {})
      res.json({ user: updated })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/admin/users/:id', authRequired, async (req, res, next) => {
    try {
      const context = await adminGuard(req, res, { ownerOnly: true })
      if (!context) return
      if (req.body?.hard_delete !== true) {
        return res.status(400).json({ error: 'HARD_DELETE_FLAG_REQUIRED' })
      }
      const target = await findAdminTarget(pool, req.params.id)
      if (!target) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const result = await executeAdminHardDelete({
        acknowledgeFullPurge: req.body?.acknowledge_full_purge,
        acknowledgeIrreversible: req.body?.acknowledge_irreversible,
        allowSuspendedAccountDelete: req.body?.allow_suspended_account_delete,
        actorIdentity: context.identity,
        confirmation: req.body?.confirmation,
        deleteExternalAuthUser,
        expectedEmail: req.body?.expected_email,
        pool,
        reason: req.body?.reason,
        targetId: req.params.id,
      })
      const realtimeUserIds = Array.from(new Set([
        target.id,
        target.local_id,
      ].map(normalizeId).filter(Boolean)))
      void Promise.all(realtimeUserIds.map((userId) =>
        publishRealtimeEvent(`user:${userId}`, { event: 'account_deleted' }),
      )).catch(() => {})
      res.json({ success: true, deletion: result })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/admin/stats', authRequired, async (req, res, next) => {
    try {
      const context = await adminGuard(req, res, { minimum: 'view' })
      if (!context) return
      const { rows } = await pool.query(`
        WITH deleted_identities AS (
          SELECT lower(user_id) AS id
          FROM fallback_deleted_identities
        ),
        canonical_users AS (
          SELECT lower(COALESCE(NULLIF(supabase_user_id::text, ''), id::text)) AS id
          FROM fallback_users
          UNION
          SELECT lower(id::text)
          FROM supabase_import.profiles
          UNION
          SELECT lower(id::text)
          FROM supabase_import.users
          UNION
          SELECT lower(COALESCE(NULLIF(row->>'id', ''), NULLIF(row->>'user_id', ''), row_id))
          FROM fallback_compat_rows
          WHERE table_name IN ('users', 'profiles')
        ),
        canonical_videos AS (
          SELECT lower(v.id::text) AS id
          FROM fallback_videos v
          WHERE NOT EXISTS (
            SELECT 1 FROM fallback_video_deletions d
            WHERE lower(d.video_id) = lower(v.id::text)
          )
          UNION
          SELECT lower(v.id::text)
          FROM supabase_import.videos v
          WHERE NOT EXISTS (
            SELECT 1 FROM fallback_video_deletions d
            WHERE lower(d.video_id) = lower(v.id::text)
          )
          UNION
          SELECT lower(COALESCE(NULLIF(row->>'id', ''), row_id))
          FROM fallback_compat_rows c
          WHERE c.table_name = 'videos'
            AND NOT EXISTS (
              SELECT 1 FROM fallback_video_deletions d
              WHERE lower(d.video_id) = lower(COALESCE(NULLIF(c.row->>'id', ''), c.row_id))
            )
        )
        SELECT
          (SELECT COUNT(*)::int
           FROM canonical_users u
           WHERE u.id IS NOT NULL
             AND u.id <> ''
             AND NOT EXISTS (
               SELECT 1 FROM deleted_identities d WHERE d.id = u.id
             )) AS total_users,
          (SELECT COUNT(*)::int FROM fallback_profile_moderation WHERE is_suspended = TRUE AND (suspended_until IS NULL OR suspended_until > NOW())) AS suspended_users,
          (SELECT COUNT(*)::int FROM fallback_reports WHERE status = 'pendiente') AS pending_user_reports,
          (SELECT COUNT(*)::int FROM fallback_video_reports WHERE status = 'pendiente') AS pending_video_reports,
          (SELECT COUNT(*)::int
           FROM canonical_videos v
           WHERE v.id IS NOT NULL AND v.id <> '') AS total_videos
      `)
      res.json({ stats: rows[0] || {} })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/admin/suspended-profiles', authRequired, async (req, res, next) => {
    try {
      const context = await adminGuard(req, res, { minimum: 'view' })
      if (!context) return
      const { rows } = await pool.query(
        `SELECT
           COALESCE(NULLIF(u.supabase_user_id::text, ''), u.id::text, m.user_id) AS id,
           u.id::text AS local_id,
           u.email,
           COALESCE(NULLIF(o.profile->>'display_name', ''), NULLIF(o.profile->>'full_name', ''), u.display_name, u.email) AS display_name,
           COALESCE(o.profile->>'username', '') AS username,
           COALESCE(NULLIF(o.profile->>'avatar_url', ''), u.avatar_url, '') AS avatar_url,
           m.is_suspended,
           m.suspended_until,
           m.suspension_end_date,
           m.suspension_reason,
           m.suspension_count,
           m.decision_id::text,
           d.rule_code,
           d.rule_title,
           d.explanation,
           d.evidence,
           d.severity,
           d.appeal_deadline,
           appeal.id::text AS appeal_id,
           appeal.status AS appeal_status,
           appeal.statement AS appeal_statement,
           appeal.reviewer_note AS appeal_reviewer_note,
           appeal.created_at AS appeal_created_at,
           appeal.reviewed_at AS appeal_reviewed_at,
           m.updated_at
         FROM fallback_profile_moderation m
         LEFT JOIN fallback_users u
           ON lower(m.user_id) = lower(u.id::text)
           OR lower(m.user_id) = lower(COALESCE(u.supabase_user_id::text, ''))
         LEFT JOIN fallback_profile_overrides o ON o.user_id = u.id
         LEFT JOIN fallback_moderation_decisions d ON d.id = m.decision_id
         LEFT JOIN LATERAL (
           SELECT a.*
           FROM fallback_moderation_appeals a
           WHERE a.decision_id = d.id
           ORDER BY a.created_at DESC
           LIMIT 1
         ) appeal ON TRUE
         WHERE m.is_suspended = TRUE
           AND (m.suspended_until IS NULL OR m.suspended_until > NOW())
         ORDER BY m.updated_at DESC
         LIMIT 200`,
      )
      res.json({ items: rows })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/admin/moderation/appeals', authRequired, async (req, res, next) => {
    try {
      const context = await adminGuard(req, res, { minimum: 'view' })
      if (!context) return
      const status = normalizeId(req.query?.status || 'all')
      if (!['all', 'pending', 'reviewing', 'upheld', 'overturned'].includes(status)) {
        return res.status(400).json({ error: 'APPEAL_STATUS_INVALID' })
      }
      const params = []
      const predicate = status === 'all' ? '' : 'WHERE a.status = $1'
      if (status !== 'all') params.push(status)
      const { rows } = await pool.query(
        `SELECT
           a.id::text,
           a.decision_id::text,
           a.user_id,
           a.statement,
           a.evidence_urls,
           a.status,
           a.reviewer_id,
           a.reviewer_note,
           a.created_at,
           a.updated_at,
           a.reviewed_at,
           d.action,
           d.rule_code,
           d.rule_title,
           d.explanation,
           d.evidence,
           d.severity,
           d.effective_until,
           d.appeal_deadline,
           COALESCE(NULLIF(o.profile->>'display_name', ''), u.display_name, u.email, 'Usuario') AS display_name,
           u.email
         FROM fallback_moderation_appeals a
         JOIN fallback_moderation_decisions d ON d.id = a.decision_id
         LEFT JOIN fallback_users u
           ON lower(a.user_id) = lower(u.id::text)
           OR lower(a.user_id) = lower(COALESCE(u.supabase_user_id::text, ''))
         LEFT JOIN fallback_profile_overrides o ON o.user_id = u.id
         ${predicate}
         ORDER BY
           CASE a.status WHEN 'pending' THEN 0 WHEN 'reviewing' THEN 1 ELSE 2 END,
           a.created_at
         LIMIT 300`,
        params,
      )
      res.json({
        items: rows.map((row) => ({
          ...row,
          evidence_urls: signEvidenceUrls(req, row.evidence_urls),
        })),
      })
    } catch (error) {
      next(error)
    }
  })

  app.put('/v1/data/admin/moderation/appeals/:id', authRequired, async (req, res, next) => {
    try {
      const context = await adminGuard(req, res)
      if (!context) return
      const appealId = normalizeId(req.params.id)
      const outcome = normalizeId(req.body?.outcome || req.body?.status)
      const note = textOf(req.body, ['note', 'reviewer_note']).slice(0, 2000)
      if (!/^[0-9a-f-]{36}$/.test(appealId)) {
        return res.status(400).json({ error: 'APPEAL_ID_INVALID' })
      }
      if (!['upheld', 'overturned'].includes(outcome)) {
        return res.status(400).json({ error: 'APPEAL_OUTCOME_INVALID' })
      }
      if (note.length < 3) return res.status(400).json({ error: 'APPEAL_REVIEW_NOTE_REQUIRED' })

      const client = await pool.connect()
      let appeal
      try {
        await client.query('BEGIN')
        const current = await client.query(
          `SELECT a.*, d.user_id AS decision_user_id
           FROM fallback_moderation_appeals a
           JOIN fallback_moderation_decisions d ON d.id = a.decision_id
           WHERE a.id = $1::uuid
           FOR UPDATE OF a`,
          [appealId],
        )
        appeal = current.rows[0]
        if (!appeal) {
          await client.query('ROLLBACK')
          return res.status(404).json({ error: 'APPEAL_NOT_FOUND' })
        }
        if (!['pending', 'reviewing'].includes(appeal.status)) {
          await client.query('ROLLBACK')
          return res.status(409).json({ error: 'APPEAL_ALREADY_REVIEWED' })
        }
        const decisionUserId = appeal.decision_user_id
        const updated = await client.query(
          `UPDATE fallback_moderation_appeals
           SET status = $2,
               reviewer_id = $3,
               reviewer_note = $4,
               reviewed_at = NOW(),
               updated_at = NOW()
           WHERE id = $1::uuid
           RETURNING *`,
          [appealId, outcome, context.identity.displayUserId, note],
        )
        appeal = { ...updated.rows[0], decision_user_id: decisionUserId }
        if (outcome === 'overturned') {
          await client.query(
            `UPDATE fallback_moderation_decisions
             SET status = 'reversed',
                 reversed_at = NOW(),
                 reversal_reason = $2,
                 updated_at = NOW()
             WHERE id = $1::uuid`,
            [appeal.decision_id, note],
          )
          await client.query(
            `UPDATE fallback_profile_moderation
             SET is_suspended = FALSE,
                 suspended_until = NULL,
                 suspension_end_date = NULL,
                 suspension_reason = NULL,
                 updated_at = NOW()
             WHERE decision_id = $1::uuid`,
            [appeal.decision_id],
          )
        }
        await client.query('COMMIT')
      } catch (error) {
        await client.query('ROLLBACK').catch(() => {})
        throw error
      } finally {
        client.release()
      }
      await recordModerationEvent(pool, context.identity.displayUserId, {
        context: 'moderation_appeal_reviewed',
        contentRef: appeal.decision_id,
        verdict: outcome,
        metadataPreview: note,
      })
      void publishRealtimeEvent(`user:${normalizeId(appeal.decision_user_id)}`, {
        event: 'moderation_appeal_reviewed',
        outcome,
        decisionId: appeal.decision_id,
      }).catch(() => {})
      res.json({
        success: true,
        appeal: {
          id: appeal.id,
          decision_id: appeal.decision_id,
          status: appeal.status,
          reviewer_note: appeal.reviewer_note,
          reviewed_at: appeal.reviewed_at,
        },
        account_reactivated: outcome === 'overturned',
      })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/admin/reports', authRequired, async (req, res, next) => {
    try {
      const context = await adminGuard(req, res, { minimum: 'view' })
      if (!context) return
      const { rows } = await pool.query(
        `SELECT * FROM fallback_reports ORDER BY created_at DESC LIMIT 200`
      )
      res.json({ items: rows.map((row) => signReportEvidence(req, row)) })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/admin/video-reports', authRequired, async (req, res, next) => {
    try {
      const context = await adminGuard(req, res, { minimum: 'view' })
      if (!context) return
      const { rows } = await pool.query(
        `SELECT * FROM fallback_video_reports ORDER BY created_at DESC LIMIT 200`
      )
      res.json({ items: rows })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/admin/suspend', authRequired, async (req, res, next) => {
    try {
      const context = await adminGuard(req, res)
      if (!context) return
      const userId = normalizeId(req.body?.user_id)
      const reason = textOf(req.body, ['reason']).slice(0, 500)
      const window = suspensionWindow(req.body?.days)
      const rule = normalizeModerationRule(req.body?.rule_code)
      const severity = normalizeModerationSeverity(req.body?.severity)
      const evidence = normalizeModerationEvidence(req.body?.evidence)
      if (!userId) return res.status(400).json({ error: 'USER_ID_REQUIRED' })
      if (!reason) return res.status(400).json({ error: 'SUSPENSION_REASON_REQUIRED' })
      if (!window) return res.status(400).json({ error: 'SUSPENSION_DAYS_INVALID' })
      if (!rule) return res.status(400).json({ error: 'MODERATION_RULE_INVALID' })

      const target = await findAdminTarget(pool, userId)
      if (!target) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      if (target.is_admin) {
        return res.status(403).json({ error: 'ADMIN_CANNOT_BE_SUSPENDED' })
      }

      const client = await pool.connect()
      let decision
      try {
        await client.query('BEGIN')
        const appealDeadline = new Date(Date.now() + 14 * 86400000).toISOString()
        const decisionResult = await client.query(
          `INSERT INTO fallback_moderation_decisions(
             user_id, action, rule_code, rule_title, explanation, evidence,
             severity, status, decided_by, effective_until, appeal_deadline
           )
           VALUES ($1, 'suspension', $2, $3, $4, $5::jsonb, $6, 'active', $7, $8, $9)
           RETURNING *`,
          [
            target.id,
            rule.id,
            rule.title,
            reason,
            JSON.stringify(evidence.length > 0
              ? evidence
              : [{ source: 'manual', reference: '', label: reason }]),
            severity,
            context.identity.displayUserId,
            window.suspendedUntil,
            appealDeadline,
          ],
        )
        decision = decisionResult.rows[0]
        await client.query(
          `INSERT INTO fallback_profile_moderation (
             user_id, is_suspended, suspension_reason, suspension_end_date,
             suspended_until, suspension_count, decision_id, updated_at
           )
           VALUES ($1, TRUE, $2, $3, $3, 1, $4::uuid, NOW())
           ON CONFLICT (user_id) DO UPDATE SET
             is_suspended = TRUE,
             suspension_reason = $2,
             suspension_end_date = $3,
             suspended_until = $3,
             suspension_count = fallback_profile_moderation.suspension_count + 1,
             decision_id = $4::uuid,
             updated_at = NOW()`,
          [target.id, reason, window.suspendedUntil, decision.id],
        )
        await recordModerationEvent(client, context.identity.displayUserId, {
          context: 'admin_suspend_user',
          contentRef: decision.id,
          verdict: window.days === 0 ? 'suspended_permanently' : `suspended_${window.days}_days`,
          metadataPreview: `${rule.id}: ${reason}`,
        })
        await client.query('COMMIT')
      } catch (error) {
        await client.query('ROLLBACK').catch(() => {})
        throw error
      } finally {
        client.release()
      }


      res.json({
        success: true,
        suspended_until: window.suspendedUntil,
        permanent: window.days === 0,
        decision: moderationDecisionPayload(decision),
      })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/admin/unsuspend', authRequired, async (req, res, next) => {
    try {
      const context = await adminGuard(req, res)
      if (!context) return
      const userId = normalizeId(req.body?.user_id)
      const reason = textOf(req.body, ['reason'], 'Reactivación manual').slice(0, 500)
      if (!userId) return res.status(400).json({ error: 'USER_ID_REQUIRED' })
      const target = await findAdminTarget(pool, userId)
      if (!target) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identityValues = Array.from(new Set([target.id, target.local_id].map(normalizeId).filter(Boolean)))
      await pool.query(
        `UPDATE fallback_moderation_decisions
         SET status = 'reversed',
             reversed_at = NOW(),
             reversal_reason = $2,
             updated_at = NOW()
         WHERE id IN (
           SELECT decision_id
           FROM fallback_profile_moderation
           WHERE lower(user_id) = ANY($1::text[])
             AND decision_id IS NOT NULL
         )
           AND status = 'active'`,
        [identityValues, reason],
      )
      const { rowCount } = await pool.query(
        `UPDATE fallback_profile_moderation
         SET is_suspended = FALSE,
             suspended_until = NULL,
             suspension_end_date = NULL,
             suspension_reason = NULL,
             updated_at = NOW()
         WHERE lower(user_id) = ANY($1::text[])`,
        [identityValues],
      )
      if (rowCount === 0) return res.status(404).json({ error: 'SUSPENSION_NOT_FOUND' })
      await recordModerationEvent(pool, context.identity.displayUserId, {
        context: 'admin_unsuspend_user',
        contentRef: target.id,
        verdict: 'reactivated',
        metadataPreview: reason,
      })
      res.json({ success: true })
    } catch (error) {
      next(error)
    }
  })

  app.put('/v1/data/admin/reports/:id/status', authRequired, async (req, res, next) => {
    try {
      const context = await adminGuard(req, res)
      if (!context) return
      const { id } = req.params
      const { status, type } = req.body // type = 'user' or 'video'
      if (!['pendiente', 'resuelto', 'ignorado'].includes(status)) return res.status(400).json({ error: 'Invalid status' })
      if (!['user', 'video'].includes(type)) return res.status(400).json({ error: 'Invalid type' })
      
      const table = type === 'video' ? 'fallback_video_reports' : 'fallback_reports'
      const { rowCount } = await pool.query(`UPDATE ${table} SET status = $1, updated_at = NOW() WHERE id = $2`, [status, id])
      if (rowCount === 0) return res.status(404).json({ error: 'REPORT_NOT_FOUND' })
      await recordModerationEvent(pool, context.identity.displayUserId, {
        context: 'admin_report_status',
        contentRef: id,
        verdict: `${type}:${status}`,
      })
      res.json({ success: true })
    } catch (error) {
      next(error)
    }
  })
}

module.exports = {
  adminAccessFromStoredRow,
  ADMIN_BADGE_CATALOG,
  MODERATION_RULE_CATALOG,
  PROFILE_FRAME_CATALOG,
  appAdminAccess,
  appAdminHasCapability,
  ensureAdminModerationTables,
  executeAdminHardDelete,
  hardDeleteConfirmation,
  isAppAdmin,
  isHardDeleteConfirmationValid,
  isTestAccountTarget,
  normalizeBadgeIds,
  normalizeModerationEvidence,
  normalizeModerationRule,
  normalizeModerationSeverity,
  normalizeProfileFrame,
  normalizeSuspensionDays,
  recordModerationEvent,
  registerAdminModerationRoutes,
  suspensionWindow,
}
