const crypto = require('crypto')
const jwt = require('jsonwebtoken')
const { publishRealtimeEvent } = require('./realtime-publish')
const { publishLiveCollaborationEvent } = require('./live-collaboration-events')
const { leaveLiveGuest } = require('./live-guest-exit')
const { drawGuessEnabled } = require('./live-draw-guess-routes')
const { netWithdrawableGiftAmount } = require('./economic-provenance')
const { constantTimeSecretEqual } = require('./request-security')
const { createDistributedRateLimitMiddleware } = require('./distributed-rate-limit')
const { safeErrorLogDetails } = require('./request-observability')
const {
  muteLiveKitParticipantAudio,
  removeLiveKitParticipant,
} = require('./livekit-admin')
const {
  didLiveStart,
  enqueueLiveStartedFollowerNotifications,
  ensureLiveFollowerNotificationTables,
  liveFollowerNotificationsEnabled,
  processPendingLiveFollowerNotifications,
} = require('./live-follower-notifications')

const LIVE_GUESTS_BATTLES_FLAG = 'LIVE_GUESTS_BATTLES_ENABLED'
const LIVE_SFU_FLAG = 'LIVE_SFU_ENABLED'
// Android lays out one host plus up to eight guests; six is the default room budget.
const DEFAULT_MAX_LIVE_GUESTS = 6
const INVITATION_TTL_MINUTES = 5

function envFlag(name) {
  return /^(1|true|yes|on)$/i.test(String(process.env[name] || '').trim())
}

function liveGuestsBattlesEnabled() {
  return envFlag(LIVE_GUESTS_BATTLES_FLAG)
}

function liveSfuConfiguration() {
  return {
    enabled: envFlag(LIVE_SFU_FLAG),
    url: String(process.env.LIVEKIT_URL || '').trim().replace(/\/+$/, ''),
    apiKey: String(process.env.LIVEKIT_API_KEY || '').trim(),
    apiSecret: String(process.env.LIVEKIT_API_SECRET || '').trim(),
    maxGuests: Math.min(
      8,
      Math.max(1, Number.parseInt(process.env.LIVE_MAX_GUESTS || DEFAULT_MAX_LIVE_GUESTS, 10) || DEFAULT_MAX_LIVE_GUESTS),
    ),
  }
}

function liveRoomName(liveId) {
  return `novvin-live-${normalizeId(liveId)}`
}

function roleGrant(role, audioMutedByHost = false) {
  const isPublisher = role === 'host' || role === 'guest'
  return {
    roomJoin: true,
    canPublish: isPublisher,
    ...(role === 'guest' && audioMutedByHost ? { canPublishSources: ['camera', 'screen_share'] } : {}),
    canSubscribe: true,
    canPublishData: isPublisher,
    canUpdateOwnMetadata: role === 'host' || role === 'guest',
  }
}

function issueLiveSfuToken({ liveId, roomName, userId, displayName, avatarUrl, role, configuration, audioMutedByHost = false, now = Math.floor(Date.now() / 1000) }) {
  const expiresAt = now + 10 * 60
  const token = jwt.sign(
    {
      iss: configuration.apiKey,
      sub: normalizeId(userId),
      name: cleanText(displayName, 160) || 'Usuario',
      iat: now,
      nbf: now - 5,
      exp: expiresAt,
      video: {
        ...roleGrant(role, audioMutedByHost),
        room: cleanText(roomName, 240) || liveRoomName(liveId),
      },
      metadata: JSON.stringify({ liveId: normalizeId(liveId), role,
        ...(avatarUrl ? { avatarUrl: cleanText(avatarUrl, 900) } : {}) }),
    },
    configuration.apiSecret,
    { algorithm: 'HS256' },
  )
  return { token, expiresAt }
}

function normalizeId(value) {
  return String(value || '').trim().toLowerCase()
}

function validUuid(value) {
  const text = normalizeId(value)
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(text)
    ? text
    : null
}

function cleanText(value, maxLength) {
  return String(value || '').trim().slice(0, maxLength)
}

function cleanHashtags(value) {
  const entries = Array.isArray(value) ? value : []
  return [...new Set(entries
    .map((entry) => cleanText(entry, 40).replace(/^#+/, '').toLowerCase())
    .filter(Boolean))]
    .slice(0, 10)
}

function cleanBlockedWords(value) {
  const entries = Array.isArray(value) ? value : []
  return [...new Set(entries
    .map((entry) => cleanText(entry, 40).toLocaleLowerCase('es'))
    .filter((entry) => entry.length >= 2))]
    .slice(0, 50)
}

function normalizedContent(value) {
  return String(value || '')
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLocaleLowerCase('es')
    .replace(/[^\p{L}\p{N}]+/gu, ' ')
    .trim()
}

function containsBlockedWord(content, blockedWords) {
  const haystack = ` ${normalizedContent(content)} `
  return cleanBlockedWords(blockedWords).some((word) => {
    const needle = normalizedContent(word)
    return needle && haystack.includes(` ${needle} `)
  })
}

function sourceOf(value) {
  const source = normalizeId(value)
  if (source === 'screen' || source === 'audio') return source
  return 'camera'
}

function intBetween(value, min, max, fallback = min) {
  const parsed = Number.parseInt(value, 10)
  if (!Number.isFinite(parsed)) return fallback
  return Math.min(max, Math.max(min, parsed))
}

function booleanValue(value, fallback = false) {
  if (typeof value === 'boolean') return value
  if (typeof value === 'number') return value === 1
  if (typeof value === 'string') {
    const normalized = value.trim().toLowerCase()
    if (['1', 'true', 'yes', 'on'].includes(normalized)) return true
    if (['0', 'false', 'no', 'off'].includes(normalized)) return false
  }
  return fallback
}

function profileValue(profile, keys, fallback = '') {
  for (const key of keys) {
    const value = profile?.[key]
    if (value !== undefined && value !== null && String(value).trim()) return String(value).trim()
  }
  return fallback
}

async function ensureLiveTables(pool) {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_live_streams (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      host_user_id TEXT NOT NULL,
      host_name TEXT NOT NULL DEFAULT 'Usuario',
      host_avatar_url TEXT,
      title TEXT NOT NULL DEFAULT '',
      source TEXT NOT NULL DEFAULT 'camera' CHECK (source IN ('camera', 'screen', 'audio')),
      hashtags JSONB NOT NULL DEFAULT '[]'::jsonb,
      cover_url TEXT,
      game_name TEXT,
      category TEXT,
      visibility TEXT NOT NULL DEFAULT 'public' CHECK (visibility IN ('public', 'followers', 'private')),
      gifts_enabled BOOLEAN NOT NULL DEFAULT TRUE,
      battles_enabled BOOLEAN NOT NULL DEFAULT TRUE,
      viewer_count INT NOT NULL DEFAULT 0,
      peak_viewer_count INT NOT NULL DEFAULT 0,
      total_comments INT NOT NULL DEFAULT 0,
      total_reactions INT NOT NULL DEFAULT 0,
      chat_paused BOOLEAN NOT NULL DEFAULT FALSE,
      blocked_words JSONB NOT NULL DEFAULT '[]'::jsonb,
      replay_url TEXT,
      replay_thumbnail_url TEXT,
      replay_duration_ms BIGINT NOT NULL DEFAULT 0,
      replay_published BOOLEAN NOT NULL DEFAULT FALSE,
      replay_updated_at TIMESTAMPTZ,
      is_active BOOLEAN NOT NULL DEFAULT FALSE,
      started_at TIMESTAMPTZ,
      ended_at TIMESTAMPTZ,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    ALTER TABLE fallback_live_streams
    ADD COLUMN IF NOT EXISTS chat_paused BOOLEAN NOT NULL DEFAULT FALSE
  `)
  await pool.query(`
    ALTER TABLE fallback_live_streams
    ADD COLUMN IF NOT EXISTS allow_guest_requests BOOLEAN NOT NULL DEFAULT FALSE
  `)
  await pool.query(`
    ALTER TABLE fallback_live_streams
    ADD COLUMN IF NOT EXISTS blocked_words JSONB NOT NULL DEFAULT '[]'::jsonb
  `)
  await pool.query(`ALTER TABLE fallback_live_streams ADD COLUMN IF NOT EXISTS cover_url TEXT`)
  await pool.query(`ALTER TABLE fallback_live_streams ADD COLUMN IF NOT EXISTS game_name TEXT`)
  await pool.query(`ALTER TABLE fallback_live_streams ADD COLUMN IF NOT EXISTS category TEXT`)
  await pool.query(`ALTER TABLE fallback_live_streams ADD COLUMN IF NOT EXISTS visibility TEXT NOT NULL DEFAULT 'public'`)
  await pool.query(`ALTER TABLE fallback_live_streams ADD COLUMN IF NOT EXISTS gifts_enabled BOOLEAN NOT NULL DEFAULT TRUE`)
  await pool.query(`ALTER TABLE fallback_live_streams ADD COLUMN IF NOT EXISTS battles_enabled BOOLEAN NOT NULL DEFAULT TRUE`)
  await pool.query(`ALTER TABLE fallback_live_streams ADD COLUMN IF NOT EXISTS replay_url TEXT`)
  await pool.query(`ALTER TABLE fallback_live_streams ADD COLUMN IF NOT EXISTS replay_thumbnail_url TEXT`)
  await pool.query(`
    ALTER TABLE fallback_live_streams
    ADD COLUMN IF NOT EXISTS replay_duration_ms BIGINT NOT NULL DEFAULT 0
  `)
  await pool.query(`
    ALTER TABLE fallback_live_streams
    ADD COLUMN IF NOT EXISTS replay_published BOOLEAN NOT NULL DEFAULT FALSE
  `)
  await pool.query(`ALTER TABLE fallback_live_streams ADD COLUMN IF NOT EXISTS replay_updated_at TIMESTAMPTZ`)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_live_streams_active_created
    ON fallback_live_streams(is_active, created_at DESC)
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_live_streams_host_created
    ON fallback_live_streams(lower(host_user_id), created_at DESC)
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_live_comments (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      live_id UUID NOT NULL REFERENCES fallback_live_streams(id) ON DELETE CASCADE,
      user_id TEXT NOT NULL,
      user_name TEXT NOT NULL DEFAULT 'Usuario',
      avatar_url TEXT,
      content TEXT NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_live_comments_live_created
    ON fallback_live_comments(live_id, created_at DESC)
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_live_moderators (
      live_id UUID NOT NULL REFERENCES fallback_live_streams(id) ON DELETE CASCADE,
      user_id TEXT NOT NULL,
      assigned_by TEXT NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (live_id, user_id)
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_live_moderators_user
    ON fallback_live_moderators(lower(user_id), created_at DESC)
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_live_bans (
      live_id UUID NOT NULL REFERENCES fallback_live_streams(id) ON DELETE CASCADE,
      user_id TEXT NOT NULL,
      reason TEXT NOT NULL DEFAULT '',
      banned_by TEXT NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (live_id, user_id)
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_live_bans_user
    ON fallback_live_bans(lower(user_id), created_at DESC)
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_live_moderation_audit (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      live_id UUID NOT NULL REFERENCES fallback_live_streams(id) ON DELETE CASCADE,
      actor_user_id TEXT NOT NULL,
      target_user_id TEXT,
      action TEXT NOT NULL,
      reason TEXT NOT NULL DEFAULT '',
      metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_live_moderation_audit_live
    ON fallback_live_moderation_audit(live_id, created_at DESC)
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_live_chapters (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      live_id UUID NOT NULL REFERENCES fallback_live_streams(id) ON DELETE CASCADE,
      title TEXT NOT NULL,
      offset_ms BIGINT NOT NULL CHECK (offset_ms >= 0),
      is_highlight BOOLEAN NOT NULL DEFAULT TRUE,
      created_by TEXT NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_live_chapters_timeline
    ON fallback_live_chapters(live_id, offset_ms ASC, created_at ASC)
  `)
  await pool.query(`
    ALTER TABLE fallback_live_streams
    DROP CONSTRAINT IF EXISTS fallback_live_streams_source_check
  `)
  await pool.query(`
    ALTER TABLE fallback_live_streams
    ADD CONSTRAINT fallback_live_streams_source_check
    CHECK (source IN ('camera', 'screen', 'audio'))
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_live_guest_invitations (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      live_id UUID NOT NULL REFERENCES fallback_live_streams(id) ON DELETE CASCADE,
      host_user_id TEXT NOT NULL,
      invitee_user_id TEXT NOT NULL,
      initiated_by TEXT NOT NULL DEFAULT 'host'
        CHECK (initiated_by IN ('host', 'viewer')),
      status TEXT NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'accepted', 'rejected', 'canceled', 'expired')),
      request_nonce TEXT NOT NULL,
      expires_at TIMESTAMPTZ NOT NULL,
      responded_at TIMESTAMPTZ,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE (live_id, request_nonce),
      CHECK (lower(host_user_id) <> lower(invitee_user_id))
    )
  `)
  await pool.query(`
    ALTER TABLE fallback_live_guest_invitations
    ADD COLUMN IF NOT EXISTS initiated_by TEXT NOT NULL DEFAULT 'host'
  `)
  await pool.query(`
    CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_live_guest_pending
    ON fallback_live_guest_invitations(live_id, lower(invitee_user_id))
    WHERE status = 'pending'
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_live_guest_invitee
    ON fallback_live_guest_invitations(lower(invitee_user_id), status, created_at DESC)
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_live_participants (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      live_id UUID NOT NULL REFERENCES fallback_live_streams(id) ON DELETE CASCADE,
      user_id TEXT NOT NULL,
      role TEXT NOT NULL CHECK (role IN ('host', 'guest', 'viewer')),
      participant_identity TEXT NOT NULL,
      state TEXT NOT NULL DEFAULT 'invited'
        CHECK (state IN ('invited', 'connected', 'disconnected', 'removed')),
      joined_at TIMESTAMPTZ,
      left_at TIMESTAMPTZ,
      audio_muted_by_host BOOLEAN NOT NULL DEFAULT FALSE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE (live_id, user_id),
      UNIQUE (live_id, participant_identity)
    )
  `)
  await pool.query(`
    ALTER TABLE fallback_live_participants
    ADD COLUMN IF NOT EXISTS audio_muted_by_host BOOLEAN NOT NULL DEFAULT FALSE
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_live_participants_live_state
    ON fallback_live_participants(live_id, state, role)
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_live_battles (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      live_id UUID NOT NULL REFERENCES fallback_live_streams(id) ON DELETE CASCADE,
      left_user_id TEXT NOT NULL,
      right_user_id TEXT NOT NULL,
      status TEXT NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'ended', 'canceled')),
      score_version BIGINT NOT NULL DEFAULT 0 CHECK (score_version >= 0),
      left_score BIGINT NOT NULL DEFAULT 0 CHECK (left_score >= 0),
      right_score BIGINT NOT NULL DEFAULT 0 CHECK (right_score >= 0),
      winner_user_id TEXT,
      started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      ended_at TIMESTAMPTZ,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      CHECK (lower(left_user_id) <> lower(right_user_id)),
      CHECK (winner_user_id IS NULL OR lower(winner_user_id) IN (lower(left_user_id), lower(right_user_id)))
    )
  `)
  await pool.query(`ALTER TABLE fallback_live_battles ADD COLUMN IF NOT EXISTS opponent_live_id UUID REFERENCES fallback_live_streams(id) ON DELETE CASCADE`)
  await pool.query(`ALTER TABLE fallback_live_battles ADD COLUMN IF NOT EXISTS room_name TEXT`)
  await pool.query(`ALTER TABLE fallback_live_battles ADD COLUMN IF NOT EXISTS requested_by_user_id TEXT`)
  await pool.query(`ALTER TABLE fallback_live_battles ADD COLUMN IF NOT EXISTS invitation_expires_at TIMESTAMPTZ`)
  await pool.query(`ALTER TABLE fallback_live_battles ADD COLUMN IF NOT EXISTS responded_at TIMESTAMPTZ`)
  await pool.query(`ALTER TABLE fallback_live_battles DROP CONSTRAINT IF EXISTS fallback_live_battles_status_check`)
  await pool.query(`
    ALTER TABLE fallback_live_battles
    ADD CONSTRAINT fallback_live_battles_status_check
    CHECK (status IN ('invited', 'active', 'rejected', 'ended', 'canceled', 'expired'))
  `).catch(async (error) => {
    // PostgreSQL raises duplicate_object when another migration already installed
    // the identical named constraint. Every other error is still actionable.
    if (error?.code !== '42710') throw error
  })
  await pool.query(`
    UPDATE fallback_live_battles
    SET room_name = COALESCE(room_name, 'novvin-battle-' || id::text)
    WHERE room_name IS NULL OR room_name = ''
  `)
  await pool.query(`DROP INDEX IF EXISTS idx_fallback_live_active_battle`)
  await pool.query(`
    CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_live_active_battle
    ON fallback_live_battles(live_id)
    WHERE status IN ('invited', 'active')
  `)
  await pool.query(`
    CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_live_active_battle_opponent
    ON fallback_live_battles(opponent_live_id)
    WHERE opponent_live_id IS NOT NULL AND status IN ('invited', 'active')
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_live_battle_events (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      battle_id UUID NOT NULL REFERENCES fallback_live_battles(id) ON DELETE CASCADE,
      event_id TEXT NOT NULL,
      source TEXT NOT NULL CHECK (source IN ('gift_ledger', 'channel_points', 'system')),
      event_type TEXT NOT NULL,
      actor_user_id TEXT,
      points BIGINT NOT NULL CHECK (points > 0),
      occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE (battle_id, event_id)
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_live_battle_events_timeline
    ON fallback_live_battle_events(battle_id, occurred_at ASC)
  `)
  await ensureLiveFollowerNotificationTables(pool)
}

async function resolvedProfile(pool, user) {
  const { rows } = await pool.query(
    `SELECT profile FROM fallback_profile_overrides WHERE user_id = $1 LIMIT 1`,
    [user.id],
  )
  const profile = rows[0]?.profile || {}
  return {
    name: profileValue(
      profile,
      ['display_name', 'displayName', 'full_name', 'fullName', 'username'],
      user.display_name || user.email || 'Usuario',
    ).slice(0, 160),
    avatarUrl: profileValue(
      profile,
      ['avatar_url', 'avatarUrl', 'profile_image', 'profileImage'],
      user.avatar_url || '',
    ).slice(0, 900) || null,
  }
}

function ownsStream(stream, identity) {
  return identity.ids.has(normalizeId(stream?.host_user_id))
}

async function canViewStream(pool, stream, identity) {
  if (!stream) return false
  if (ownsStream(stream, identity)) return true
  if (await isLiveBanned(pool, stream.id, identity)) return false
  const { rowCount: blocked } = await pool.query(
    `SELECT 1 FROM fallback_user_blocks
     WHERE (lower(blocker_id) = lower($1) AND lower(blocked_id) = ANY($2::text[]))
        OR (lower(blocked_id) = lower($1) AND lower(blocker_id) = ANY($2::text[]))
     LIMIT 1`,
    [String(stream.host_user_id || ''), identity.values],
  )
  if (blocked > 0) return false
  const visibility = normalizeId(stream.visibility || 'public')
  if (visibility === 'public') return true
  if (visibility !== 'followers') return false
  const query =
    'SELECT 1 FROM fallback_follows ' +
    'WHERE lower(follower_id) = ANY($1::text[]) ' +
    'AND lower(following_id) = lower($2) LIMIT 1'
  const { rowCount } = await pool.query(
    query,
    [identity.values, String(stream.host_user_id || '')],
  )
  return rowCount > 0
}

async function streamById(pool, liveId, { forUpdate = false } = {}) {
  const { rows } = await pool.query(
    `SELECT id::text, host_user_id, host_name, host_avatar_url, title, source, hashtags,
            cover_url, game_name, category, visibility, gifts_enabled, battles_enabled,
            viewer_count, peak_viewer_count, total_comments, total_reactions, chat_paused,
            allow_guest_requests,
            blocked_words, replay_url, replay_thumbnail_url,
            replay_duration_ms::float8 AS replay_duration_ms,
            replay_published, replay_updated_at, is_active,
            started_at, ended_at, created_at, updated_at
     FROM fallback_live_streams
     WHERE id = $1::uuid
     LIMIT 1${forUpdate ? ' FOR UPDATE' : ''}`,
    [liveId],
  )
  return rows[0] || null
}

async function activeCrossLiveBattle(pool, liveId) {
  const { rows } = await pool.query(
    `SELECT id::text, live_id::text, opponent_live_id::text, left_user_id, right_user_id,
            status, room_name, requested_by_user_id, invitation_expires_at, responded_at,
            score_version, left_score, right_score, winner_user_id,
            started_at, ended_at, created_at, updated_at
     FROM fallback_live_battles
     WHERE status = 'active'
       AND (live_id = $1::uuid OR opponent_live_id = $1::uuid)
     ORDER BY started_at DESC
     LIMIT 1`,
    [liveId],
  )
  return rows[0] || null
}

async function effectiveLiveRoomName(pool, liveId) {
  const battle = await activeCrossLiveBattle(pool, liveId)
  return battle?.room_name || liveRoomName(liveId)
}

async function chaptersForLive(pool, liveId) {
  const { rows } = await pool.query(
    `SELECT id::text, live_id::text, title, offset_ms::float8 AS offset_ms,
            is_highlight, created_by, created_at
     FROM fallback_live_chapters
     WHERE live_id = $1::uuid
     ORDER BY offset_ms ASC, created_at ASC`,
    [liveId],
  )
  return rows
}

async function ownsStoredObject(pool, userId, objectUrl, buckets) {
  const cleanUrl = cleanText(objectUrl, 2_000)
  if (!cleanUrl) return false
  const { rows } = await pool.query(
    `SELECT 1
     FROM fallback_storage_objects
     WHERE owner_id = $1::uuid
       AND bucket_id = ANY($2::text[])
       AND (
         public_url = $3 OR
         name = $3 OR
         $3 LIKE ('%/storage/v1/object/public/' || bucket_id || '/' || name)
       )
     LIMIT 1`,
    [userId, buckets, cleanUrl],
  )
  return rows.length > 0
}

async function moderationMembership(pool, stream, identity) {
  const isHost = ownsStream(stream, identity)
  if (isHost) return { isHost: true, isModerator: false, canModerate: true }
  const { rows } = await pool.query(
    `SELECT 1
     FROM fallback_live_moderators
     WHERE live_id = $1::uuid
       AND lower(user_id) = ANY($2::text[])
     LIMIT 1`,
    [stream.id, identity.values],
  )
  const isModerator = rows.length > 0
  return { isHost: false, isModerator, canModerate: isModerator }
}

async function isLiveBanned(pool, liveId, identity) {
  const { rows } = await pool.query(
    `SELECT 1
     FROM fallback_live_bans
     WHERE live_id = $1::uuid
       AND lower(user_id) = ANY($2::text[])
     LIMIT 1`,
    [liveId, identity.values],
  )
  return rows.length > 0
}

function requireLiveCollaborationFeature(res) {
  if (liveGuestsBattlesEnabled()) return true
  res.status(404).json({ error: 'LIVE_GUESTS_BATTLES_DISABLED' })
  return false
}

function requireInternalLiveEvent(req, res) {
  const expected = String(process.env.LIVE_INTERNAL_EVENTS_TOKEN || '').trim()
  const supplied = String(req.headers['x-live-internal-token'] || '').trim()
  if (!constantTimeSecretEqual(supplied, expected)) {
    res.status(404).json({ error: 'NOT_FOUND' })
    return false
  }
  return true
}

function liveSupporterLevel(totalCoins) {
  const coins = Math.max(0, Math.trunc(Number(totalCoins) || 0))
  const thresholds = [0, 100, 500, 1_500, 5_000, 15_000, 50_000, 150_000]
  const level = thresholds.reduce((value, threshold, index) => coins >= threshold ? index + 1 : value, 1)
  const badge = ['Brisa', 'Chispa', 'Aurora', 'Cometa', 'Órbita', 'Nova', 'Galaxia', 'Constelación'][level - 1]
  return { level, badge }
}

function liveSupporterRanking(transactions, liveId, limit = 20) {
  const normalizedLiveId = normalizeId(liveId)
  if (!normalizedLiveId) return []
  const lifetime = new Map()
  const current = new Map()
  for (const transaction of Array.isArray(transactions) ? transactions : []) {
    if (String(transaction?.status || '').toLowerCase() !== 'committed') continue
    if (transaction?.refunded_at || transaction?.reversed_at || transaction?.is_refunded === true) continue
    const senderId = normalizeId(transaction?.sender_id)
    const coins = netWithdrawableGiftAmount(transaction)
    if (!senderId || coins <= 0) continue
    const name = cleanText(transaction?.sender_name, 160) || 'Usuario'
    const avatarUrl = cleanText(transaction?.sender_avatar_url || transaction?.sender_avatar, 2_000)
    const total = lifetime.get(senderId) || { user_id: senderId, name, avatar_url: avatarUrl, coins: 0 }
    total.coins += coins
    if (!total.avatar_url && avatarUrl) total.avatar_url = avatarUrl
    lifetime.set(senderId, total)
    if (normalizeId(transaction?.live_stream_id) === normalizedLiveId) {
      const liveTotal = current.get(senderId) || { user_id: senderId, name, avatar_url: avatarUrl, coins: 0 }
      liveTotal.coins += coins
      if (!liveTotal.avatar_url && avatarUrl) liveTotal.avatar_url = avatarUrl
      current.set(senderId, liveTotal)
    }
  }
  return [...current.values()]
    .sort((left, right) => right.coins - left.coins || left.name.localeCompare(right.name))
    .slice(0, Math.max(1, Math.min(100, Math.trunc(Number(limit) || 20))))
    .map((item, index) => ({
      user_id: item.user_id,
      name: item.name,
      avatar_url: item.avatar_url || null,
      coins_in_live: item.coins,
      lifetime_coins: lifetime.get(item.user_id)?.coins || item.coins,
      rank: index + 1,
      ...liveSupporterLevel(lifetime.get(item.user_id)?.coins || item.coins),
    }))
}

async function invitationForUser(pool, liveId, userId) {
  const { rows } = await pool.query(
    `SELECT id::text, live_id::text, host_user_id, invitee_user_id, initiated_by, status,
            request_nonce, expires_at, responded_at, created_at, updated_at
     FROM fallback_live_guest_invitations
     WHERE live_id = $1::uuid
       AND lower(invitee_user_id) = lower($2)
     ORDER BY created_at DESC
     LIMIT 1`,
    [liveId, userId],
  )
  return rows[0] || null
}

async function invitationByNonce(pool, liveId, requestNonce) {
  const { rows } = await pool.query(
    `SELECT id::text, live_id::text, host_user_id, invitee_user_id, initiated_by, status,
            request_nonce, expires_at, responded_at, created_at, updated_at
     FROM fallback_live_guest_invitations
     WHERE live_id = $1::uuid AND request_nonce = $2
     LIMIT 1`,
    [liveId, requestNonce],
  )
  return rows[0] || null
}

async function invitationById(pool, liveId, invitationId) {
  const { rows } = await pool.query(
    `SELECT id::text, live_id::text, host_user_id, invitee_user_id, initiated_by, status,
            request_nonce, expires_at, responded_at, created_at, updated_at
     FROM fallback_live_guest_invitations
     WHERE live_id = $1::uuid AND id = $2::uuid
     LIMIT 1`,
    [liveId, invitationId],
  )
  return rows[0] || null
}

async function activeGuestCount(pool, liveId, excludeUserId = '') {
  const { rows } = await pool.query(
    `SELECT COUNT(*)::int AS count
     FROM fallback_live_guest_invitations invitation
     JOIN fallback_live_participants participant
       ON participant.live_id = invitation.live_id
      AND lower(participant.user_id) = lower(invitation.invitee_user_id)
     WHERE invitation.live_id = $1::uuid
       AND invitation.status = 'accepted'
       AND ($2 = '' OR lower(invitation.invitee_user_id) <> lower($2))
       AND (
         participant.state = 'connected' OR
         (participant.state = 'invited' AND participant.updated_at > NOW() - INTERVAL '2 minutes')
       )`,
    [liveId, String(excludeUserId || '').trim()],
  )
  return Number(rows[0]?.count || 0)
}

async function appendModerationAudit(pool, {
  liveId,
  actorUserId,
  targetUserId = null,
  action,
  reason = '',
  metadata = {},
}) {
  await pool.query(
    `INSERT INTO fallback_live_moderation_audit(
       live_id, actor_user_id, target_user_id, action, reason, metadata
     )
     VALUES ($1::uuid, $2, $3, $4, $5, $6::jsonb)`,
    [liveId, actorUserId, targetUserId, action, reason, JSON.stringify(metadata)],
  )
}

async function moderationState(pool, stream, identity) {
  const membership = await moderationMembership(pool, stream, identity)
  const banned = membership.isHost ? false : await isLiveBanned(pool, stream.id, identity)
  let moderators = []
  if (membership.canModerate) {
    const result = await pool.query(
      `SELECT user_id, assigned_by, created_at
       FROM fallback_live_moderators
       WHERE live_id = $1::uuid
       ORDER BY created_at ASC`,
      [stream.id],
    )
    moderators = result.rows
  }
  return {
    live_id: stream.id,
    chat_paused: Boolean(stream.chat_paused),
    blocked_words: membership.canModerate ? cleanBlockedWords(stream.blocked_words) : [],
    is_host: membership.isHost,
    is_moderator: membership.isModerator,
    can_moderate: membership.canModerate,
    is_banned: banned,
    moderators,
  }
}

function registerLiveRoutes(app, { pool, authRequired, getCurrentUser, userIdentity }) {
  const limitLiveComments = createDistributedRateLimitMiddleware({
    pool,
    scope: 'live:comments',
    limit: 30,
    windowMs: 60_000,
    code: 'LIVE_COMMENT_RATE_LIMITED',
    key: (req) => `${req.auth?.sub || 'unknown'}:${req.params.liveId || 'unknown'}`,
    methods: ['POST'],
  })
  require('./live-gift-events').registerLiveGiftRoutes(app, {
    pool, authRequired, getCurrentUser, userIdentity, validUuid, streamById, canViewStream, ownsStream,
  })
  app.get('/v1/data/live/capabilities', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const configuration = liveSfuConfiguration()
      res.setHeader('Cache-Control', 'private, no-store')
      res.json({
        guestsBattlesEnabled: liveGuestsBattlesEnabled(),
        sfuAvailable: liveGuestsBattlesEnabled() && configuration.enabled &&
          Boolean(configuration.url && configuration.apiKey && configuration.apiSecret),
        channelPointsEnabled: /^(1|true|yes|on)$/i.test(String(process.env.LIVE_CHANNEL_POINTS_ENABLED || '').trim()) &&
          Boolean(String(process.env.LIVE_POINTS_SESSION_SECRET || '').trim()),
        drawGuessEnabled: drawGuessEnabled(),
        maxGuests: configuration.maxGuests,
        scoringSources: ['gift_ledger', 'channel_points'],
      })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/live/:liveId/sfu-token', authRequired, async (req, res, next) => {
    try {
      if (!requireLiveCollaborationFeature(res)) return
      const configuration = liveSfuConfiguration()
      if (!configuration.enabled || !configuration.url || !configuration.apiKey || !configuration.apiSecret) {
        return res.status(503).json({ error: 'LIVE_SFU_NOT_CONFIGURED' })
      }
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const stream = await streamById(pool, liveId)
      if (!stream || !stream.is_active) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      const identity = userIdentity(user)
      const requestedRole = normalizeId(req.body?.role || 'viewer')
      if (!['host', 'guest', 'viewer'].includes(requestedRole)) {
        return res.status(400).json({ error: 'LIVE_SFU_ROLE_INVALID' })
      }
      // A media token is an access grant. Apply the same visibility policy as
      // GET /live/:id before issuing it, including private and follower rooms.
      if (requestedRole === 'viewer' && !(await canViewStream(pool, stream, identity))) {
        return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      }
      if (requestedRole === 'host' && !ownsStream(stream, identity)) {
        return res.status(403).json({ error: 'LIVE_HOST_REQUIRED' })
      }
      if (requestedRole === 'guest') {
        if (await isLiveBanned(pool, liveId, identity)) {
          return res.status(403).json({ error: 'LIVE_BANNED' })
        }
        const invitation = await invitationForUser(pool, liveId, identity.displayUserId)
        if (!invitation || invitation.status !== 'accepted') {
          return res.status(403).json({ error: 'LIVE_GUEST_INVITATION_REQUIRED' })
        }
        if ((await activeGuestCount(pool, liveId, identity.displayUserId)) >= configuration.maxGuests) {
          return res.status(409).json({ error: 'LIVE_GUEST_LIMIT_REACHED' })
        }
      }
      // While two independent LIVE rooms are in an accepted battle, both hosts
      // and both audiences receive a token for the same short-lived battle room.
      // This is what makes the opponent a real remote publisher instead of a
      // duplicated guest or a black placeholder.
      const activeBattle = await activeCrossLiveBattle(pool, liveId)
      const resolvedRoomName = activeBattle?.room_name || liveRoomName(liveId)
      const moderationState = await pool.query(
        `SELECT audio_muted_by_host FROM fallback_live_participants
         WHERE live_id = $1::uuid AND lower(user_id) = ANY($2::text[]) LIMIT 1`,
        [liveId, identity.values],
      )
      const audioMutedByHost = requestedRole === 'guest' && moderationState.rows[0]?.audio_muted_by_host === true
      // Auth identity contains IDs, not profile fields. Use the public profile;
      // never substitute a private email address for a missing public name.
      const profile = await resolvedProfile(pool, { ...user, email: '' })
      const participant = issueLiveSfuToken({
        liveId,
        roomName: resolvedRoomName,
        userId: identity.displayUserId,
        displayName: profile.name,
        avatarUrl: profile.avatarUrl,
        role: requestedRole,
        audioMutedByHost,
        configuration,
      })
      await pool.query(
        `INSERT INTO fallback_live_participants(
           live_id, user_id, role, participant_identity, state, joined_at, updated_at
         )
         VALUES ($1::uuid, $2, $3, $4, 'invited', NULL, NOW())
         ON CONFLICT (live_id, user_id)
         DO UPDATE SET role = EXCLUDED.role,
                       participant_identity = EXCLUDED.participant_identity,
                       state = 'invited',
                       left_at = NULL,
                       updated_at = NOW()` ,
        [liveId, identity.displayUserId, requestedRole, identity.displayUserId],
      )
      res.setHeader('Cache-Control', 'no-store')
      res.json({
        wsUrl: configuration.url,
        roomName: resolvedRoomName,
        token: participant.token,
        expiresAt: participant.expiresAt,
        role: requestedRole,
        maxGuests: configuration.maxGuests,
        battleId: activeBattle?.id || null,
      })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/live/:liveId/participants/state', authRequired, async (req, res, next) => {
    try {
      if (!requireLiveCollaborationFeature(res)) return
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const stream = await streamById(pool, liveId)
      if (!stream) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      const identity = userIdentity(user)
      const state = normalizeId(req.body?.state)
      if (!['connected', 'disconnected'].includes(state)) {
        return res.status(400).json({ error: 'LIVE_PARTICIPANT_STATE_INVALID' })
      }
      const participantResult = await pool.query(
        `SELECT id::text, live_id::text, user_id, role, participant_identity, state,
                joined_at, left_at, audio_muted_by_host, created_at, updated_at
         FROM fallback_live_participants
         WHERE live_id = $1::uuid AND lower(user_id) = ANY($2::text[])
         LIMIT 1`,
        [liveId, identity.values],
      )
      const participant = participantResult.rows[0]
      if (!participant) return res.status(404).json({ error: 'LIVE_PARTICIPANT_NOT_FOUND' })
      if (participant.role === 'guest') {
        const invitation = await invitationForUser(pool, liveId, participant.user_id)
        if (!invitation || invitation.status !== 'accepted') {
          return res.status(403).json({ error: 'LIVE_GUEST_INVITATION_REQUIRED' })
        }
      }
      const updated = await pool.query(
        `UPDATE fallback_live_participants
         SET state = $3,
             joined_at = CASE WHEN $3 = 'connected' THEN COALESCE(joined_at, NOW()) ELSE joined_at END,
             left_at = CASE WHEN $3 = 'disconnected' THEN NOW() ELSE NULL END,
             updated_at = NOW()
         WHERE live_id = $1::uuid AND lower(user_id) = ANY($2::text[])
         RETURNING id::text, live_id::text, user_id, role, participant_identity, state,
                   joined_at, left_at, audio_muted_by_host, created_at, updated_at`,
        [liveId, identity.values, state],
      )
      await publishLiveCollaborationEvent(liveId, {
        type: `participant_${state}`,
        participant: updated.rows[0],
      })
      res.json({ participant: updated.rows[0] })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/live/:liveId/guests/leave', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const identity = userIdentity(user), stream = await streamById(pool, liveId)
      if (!stream) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      if (ownsStream(stream, identity)) return res.status(400).json({ error: 'LIVE_HOST_CANNOT_LEAVE_AS_GUEST' })
      const result = await leaveLiveGuest(pool, liveId, identity)
      await Promise.all(result.invitations.map(invitation => publishLiveCollaborationEvent(liveId, { type: 'guest_invitation_canceled', invitation })))
      if (result.participant) await publishLiveCollaborationEvent(liveId, { type: 'participant_disconnected', participant: result.participant })
      res.json({ left: true })
    } catch (error) { next(error) }
  })

  app.post('/v1/data/live/:liveId/guests/invitations', authRequired, async (req, res, next) => {
    try {
      if (!requireLiveCollaborationFeature(res)) return
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const stream = await streamById(pool, liveId)
      if (!stream) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      const identity = userIdentity(user)
      if (!ownsStream(stream, identity)) return res.status(403).json({ error: 'LIVE_HOST_REQUIRED' })
      const inviteeUserId = cleanText(req.body?.inviteeUserId || req.body?.invitee_user_id, 160).toLowerCase()
      const requestNonce = cleanText(req.body?.requestNonce || req.body?.request_nonce, 128) || crypto.randomUUID()
      if (!inviteeUserId || identity.ids.has(inviteeUserId)) {
        return res.status(400).json({ error: 'LIVE_INVITATION_TARGET_INVALID' })
      }
      const { rows } = await pool.query(
        `INSERT INTO fallback_live_guest_invitations(
           live_id, host_user_id, invitee_user_id, initiated_by, status, request_nonce, expires_at
         )
         VALUES ($1::uuid, $2, $3, 'host', 'pending', $4, NOW() + ($5::int * INTERVAL '1 minute'))
         ON CONFLICT (live_id, request_nonce) DO NOTHING
         RETURNING id::text, live_id::text, host_user_id, invitee_user_id, initiated_by, status,
                   request_nonce, expires_at, responded_at, created_at, updated_at`,
        [liveId, identity.displayUserId, inviteeUserId, requestNonce, INVITATION_TTL_MINUTES],
      )
      const invitation = rows[0] || await invitationByNonce(pool, liveId, requestNonce)
      if (!invitation) return res.status(409).json({ error: 'LIVE_INVITATION_CONFLICT' })
      await publishLiveCollaborationEvent(liveId, {
        type: 'guest_invitation_created',
        invitation,
      })
      res.status(rows[0] ? 201 : 200).json({ invitation })
    } catch (error) {
      if (error?.code === '23505') return res.status(409).json({ error: 'LIVE_INVITATION_ALREADY_PENDING' })
      next(error)
    }
  })

  app.post('/v1/data/live/:liveId/guests/requests', authRequired, async (req, res, next) => {
    try {
      if (!requireLiveCollaborationFeature(res)) return
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const stream = await streamById(pool, liveId)
      if (!stream || !stream.is_active) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      const identity = userIdentity(user)
      if (ownsStream(stream, identity)) return res.status(409).json({ error: 'LIVE_HOST_CANNOT_REQUEST_GUEST' })
      if (!stream.allow_guest_requests) return res.status(403).json({ error: 'LIVE_GUEST_REQUESTS_DISABLED' })
      const requestNonce = cleanText(req.body?.requestNonce || req.body?.request_nonce, 128) || crypto.randomUUID()
      const { rows } = await pool.query(
        `INSERT INTO fallback_live_guest_invitations(
           live_id, host_user_id, invitee_user_id, initiated_by, status, request_nonce, expires_at
         )
         VALUES ($1::uuid, $2, $3, 'viewer', 'pending', $4, NOW() + ($5::int * INTERVAL '1 minute'))
         ON CONFLICT (live_id, request_nonce) DO NOTHING
         RETURNING id::text, live_id::text, host_user_id, invitee_user_id, initiated_by, status,
                   request_nonce, expires_at, responded_at, created_at, updated_at`,
        [liveId, stream.host_user_id, identity.displayUserId, requestNonce, INVITATION_TTL_MINUTES],
      )
      const invitation = rows[0] || await invitationByNonce(pool, liveId, requestNonce)
      if (!invitation) return res.status(409).json({ error: 'LIVE_GUEST_REQUEST_CONFLICT' })
      await publishLiveCollaborationEvent(liveId, { type: 'guest_request_created', invitation })
      res.status(rows[0] ? 201 : 200).json({ invitation })
    } catch (error) {
      if (error?.code === '23505') return res.status(409).json({ error: 'LIVE_INVITATION_ALREADY_PENDING' })
      next(error)
    }
  })

  app.get('/v1/data/live/:liveId/guests/invitations', authRequired, async (req, res, next) => {
    try {
      if (!requireLiveCollaborationFeature(res)) return
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const identity = userIdentity(user)
      const stream = await streamById(pool, liveId)
      if (!stream) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      const isHost = ownsStream(stream, identity)
      const { rows } = await pool.query(
        `UPDATE fallback_live_guest_invitations
         SET status = 'expired', updated_at = NOW()
         WHERE live_id = $1::uuid AND status = 'pending' AND expires_at <= NOW()
         RETURNING id`,
        [liveId],
      )
      void rows
      const result = await pool.query(
        `SELECT id::text, live_id::text, host_user_id, invitee_user_id, initiated_by, status,
                request_nonce, expires_at, responded_at, created_at, updated_at
         FROM fallback_live_guest_invitations
         WHERE live_id = $1::uuid
           AND ($2::boolean OR lower(invitee_user_id) = ANY($3::text[]))
         ORDER BY created_at DESC
         LIMIT 50`,
        [liveId, isHost, identity.values],
      )
      res.setHeader('Cache-Control', 'private, no-store')
      res.json({ invitations: result.rows })
    } catch (error) {
      next(error)
    }
  })

  app.patch('/v1/data/live/:liveId/guests/invitations/:invitationId', authRequired, async (req, res, next) => {
    let client
    try {
      if (!requireLiveCollaborationFeature(res)) return
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      const invitationId = validUuid(req.params.invitationId)
      if (!liveId || !invitationId) return res.status(400).json({ error: 'LIVE_INVITATION_ID_INVALID' })
      const identity = userIdentity(user)
      const action = normalizeId(req.body?.status || req.body?.action)
      if (!['accepted', 'rejected'].includes(action)) {
        return res.status(400).json({ error: 'LIVE_INVITATION_STATUS_INVALID' })
      }
      client = await pool.connect()
      await client.query('BEGIN')
      await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [`live-guests:${liveId}`])
      const fail = async (status, error) => {
        await client.query('ROLLBACK')
        return res.status(status).json({ error })
      }
      const existing = await invitationById(client, liveId, invitationId)
      if (!existing) return fail(404, 'LIVE_INVITATION_NOT_FOUND')
      const stream = await streamById(client, liveId)
      if (!stream) return fail(404, 'LIVE_NOT_FOUND')
      const viewerInitiated = existing.initiated_by === 'viewer'
      const canRespond = viewerInitiated
        ? ownsStream(stream, identity)
        : identity.ids.has(normalizeId(existing.invitee_user_id))
      if (!canRespond) {
        return fail(403, viewerInitiated ? 'LIVE_HOST_REQUIRED' : 'LIVE_INVITEE_REQUIRED')
      }
      if (existing.status !== 'pending' || new Date(existing.expires_at).getTime() <= Date.now()) {
        return fail(409, 'LIVE_INVITATION_NOT_PENDING')
      }
      if (action === 'accepted' && (await activeGuestCount(client, liveId)) >= liveSfuConfiguration().maxGuests) {
        return fail(409, 'LIVE_GUEST_LIMIT_REACHED')
      }
      const { rows } = await client.query(
        `UPDATE fallback_live_guest_invitations
         SET status = $3, responded_at = NOW(), updated_at = NOW()
         WHERE id = $1::uuid AND live_id = $2::uuid AND status = 'pending' AND expires_at > NOW()
         RETURNING id::text, live_id::text, host_user_id, invitee_user_id, initiated_by, status,
                   request_nonce, expires_at, responded_at, created_at, updated_at`,
        [invitationId, liveId, action],
      )
      const invitation = rows[0]
      if (!invitation) return fail(409, 'LIVE_INVITATION_NOT_PENDING')
      if (action === 'accepted') {
        await client.query(
          `INSERT INTO fallback_live_participants(
             live_id, user_id, role, participant_identity, state, updated_at
           ) VALUES ($1::uuid, $2, 'guest', $2, 'invited', NOW())
           ON CONFLICT (live_id, user_id)
           DO UPDATE SET role = 'guest', state = 'invited', updated_at = NOW()`,
          [liveId, invitation.invitee_user_id],
        )
      }
      await client.query('COMMIT')
      await publishLiveCollaborationEvent(liveId, {
        type: `guest_invitation_${action}`,
        invitation,
      })
      res.json({ invitation })
    } catch (error) {
      if (client) await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client?.release()
    }
  })

  app.post('/v1/data/live/:liveId/guests/:guestUserId/remove', authRequired, async (req, res, next) => {
    let client
    try {
      if (!requireLiveCollaborationFeature(res)) return
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      const guestUserId = cleanText(req.params.guestUserId, 160).toLowerCase()
      if (!liveId || !guestUserId) return res.status(400).json({ error: 'LIVE_PARTICIPANT_INVALID' })
      const stream = await streamById(pool, liveId)
      if (!stream) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      const identity = userIdentity(user)
      if (!ownsStream(stream, identity)) return res.status(403).json({ error: 'LIVE_HOST_REQUIRED' })
      if (identity.ids.has(guestUserId)) return res.status(400).json({ error: 'LIVE_PARTICIPANT_INVALID' })
      const participantResult = await pool.query(
        `SELECT id::text, user_id, participant_identity, state
         FROM fallback_live_participants
         WHERE live_id = $1::uuid AND lower(user_id) = lower($2) AND role = 'guest'
         LIMIT 1`,
        [liveId, guestUserId],
      )
      const participant = participantResult.rows[0]
      if (!participant) return res.status(404).json({ error: 'LIVE_PARTICIPANT_NOT_FOUND' })
      if (participant.state === 'connected') {
        try {
          await removeLiveKitParticipant({
            configuration: liveSfuConfiguration(),
            roomName: await effectiveLiveRoomName(pool, liveId),
            participantIdentity: participant.participant_identity,
          })
        } catch (_error) {
          return res.status(502).json({ error: 'LIVE_PARTICIPANT_REMOVE_FAILED' })
        }
      }
      client = await pool.connect()
      await client.query('BEGIN')
      try {
        await client.query(
          `UPDATE fallback_live_participants
           SET state = 'removed', left_at = NOW(), updated_at = NOW()
           WHERE id = $1::uuid`,
          [participant.id],
        )
        await client.query(
          `UPDATE fallback_live_guest_invitations
           SET status = 'canceled', responded_at = COALESCE(responded_at, NOW()), updated_at = NOW()
           WHERE live_id = $1::uuid AND lower(invitee_user_id) = lower($2) AND status = 'accepted'`,
          [liveId, guestUserId],
        )
        await client.query(
          `UPDATE fallback_live_battles
           SET status = 'canceled', ended_at = NOW(), updated_at = NOW()
           WHERE live_id = $1::uuid AND status = 'active'
             AND (lower(left_user_id) = lower($2) OR lower(right_user_id) = lower($2))`,
          [liveId, guestUserId],
        )
        await appendModerationAudit(client, {
          liveId,
          actorUserId: identity.displayUserId,
          targetUserId: guestUserId,
          action: 'guest_removed',
          reason: cleanText(req.body?.reason, 300),
        })
        await client.query('COMMIT')
      } catch (error) {
        await client.query('ROLLBACK').catch(() => {})
        throw error
      }
      await publishLiveCollaborationEvent(liveId, {
        type: 'participant_removed',
        participant: { user_id: guestUserId, state: 'removed' },
      })
      res.json({ removed: true, userId: guestUserId })
    } catch (error) {
      next(error)
    } finally {
      client?.release()
    }
  })

  app.post('/v1/data/live/:liveId/guests/:guestUserId/mute', authRequired, async (req, res, next) => {
    try {
      if (!requireLiveCollaborationFeature(res)) return
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      const guestUserId = cleanText(req.params.guestUserId, 160).toLowerCase()
      if (!liveId || !guestUserId) return res.status(400).json({ error: 'LIVE_PARTICIPANT_INVALID' })
      if (req.body?.muted === false) {
        return res.status(409).json({ error: 'LIVE_REMOTE_UNMUTE_NOT_ALLOWED' })
      }
      const stream = await streamById(pool, liveId)
      if (!stream) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      const identity = userIdentity(user)
      if (!ownsStream(stream, identity)) return res.status(403).json({ error: 'LIVE_HOST_REQUIRED' })
      const participantResult = await pool.query(
        `SELECT id::text, user_id, participant_identity, state
         FROM fallback_live_participants
         WHERE live_id = $1::uuid AND lower(user_id) = lower($2)
           AND role = 'guest' AND state = 'connected'
         LIMIT 1`,
        [liveId, guestUserId],
      )
      const participant = participantResult.rows[0]
      if (!participant) return res.status(404).json({ error: 'LIVE_PARTICIPANT_NOT_CONNECTED' })
      const result = await muteLiveKitParticipantAudio({
        configuration: liveSfuConfiguration(),
        roomName: await effectiveLiveRoomName(pool, liveId),
        participantIdentity: participant.participant_identity,
      }).catch(() => null)
      if (!result) return res.status(502).json({ error: 'LIVE_PARTICIPANT_MUTE_FAILED' })
      await pool.query(
        `UPDATE fallback_live_participants
         SET audio_muted_by_host = TRUE, updated_at = NOW()
         WHERE id = $1::uuid`,
        [participant.id],
      )
      await appendModerationAudit(pool, {
        liveId,
        actorUserId: identity.displayUserId,
        targetUserId: guestUserId,
        action: 'guest_audio_muted',
      })
      await publishLiveCollaborationEvent(liveId, {
        type: 'participant_audio_muted',
        participant: { user_id: guestUserId, audio_muted_by_host: true },
      })
      res.json({ muted: true, mutedTracks: result.mutedTracks })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/live/:liveId/battles/candidates', authRequired, async (req, res, next) => {
    try {
      if (!requireLiveCollaborationFeature(res)) return
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const stream = await streamById(pool, liveId)
      const identity = userIdentity(user)
      if (!stream || !ownsStream(stream, identity)) return res.status(403).json({ error: 'LIVE_HOST_REQUIRED' })
      await pool.query(
        `UPDATE fallback_live_battles SET status='expired', ended_at=NOW(), updated_at=NOW()
         WHERE status='invited' AND invitation_expires_at <= NOW()`,
      )
      const { rows } = await pool.query(
        `SELECT s.id::text AS live_id, s.host_user_id AS user_id, s.host_name AS name,
                s.host_avatar_url AS avatar_url, s.title, s.viewer_count,
                s.category, s.started_at
         FROM fallback_live_streams s
         WHERE s.is_active = TRUE AND s.battles_enabled = TRUE AND s.id <> $1::uuid
           AND lower(s.host_user_id) <> ALL($2::text[])
           AND NOT EXISTS (
             SELECT 1 FROM fallback_live_battles b
             WHERE b.status IN ('invited', 'active')
               AND (b.live_id = s.id OR b.opponent_live_id = s.id)
           )
         ORDER BY s.viewer_count DESC, s.started_at DESC NULLS LAST
         LIMIT 100`,
        [liveId, identity.values],
      )
      res.setHeader('Cache-Control', 'private, no-store')
      res.json({ items: rows })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/live/:liveId/battles', authRequired, async (req, res, next) => {
    try {
      if (!requireLiveCollaborationFeature(res)) return
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const stream = await streamById(pool, liveId)
      if (!stream) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      const identity = userIdentity(user)
      if (!ownsStream(stream, identity)) return res.status(403).json({ error: 'LIVE_HOST_REQUIRED' })
      await pool.query(
        `UPDATE fallback_live_battles SET status='expired', ended_at=NOW(), updated_at=NOW()
         WHERE status='invited' AND invitation_expires_at <= NOW()`,
      )
      const opponentUserId = cleanText(req.body?.opponentUserId || req.body?.opponent_user_id, 160).toLowerCase()
      if (!opponentUserId || identity.ids.has(opponentUserId)) {
        return res.status(400).json({ error: 'LIVE_BATTLE_OPPONENT_INVALID' })
      }
      const requestedOpponentLiveId = validUuid(req.body?.opponentLiveId || req.body?.opponent_live_id)
      const { rows: opponentRows } = await pool.query(
        `SELECT id::text, host_user_id, host_name, host_avatar_url, title, source, hashtags,
                cover_url, game_name, category, visibility, gifts_enabled, battles_enabled,
                viewer_count, peak_viewer_count, total_comments, total_reactions, chat_paused,
                allow_guest_requests, blocked_words, replay_url, replay_thumbnail_url,
                replay_duration_ms::float8 AS replay_duration_ms, replay_published,
                replay_updated_at, is_active, started_at, ended_at, created_at, updated_at
         FROM fallback_live_streams
         WHERE is_active = TRUE AND battles_enabled = TRUE AND id <> $1::uuid
           AND lower(host_user_id) = lower($2)
           AND ($3::uuid IS NULL OR id = $3::uuid)
         ORDER BY started_at DESC NULLS LAST
         LIMIT 1`,
        [liveId, opponentUserId, requestedOpponentLiveId],
      )
      const opponentStream = opponentRows[0]
      if (opponentStream) {
        const battleId = crypto.randomUUID()
        const roomName = `novvin-battle-${battleId}`
        const { rows } = await pool.query(
          `INSERT INTO fallback_live_battles(
             id, live_id, opponent_live_id, left_user_id, right_user_id, status,
             room_name, requested_by_user_id, invitation_expires_at
           )
           VALUES ($1::uuid, $2::uuid, $3::uuid, $4, $5, 'invited', $6, $4, NOW() + INTERVAL '2 minutes')
           RETURNING id::text, live_id::text, opponent_live_id::text, left_user_id, right_user_id,
                     status, room_name, requested_by_user_id, invitation_expires_at, responded_at,
                     score_version, left_score, right_score, winner_user_id,
                     started_at, ended_at, created_at, updated_at`,
          [battleId, liveId, opponentStream.id, identity.displayUserId, opponentStream.host_user_id, roomName],
        )
        const battle = rows[0]
        await Promise.all([
          publishLiveCollaborationEvent(liveId, { type: 'battle_invited', battle }),
          publishLiveCollaborationEvent(opponentStream.id, { type: 'battle_invitation_received', battle }),
        ])
        return res.status(202).json({ battle })
      }

      // Backward-compatible guest battle. This remains available inside one
      // LIVE, but is deliberately separate from host-vs-host cross-LIVE mode.
      const invitation = await invitationForUser(pool, liveId, opponentUserId)
      if (!invitation || invitation.status !== 'accepted') {
        return res.status(409).json({ error: 'LIVE_BATTLE_GUEST_NOT_ACCEPTED' })
      }
      const { rows } = await pool.query(
        `INSERT INTO fallback_live_battles(live_id, left_user_id, right_user_id, status, room_name, requested_by_user_id)
         VALUES ($1::uuid, $2, $3, 'active', 'novvin-live-' || $1::text, $2)
         RETURNING id::text, live_id::text, opponent_live_id::text, left_user_id, right_user_id, status,
                   room_name, requested_by_user_id, invitation_expires_at, responded_at,
                   score_version, left_score, right_score, winner_user_id,
                   started_at, ended_at, created_at, updated_at`,
        [liveId, identity.displayUserId, opponentUserId],
      )
      const battle = rows[0]
      await publishLiveCollaborationEvent(liveId, { type: 'battle_started', battle })
      res.status(201).json({ battle })
    } catch (error) {
      if (error?.code === '23505') return res.status(409).json({ error: 'LIVE_BATTLE_ALREADY_ACTIVE' })
      next(error)
    }
  })

  app.get('/v1/data/live/:liveId/battles/current', authRequired, async (req, res, next) => {
    try {
      if (!requireLiveCollaborationFeature(res)) return
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const stream = await streamById(pool, liveId)
      if (!stream || !stream.is_active) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      const { rows } = await pool.query(
        `SELECT id::text, live_id::text, opponent_live_id::text, left_user_id, right_user_id, status,
                room_name, requested_by_user_id, invitation_expires_at, responded_at,
                score_version, left_score, right_score, winner_user_id,
                started_at, ended_at, created_at, updated_at
         FROM fallback_live_battles
         WHERE (live_id = $1::uuid OR opponent_live_id = $1::uuid)
           AND status IN ('invited', 'active')
           AND (status <> 'invited' OR invitation_expires_at > NOW())
         ORDER BY CASE WHEN status = 'active' THEN 0 ELSE 1 END, created_at DESC
         LIMIT 1`,
        [liveId],
      )
      res.setHeader('Cache-Control', 'private, no-store')
      res.json({ battle: rows[0] || null })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/live/:liveId/battles/:battleId/respond', authRequired, async (req, res, next) => {
    let client
    try {
      if (!requireLiveCollaborationFeature(res)) return
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      const battleId = validUuid(req.params.battleId)
      if (!liveId || !battleId) return res.status(400).json({ error: 'LIVE_BATTLE_ID_INVALID' })
      const stream = await streamById(pool, liveId)
      const identity = userIdentity(user)
      if (!stream || !ownsStream(stream, identity)) return res.status(403).json({ error: 'LIVE_HOST_REQUIRED' })
      const accepted = req.body?.accepted === true
      client = await pool.connect()
      await client.query('BEGIN')
      const { rows: locked } = await client.query(
        `SELECT id::text, live_id::text, opponent_live_id::text, left_user_id, right_user_id,
                status, room_name, invitation_expires_at
         FROM fallback_live_battles
         WHERE id = $1::uuid FOR UPDATE`,
        [battleId],
      )
      const current = locked[0]
      if (!current || current.opponent_live_id !== liveId || current.status !== 'invited') {
        await client.query('ROLLBACK')
        return res.status(404).json({ error: 'LIVE_BATTLE_INVITATION_NOT_FOUND' })
      }
      if (new Date(current.invitation_expires_at || 0).getTime() <= Date.now()) {
        await client.query(`UPDATE fallback_live_battles SET status='expired', ended_at=NOW(), updated_at=NOW() WHERE id=$1::uuid`, [battleId])
        await client.query('COMMIT')
        return res.status(410).json({ error: 'LIVE_BATTLE_INVITATION_EXPIRED' })
      }
      const nextStatus = accepted ? 'active' : 'rejected'
      const { rows } = await client.query(
        `UPDATE fallback_live_battles
         SET status=$2, responded_at=NOW(),
             started_at=CASE WHEN $2='active' THEN NOW() ELSE started_at END,
             ended_at=CASE WHEN $2='rejected' THEN NOW() ELSE NULL END,
             updated_at=NOW()
         WHERE id=$1::uuid
         RETURNING id::text, live_id::text, opponent_live_id::text, left_user_id, right_user_id,
                   status, room_name, requested_by_user_id, invitation_expires_at, responded_at,
                   score_version, left_score, right_score, winner_user_id,
                   started_at, ended_at, created_at, updated_at`,
        [battleId, nextStatus],
      )
      await client.query('COMMIT')
      const battle = rows[0]
      await Promise.all([
        publishLiveCollaborationEvent(battle.live_id, { type: accepted ? 'battle_started' : 'battle_rejected', battle }),
        publishLiveCollaborationEvent(battle.opponent_live_id, { type: accepted ? 'battle_started' : 'battle_rejected', battle }),
      ])
      res.json({ battle })
    } catch (error) {
      if (client) await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client?.release()
    }
  })

  app.get('/v1/data/live/:liveId/battles/:battleId', authRequired, async (req, res, next) => {
    try {
      if (!requireLiveCollaborationFeature(res)) return
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      const battleId = validUuid(req.params.battleId)
      if (!liveId || !battleId) return res.status(400).json({ error: 'LIVE_BATTLE_ID_INVALID' })
      const identity = userIdentity(user)
      const { rows } = await pool.query(
        `SELECT id::text, live_id::text, opponent_live_id::text, left_user_id, right_user_id, status,
                room_name, requested_by_user_id, invitation_expires_at, responded_at,
                score_version, left_score, right_score, winner_user_id,
                started_at, ended_at, created_at, updated_at
         FROM fallback_live_battles
          WHERE id = $1::uuid AND (live_id = $2::uuid OR opponent_live_id = $2::uuid)
           AND (lower(left_user_id) = ANY($3::text[]) OR lower(right_user_id) = ANY($3::text[]))
         LIMIT 1`,
        [battleId, liveId, identity.values],
      )
      if (!rows[0]) return res.status(404).json({ error: 'LIVE_BATTLE_NOT_FOUND' })
      res.setHeader('Cache-Control', 'private, no-store')
      res.json({ battle: rows[0] })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/live/:liveId/battles/:battleId/end', authRequired, async (req, res, next) => {
    try {
      if (!requireLiveCollaborationFeature(res)) return
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      const battleId = validUuid(req.params.battleId)
      if (!liveId || !battleId) return res.status(400).json({ error: 'LIVE_BATTLE_ID_INVALID' })
      const identity = userIdentity(user)
      const { rows } = await pool.query(
        `UPDATE fallback_live_battles
          SET status = 'ended',
             winner_user_id = CASE
               WHEN left_score > right_score THEN left_user_id
               WHEN right_score > left_score THEN right_user_id
               ELSE NULL
             END,
             ended_at = NOW(), updated_at = NOW()
          WHERE id = $1::uuid AND (live_id = $2::uuid OR opponent_live_id = $2::uuid) AND status = 'active'
            AND (lower(left_user_id) = ANY($3::text[]) OR lower(right_user_id) = ANY($3::text[]))
          RETURNING id::text, live_id::text, opponent_live_id::text, left_user_id, right_user_id, status,
                    room_name, requested_by_user_id, invitation_expires_at, responded_at,
                    score_version, left_score, right_score, winner_user_id,
                   started_at, ended_at, created_at, updated_at`,
        [battleId, liveId, identity.values],
      )
      if (!rows[0]) return res.status(404).json({ error: 'LIVE_BATTLE_NOT_ACTIVE' })
      await Promise.all([
        publishLiveCollaborationEvent(rows[0].live_id, { type: 'battle_ended', battle: rows[0] }),
        rows[0].opponent_live_id
          ? publishLiveCollaborationEvent(rows[0].opponent_live_id, { type: 'battle_ended', battle: rows[0] })
          : Promise.resolve(),
      ])
      res.json({ battle: rows[0] })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/internal/live/battles/:battleId/events', async (req, res, next) => {
    let client
    try {
      if (!liveGuestsBattlesEnabled() || !requireInternalLiveEvent(req, res)) return
      const battleId = validUuid(req.params.battleId)
      if (!battleId) return res.status(400).json({ error: 'LIVE_BATTLE_ID_INVALID' })
      const eventId = cleanText(req.body?.eventId || req.body?.event_id, 160)
      const source = normalizeId(req.body?.source)
      const eventType = cleanText(req.body?.eventType || req.body?.event_type, 80)
      const actorUserId = cleanText(req.body?.actorUserId || req.body?.actor_user_id, 160).toLowerCase()
      const points = intBetween(req.body?.points, 1, 1_000_000, 0)
      if (!eventId || !eventType || !actorUserId || !['gift_ledger', 'channel_points', 'system'].includes(source) || points <= 0) {
        return res.status(400).json({ error: 'LIVE_BATTLE_EVENT_INVALID' })
      }
      client = await pool.connect()
      await client.query('BEGIN')
      const battleResult = await client.query(
        `SELECT id::text, live_id::text, opponent_live_id::text, left_user_id, right_user_id, status
         FROM fallback_live_battles WHERE id = $1::uuid FOR UPDATE`,
        [battleId],
      )
      const battle = battleResult.rows[0]
      if (!battle || battle.status !== 'active') {
        await client.query('ROLLBACK')
        return res.status(409).json({ error: 'LIVE_BATTLE_NOT_ACTIVE' })
      }
      const actor = normalizeId(actorUserId)
      const side = actor === normalizeId(battle.left_user_id)
        ? 'left_score'
        : actor === normalizeId(battle.right_user_id) ? 'right_score' : null
      if (!side) {
        await client.query('ROLLBACK')
        return res.status(403).json({ error: 'LIVE_BATTLE_ACTOR_INVALID' })
      }
      const inserted = await client.query(
        `INSERT INTO fallback_live_battle_events(
           battle_id, event_id, source, event_type, actor_user_id, points
         ) VALUES ($1::uuid, $2, $3, $4, $5, $6)
         ON CONFLICT (battle_id, event_id) DO NOTHING
         RETURNING id::text`,
        [battleId, eventId, source, eventType, actorUserId, points],
      )
      if (!inserted.rows[0]) {
        await client.query('COMMIT')
        return res.json({ duplicate: true })
      }
      const updated = await client.query(
        `UPDATE fallback_live_battles
         SET ${side} = ${side} + $2, score_version = score_version + 1, updated_at = NOW()
         WHERE id = $1::uuid
          RETURNING id::text, live_id::text, opponent_live_id::text, left_user_id, right_user_id, status,
                    room_name, requested_by_user_id, invitation_expires_at, responded_at,
                    score_version, left_score, right_score, winner_user_id,
                   started_at, ended_at, created_at, updated_at`,
        [battleId, points],
      )
      await client.query('COMMIT')
      await Promise.all([
        publishLiveCollaborationEvent(battle.live_id, {
          type: 'battle_score_updated', battle: updated.rows[0], eventId,
        }),
        battle.opponent_live_id
          ? publishLiveCollaborationEvent(battle.opponent_live_id, {
            type: 'battle_score_updated', battle: updated.rows[0], eventId,
          })
          : Promise.resolve(),
      ])
      res.status(201).json({ battle: updated.rows[0], eventId })
    } catch (error) {
      if (client) await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client?.release()
    }
  })

  // Signaling uses this idempotent service-to-service endpoint when the host
  // lease expires after a process crash. A browser/mobile bearer is purposely
  // not retained in Redis just to close an orphaned live stream.
  app.post('/v1/internal/live/:liveId/terminate', async (req, res, next) => {
    try {
      if (!requireInternalLiveEvent(req, res)) return
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const reason = cleanText(req.body?.reason, 120) || 'signaling-host-lease-expired'
      const totalReactions = intBetween(
        req.body?.totalReactions ?? req.body?.total_reactions,
        0,
        100_000_000,
        0,
      )
      const { rows } = await pool.query(
        `WITH target AS (
           SELECT id, is_active AS was_active
           FROM fallback_live_streams
           WHERE id = $1::uuid
           FOR UPDATE
         )
         UPDATE fallback_live_streams AS stream
         SET is_active = FALSE,
             viewer_count = 0,
             total_reactions = GREATEST(stream.total_reactions, $2),
             ended_at = COALESCE(stream.ended_at, NOW()),
             updated_at = NOW()
         FROM target
         WHERE stream.id = target.id
         RETURNING stream.id::text, stream.host_user_id, stream.is_active, stream.viewer_count,
                   stream.total_reactions, stream.started_at, stream.ended_at, stream.updated_at,
                   target.was_active`,
        [liveId, totalReactions],
      )
      const stream = rows[0]
      if (!stream) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      const transitioned = stream.was_active === true
      if (transitioned) {
        await publishLiveCollaborationEvent(liveId, {
          type: 'live_ended',
          reason,
          endedAt: stream.ended_at,
        })
      }
      const { was_active: _wasActive, ...publicStream } = stream
      res.setHeader('Cache-Control', 'no-store')
      res.json({ ok: true, transitioned, stream: publicStream })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/live', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const limit = intBetween(req.query.limit, 1, 100, 60)
      const { rows } = await pool.query(
        `SELECT id::text, host_user_id, host_name, host_avatar_url, title, source, hashtags,
                cover_url, game_name, category, visibility, gifts_enabled, battles_enabled,
                viewer_count, peak_viewer_count, total_comments, total_reactions, chat_paused,
                allow_guest_requests,
                is_active,
                started_at, ended_at, created_at, updated_at
         FROM fallback_live_streams
         WHERE is_active = TRUE
           AND NOT EXISTS (
             SELECT 1 FROM fallback_live_bans ban
             WHERE ban.live_id = fallback_live_streams.id
               AND lower(ban.user_id) = ANY($1::text[])
           )
           AND NOT EXISTS (
             SELECT 1 FROM fallback_user_blocks block
             WHERE (lower(block.blocker_id) = lower(fallback_live_streams.host_user_id)
                    AND lower(block.blocked_id) = ANY($1::text[]))
                OR (lower(block.blocked_id) = lower(fallback_live_streams.host_user_id)
                    AND lower(block.blocker_id) = ANY($1::text[]))
           )
           AND (
             visibility = 'public'
             OR lower(host_user_id) = ANY($1::text[])
             OR (
               visibility = 'followers'
               AND EXISTS (
                 SELECT 1
                 FROM fallback_follows
                 WHERE lower(follower_id) = ANY($1::text[])
                   AND lower(following_id) = lower(fallback_live_streams.host_user_id)
               )
             )
           )
         ORDER BY viewer_count DESC, started_at DESC NULLS LAST, created_at DESC
         LIMIT $2`,
        [identity.values, limit],
      )
      res.setHeader('Cache-Control', 'private, no-store')
      res.json({ items: rows })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/live/replays', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const mine = String(req.query.mine || '').toLowerCase() === 'true'
      const limit = intBetween(req.query.limit, 1, 100, 40)
      const { rows } = await pool.query(
        `SELECT id::text, host_user_id, host_name, host_avatar_url, title, source, hashtags,
                cover_url, game_name, category, visibility, gifts_enabled, battles_enabled,
                viewer_count, peak_viewer_count, total_comments, total_reactions, is_active,
                started_at, ended_at, created_at, updated_at, replay_url, replay_thumbnail_url,
                replay_duration_ms::float8 AS replay_duration_ms,
                replay_published, replay_updated_at
         FROM fallback_live_streams
         WHERE is_active = FALSE
           AND (
             ($1::boolean AND lower(host_user_id) = ANY($2::text[])) OR
             (
               NOT $1::boolean
               AND replay_published = TRUE
               AND replay_url IS NOT NULL
               AND (
                 visibility = 'public'
                 OR lower(host_user_id) = ANY($2::text[])
                 OR (
                   visibility = 'followers'
                   AND EXISTS (
                     SELECT 1
                     FROM fallback_follows
                     WHERE lower(follower_id) = ANY($2::text[])
                       AND lower(following_id) = lower(fallback_live_streams.host_user_id)
                   )
                 )
               )
             )
           )
         ORDER BY COALESCE(replay_updated_at, ended_at, created_at) DESC
         LIMIT $3`,
        [mine, identity.values, limit],
      )
      const items = await Promise.all(rows.map(async (stream) => ({
        ...stream,
        chapters: await chaptersForLive(pool, stream.id),
      })))
      res.setHeader('Cache-Control', 'private, no-store')
      res.json({ items })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/live/:liveId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const stream = await streamById(pool, liveId)
      if (!stream) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      const identity = userIdentity(user)
      // A ban belongs to this authenticated account. Preserve that feedback
      // without exposing profile or stream data, so signaling can explain rejoin denial.
      if (!ownsStream(stream, identity) && await isLiveBanned(pool, liveId, identity)) {
        return res.status(403).json({ error: 'LIVE_BANNED' })
      }
      if (
        (!stream.is_active && !ownsStream(stream, identity)) ||
        !(await canViewStream(pool, stream, identity))
      ) {
        return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      }
      res.setHeader('Cache-Control', 'private, no-store')
      res.json({ stream })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/live/:liveId/supporters', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const stream = await streamById(pool, liveId)
      if (!stream) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      const identity = userIdentity(user)
      if ((!stream.is_active && !ownsStream(stream, identity)) || !(await canViewStream(pool, stream, identity))) {
        return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      }
      const { rows } = await pool.query(
        `SELECT row FROM fallback_compat_rows
         WHERE table_name = 'gift_transactions'
           AND row->>'live_stream_id' = $1
           AND row->>'status' = 'committed'
           AND row->>'is_public' = 'true'
         ORDER BY updated_at DESC
         LIMIT 10000`,
        [liveId],
      )
      const items = liveSupporterRanking(rows.map((entry) => entry?.row || {}), liveId)
      if (items.length > 0) {
        const supporterIds = items.map((item) => item.user_id)
        const { rows: profiles } = await pool.query(
          `SELECT lower(id::text) AS local_id,
                  lower(COALESCE(supabase_user_id::text, '')) AS public_id,
                  avatar_url
             FROM fallback_users
            WHERE lower(id::text) = ANY($1::text[])
               OR lower(COALESCE(supabase_user_id::text, '')) = ANY($1::text[])`,
          [supporterIds],
        )
        const avatars = new Map()
        profiles.forEach((profile) => {
          const avatar = cleanText(profile?.avatar_url, 2_000)
          if (!avatar) return
          if (profile.local_id) avatars.set(profile.local_id, avatar)
          if (profile.public_id) avatars.set(profile.public_id, avatar)
        })
        items.forEach((item) => {
          item.avatar_url ||= avatars.get(item.user_id) || null
        })
      }
      res.setHeader('Cache-Control', 'private, no-store')
      res.json({ items })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/live', authRequired, async (req, res, next) => {
    let client
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const profile = await resolvedProfile(pool, user)
      const title = cleanText(req.body?.title, 80) || `${profile.name} en vivo`
      const source = sourceOf(req.body?.source)
      const hashtags = cleanHashtags(req.body?.hashtags)
      const coverUrl = cleanText(req.body?.coverUrl ?? req.body?.cover_url, 2_000) || null
      const gameName = cleanText(req.body?.gameName ?? req.body?.game_name, 80) || null
      const category = cleanText(req.body?.category, 40) || null
      const rawVisibility = normalizeId(req.body?.visibility)
      const visibility = ['public', 'followers', 'private'].includes(rawVisibility) ? rawVisibility : 'public'
      const giftsEnabled = req.body?.giftsEnabled ?? req.body?.gifts_enabled ?? true
      const battlesEnabled = req.body?.battlesEnabled ?? req.body?.battles_enabled ?? true
      const liveId = validUuid(req.body?.id) || crypto.randomUUID()

      client = await pool.connect()
      await client.query('BEGIN')
      await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))',
        [`live-create:${identity.displayUserId}`])
      const existing = await streamById(client, liveId)
      if (existing) {
        await client.query('ROLLBACK')
        if (ownsStream(existing, identity)) return res.status(200).json({ stream: existing })
        return res.status(409).json({ error: 'LIVE_ALREADY_EXISTS' })
      }
      await client.query(
        `UPDATE fallback_live_streams
         SET is_active = FALSE,
             viewer_count = 0,
             ended_at = COALESCE(ended_at, NOW()),
             updated_at = NOW()
         WHERE lower(host_user_id) = ANY($1::text[])
           AND is_active = TRUE`,
        [identity.values],
      )
      const { rows } = await client.query(
        `INSERT INTO fallback_live_streams(
            id, host_user_id, host_name, host_avatar_url, title, source, hashtags,
            cover_url, game_name, category, visibility, gifts_enabled, battles_enabled,
            viewer_count, is_active, created_at, updated_at
         )
         VALUES ($1::uuid, $2, $3, $4, $5, $6, $7::jsonb, $8, $9, $10, $11, $12, $13, 0, FALSE, NOW(), NOW())
         RETURNING id::text, host_user_id, host_name, host_avatar_url, title, source, hashtags,
                   cover_url, game_name, category, visibility, gifts_enabled, battles_enabled,
                   viewer_count, peak_viewer_count, total_comments, total_reactions, chat_paused,
                   allow_guest_requests,
                   is_active,
                   started_at, ended_at, created_at, updated_at`,
        [
          liveId,
          identity.displayUserId,
          profile.name,
          profile.avatarUrl,
          title,
          source,
          JSON.stringify(hashtags),
          coverUrl,
          gameName,
          category,
          visibility,
          Boolean(giftsEnabled),
          Boolean(battlesEnabled),
        ],
      )
      await client.query('COMMIT')
      res.status(201).json({ stream: rows[0] })
    } catch (error) {
      if (client) await client.query('ROLLBACK').catch(() => {})
      if (error?.code === '23505') return res.status(409).json({ error: 'LIVE_ALREADY_EXISTS' })
      next(error)
    } finally {
      client?.release()
    }
  })

  app.patch('/v1/data/live/:liveId/state', authRequired, async (req, res, next) => {
    let client = null
    let releaseClient = false
    let transactionOpen = false
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const identity = userIdentity(user)
      client = typeof pool.connect === 'function' ? await pool.connect() : pool
      releaseClient = client !== pool && typeof client.release === 'function'
      await client.query('BEGIN')
      transactionOpen = true
      const existing = await streamById(client, liveId, { forUpdate: true })
      if (!existing) {
        await client.query('ROLLBACK')
        transactionOpen = false
        return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      }
      if (!ownsStream(existing, identity)) {
        await client.query('ROLLBACK')
        transactionOpen = false
        return res.status(403).json({ error: 'LIVE_FORBIDDEN' })
      }

      const hasActive = req.body?.isActive !== undefined || req.body?.is_active !== undefined
      const isActive = booleanValue(req.body?.isActive ?? req.body?.is_active, existing.is_active)
      const source = req.body?.source !== undefined ? sourceOf(req.body.source) : existing.source
      const viewerCount = intBetween(req.body?.viewerCount ?? req.body?.viewer_count, 0, 1_000_000, existing.viewer_count)
      const peakViewerCount = intBetween(
        req.body?.peakViewerCount ?? req.body?.peak_viewer_count,
        0,
        1_000_000,
        existing.peak_viewer_count,
      )
      const totalComments = intBetween(
        req.body?.totalComments ?? req.body?.total_comments,
        0,
        10_000_000,
        existing.total_comments,
      )
      const totalReactions = intBetween(
        req.body?.totalReactions ?? req.body?.total_reactions,
        0,
        100_000_000,
        existing.total_reactions,
      )
      const hasAllowGuestRequests =
        req.body?.allowGuestRequests !== undefined || req.body?.allow_guest_requests !== undefined
      const allowGuestRequests = booleanValue(
        req.body?.allowGuestRequests ?? req.body?.allow_guest_requests,
        existing.allow_guest_requests,
      )
      const transitionedToLive = didLiveStart(existing.is_active, hasActive, isActive)
      const { rows } = await client.query(
        `UPDATE fallback_live_streams
         SET is_active = CASE WHEN $2 THEN $3 ELSE is_active END,
             viewer_count = $4,
             peak_viewer_count = GREATEST(peak_viewer_count, $4, $5),
             total_comments = GREATEST(total_comments, $6),
             total_reactions = GREATEST(total_reactions, $7),
             source = $8,
             allow_guest_requests = CASE WHEN $9 THEN $10 ELSE allow_guest_requests END,
             started_at = CASE WHEN $2 AND $3 THEN COALESCE(started_at, NOW()) ELSE started_at END,
             ended_at = CASE WHEN $2 AND NOT $3 THEN COALESCE(ended_at, NOW()) ELSE ended_at END,
             updated_at = NOW()
         WHERE id = $1::uuid
         RETURNING id::text, host_user_id, host_name, host_avatar_url, title, source, hashtags,
                   cover_url, game_name, category, visibility, gifts_enabled, battles_enabled,
                   viewer_count, peak_viewer_count, total_comments, total_reactions, chat_paused,
                   allow_guest_requests,
                   is_active,
                   started_at, ended_at, created_at, updated_at`,
        [liveId, hasActive, isActive, hasActive && !isActive ? 0 : viewerCount, peakViewerCount, totalComments, totalReactions, source, hasAllowGuestRequests, allowGuestRequests],
      )
      const stream = rows[0]
      if (transitionedToLive && liveFollowerNotificationsEnabled()) {
        await enqueueLiveStartedFollowerNotifications(client, {
          liveId,
          hostUserId: stream.host_user_id,
          hostIdentityIds: identity.values,
          hostName: stream.host_name,
          hostAvatarUrl: stream.host_avatar_url,
        })
      }
      await client.query('COMMIT')
      transactionOpen = false
      res.json({ stream })
      if (transitionedToLive && liveFollowerNotificationsEnabled()) {
        void processPendingLiveFollowerNotifications(pool).catch((error) => {
          console.error('[live-followers] notification dispatch failed', safeErrorLogDetails(error, {
            fallbackCode: 'UNKNOWN',
          }))
        })
      }
    } catch (error) {
      if (transactionOpen && client) await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      if (releaseClient) client.release()
    }
  })

  app.get('/v1/data/live/:liveId/replay', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const stream = await streamById(pool, liveId)
      if (!stream) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      const isOwner = ownsStream(stream, userIdentity(user))
      if ((!stream.replay_published || !stream.replay_url) && !isOwner) {
        return res.status(404).json({ error: 'LIVE_REPLAY_NOT_FOUND' })
      }
      res.setHeader('Cache-Control', 'private, no-store')
      res.json({ replay: { ...stream, chapters: await chaptersForLive(pool, liveId) } })
    } catch (error) {
      next(error)
    }
  })

  app.put('/v1/data/live/:liveId/replay', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const stream = await streamById(pool, liveId)
      if (!stream) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      if (!ownsStream(stream, userIdentity(user))) return res.status(403).json({ error: 'LIVE_FORBIDDEN' })
      if (stream.is_active) return res.status(409).json({ error: 'LIVE_REPLAY_REQUIRES_ENDED_STREAM' })

      const replayUrl = cleanText(req.body?.replayUrl ?? req.body?.replay_url, 2_000)
      const thumbnailUrl = cleanText(req.body?.thumbnailUrl ?? req.body?.thumbnail_url, 2_000) || null
      const durationMs = Number(req.body?.durationMs ?? req.body?.duration_ms)
      const published = req.body?.published !== false
      if (!replayUrl || !Number.isSafeInteger(durationMs) || durationMs < 1_000 || durationMs > 43_200_000) {
        return res.status(400).json({ error: 'LIVE_REPLAY_INVALID' })
      }
      if (!(await ownsStoredObject(pool, user.id, replayUrl, ['live-replays']))) {
        return res.status(403).json({ error: 'LIVE_REPLAY_STORAGE_OWNERSHIP_REQUIRED' })
      }
      if (thumbnailUrl && !(await ownsStoredObject(pool, user.id, thumbnailUrl, ['thumbnails']))) {
        return res.status(403).json({ error: 'LIVE_REPLAY_THUMBNAIL_OWNERSHIP_REQUIRED' })
      }
      const { rows } = await pool.query(
        `UPDATE fallback_live_streams
         SET replay_url = $2,
             replay_thumbnail_url = $3,
             replay_duration_ms = $4,
             replay_published = $5,
             replay_updated_at = NOW(),
             updated_at = NOW()
         WHERE id = $1::uuid
         RETURNING id::text, host_user_id, host_name, host_avatar_url, title, source, hashtags,
                   cover_url, game_name, category, visibility, gifts_enabled, battles_enabled,
                   viewer_count, peak_viewer_count, total_comments, total_reactions, is_active,
                   started_at, ended_at, created_at, updated_at, replay_url, replay_thumbnail_url,
                   replay_duration_ms::float8 AS replay_duration_ms,
                   replay_published, replay_updated_at`,
        [liveId, replayUrl, thumbnailUrl, durationMs, published],
      )
      await publishRealtimeEvent('live-replays', {
        event: 'live_replay_updated',
        liveId,
        published,
      })
      res.json({ replay: { ...rows[0], chapters: await chaptersForLive(pool, liveId) } })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/live/:liveId/replay', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const stream = await streamById(pool, liveId)
      if (!stream) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      if (!ownsStream(stream, userIdentity(user))) return res.status(403).json({ error: 'LIVE_FORBIDDEN' })
      await pool.query(
        `UPDATE fallback_live_streams
         SET replay_url = NULL,
             replay_thumbnail_url = NULL,
             replay_duration_ms = 0,
             replay_published = FALSE,
             replay_updated_at = NOW(),
             updated_at = NOW()
         WHERE id = $1::uuid`,
        [liveId],
      )
      res.json({ ok: true })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/live/:liveId/chapters', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      const title = cleanText(req.body?.title, 80)
      const offsetMs = Number(req.body?.offsetMs ?? req.body?.offset_ms)
      if (!liveId || !title || !Number.isSafeInteger(offsetMs) || offsetMs < 0 || offsetMs > 43_200_000) {
        return res.status(400).json({ error: 'LIVE_CHAPTER_INVALID' })
      }
      const stream = await streamById(pool, liveId)
      if (!stream) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      const identity = userIdentity(user)
      const membership = await moderationMembership(pool, stream, identity)
      if (!membership.canModerate) return res.status(403).json({ error: 'LIVE_MODERATOR_REQUIRED' })
      const timelineEndMs = stream.is_active
        ? Math.max(0, Date.now() - new Date(stream.started_at || stream.created_at).getTime()) + 30_000
        : Number(stream.replay_duration_ms || Math.max(
          0,
          new Date(stream.ended_at || stream.updated_at).getTime() -
            new Date(stream.started_at || stream.created_at).getTime(),
        ))
      if (offsetMs > timelineEndMs) return res.status(400).json({ error: 'LIVE_CHAPTER_OUT_OF_RANGE' })
      const { rows } = await pool.query(
        `INSERT INTO fallback_live_chapters(
           live_id, title, offset_ms, is_highlight, created_by
         )
         VALUES ($1::uuid, $2, $3, $4, $5)
         RETURNING id::text, live_id::text, title, offset_ms::float8 AS offset_ms,
                   is_highlight, created_by, created_at`,
        [
          liveId,
          title,
          offsetMs,
          req.body?.isHighlight !== false && req.body?.is_highlight !== false,
          identity.displayUserId,
        ],
      )
      res.status(201).json({ chapter: rows[0] })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/live/:liveId/chapters/:chapterId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      const chapterId = validUuid(req.params.chapterId)
      if (!liveId || !chapterId) return res.status(400).json({ error: 'LIVE_CHAPTER_INVALID' })
      const stream = await streamById(pool, liveId)
      if (!stream) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      if (!ownsStream(stream, userIdentity(user))) return res.status(403).json({ error: 'LIVE_FORBIDDEN' })
      const removed = await pool.query(
        `DELETE FROM fallback_live_chapters
         WHERE id = $1::uuid AND live_id = $2::uuid`,
        [chapterId, liveId],
      )
      if (removed.rowCount === 0) return res.status(404).json({ error: 'LIVE_CHAPTER_NOT_FOUND' })
      res.json({ ok: true })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/live/:liveId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const stream = await streamById(pool, liveId)
      if (!stream) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      if (!ownsStream(stream, userIdentity(user))) return res.status(403).json({ error: 'LIVE_FORBIDDEN' })
      if (stream.is_active) return res.status(409).json({ error: 'LIVE_STILL_ACTIVE' })
      await pool.query(`DELETE FROM fallback_live_streams WHERE id = $1::uuid`, [liveId])
      res.json({ ok: true })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/live/:liveId/moderation', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const stream = await streamById(pool, liveId)
      if (!stream) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      const identity = userIdentity(user)
      if (!(await canViewStream(pool, stream, identity))) {
        return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      }
      const state = await moderationState(pool, stream, identity)
      res.setHeader('Cache-Control', 'private, no-store')
      res.json({ moderation: state })
    } catch (error) {
      next(error)
    }
  })

  app.patch('/v1/data/live/:liveId/moderation/settings', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const stream = await streamById(pool, liveId)
      if (!stream) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      const identity = userIdentity(user)
      const membership = await moderationMembership(pool, stream, identity)
      if (!membership.canModerate) return res.status(403).json({ error: 'LIVE_MODERATOR_REQUIRED' })

      const hasChatPaused = req.body?.chatPaused !== undefined || req.body?.chat_paused !== undefined
      const hasBlockedWords = req.body?.blockedWords !== undefined || req.body?.blocked_words !== undefined
      if (!hasChatPaused && !hasBlockedWords) {
        return res.status(400).json({ error: 'LIVE_MODERATION_SETTINGS_REQUIRED' })
      }
      if (hasBlockedWords && !membership.isHost) {
        return res.status(403).json({ error: 'LIVE_HOST_REQUIRED' })
      }
      const chatPaused = Boolean(req.body?.chatPaused ?? req.body?.chat_paused)
      const blockedWords = hasBlockedWords
        ? cleanBlockedWords(req.body?.blockedWords ?? req.body?.blocked_words)
        : cleanBlockedWords(stream.blocked_words)
      const { rows } = await pool.query(
        `UPDATE fallback_live_streams
         SET chat_paused = CASE WHEN $2 THEN $3 ELSE chat_paused END,
             blocked_words = CASE WHEN $4 THEN $5::jsonb ELSE blocked_words END,
             updated_at = NOW()
         WHERE id = $1::uuid
         RETURNING id::text, host_user_id, host_name, host_avatar_url, title, source, hashtags,
                   viewer_count, peak_viewer_count, total_comments, total_reactions, chat_paused,
                   blocked_words, is_active, started_at, ended_at, created_at, updated_at`,
        [liveId, hasChatPaused, chatPaused, hasBlockedWords, JSON.stringify(blockedWords)],
      )
      await appendModerationAudit(pool, {
        liveId,
        actorUserId: identity.displayUserId,
        action: 'settings_updated',
        metadata: {
          ...(hasChatPaused ? { chatPaused } : {}),
          ...(hasBlockedWords ? { blockedWordCount: blockedWords.length } : {}),
        },
      })
      const state = await moderationState(pool, rows[0], identity)
      await publishRealtimeEvent(`live-moderation:${liveId}`, {
        event: 'live_moderation_updated',
        liveId,
        chatPaused: state.chat_paused,
      })
      res.json({ moderation: state })
    } catch (error) {
      next(error)
    }
  })

  app.put('/v1/data/live/:liveId/moderators/:userId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      const targetUserId = cleanText(req.params.userId, 200)
      if (!liveId || !targetUserId) return res.status(400).json({ error: 'LIVE_MODERATOR_ID_INVALID' })
      const stream = await streamById(pool, liveId)
      if (!stream) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      const identity = userIdentity(user)
      if (!ownsStream(stream, identity)) return res.status(403).json({ error: 'LIVE_HOST_REQUIRED' })
      if (identity.ids.has(normalizeId(targetUserId))) {
        return res.status(409).json({ error: 'LIVE_HOST_ALREADY_MODERATES' })
      }
      await pool.query(
        `INSERT INTO fallback_live_moderators(live_id, user_id, assigned_by)
         VALUES ($1::uuid, $2, $3)
         ON CONFLICT (live_id, user_id)
         DO UPDATE SET assigned_by = EXCLUDED.assigned_by`,
        [liveId, targetUserId, identity.displayUserId],
      )
      await appendModerationAudit(pool, {
        liveId,
        actorUserId: identity.displayUserId,
        targetUserId,
        action: 'moderator_assigned',
      })
      res.json({ moderation: await moderationState(pool, await streamById(pool, liveId), identity) })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/live/:liveId/moderators/:userId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      const targetUserId = cleanText(req.params.userId, 200)
      if (!liveId || !targetUserId) return res.status(400).json({ error: 'LIVE_MODERATOR_ID_INVALID' })
      const stream = await streamById(pool, liveId)
      if (!stream) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      const identity = userIdentity(user)
      if (!ownsStream(stream, identity)) return res.status(403).json({ error: 'LIVE_HOST_REQUIRED' })
      await pool.query(
        `DELETE FROM fallback_live_moderators
         WHERE live_id = $1::uuid AND lower(user_id) = lower($2)`,
        [liveId, targetUserId],
      )
      await appendModerationAudit(pool, {
        liveId,
        actorUserId: identity.displayUserId,
        targetUserId,
        action: 'moderator_removed',
      })
      res.json({ moderation: await moderationState(pool, await streamById(pool, liveId), identity) })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/live/:liveId/moderation/actions', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      const action = normalizeId(req.body?.action)
      const targetUserId = cleanText(req.body?.targetUserId ?? req.body?.target_user_id, 200)
      const reason = cleanText(req.body?.reason, 300)
      if (!liveId || !['kick', 'ban', 'unban'].includes(action) || !targetUserId) {
        return res.status(400).json({ error: 'LIVE_MODERATION_ACTION_INVALID' })
      }
      const stream = await streamById(pool, liveId)
      if (!stream) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      const identity = userIdentity(user)
      const membership = await moderationMembership(pool, stream, identity)
      if (!membership.canModerate) return res.status(403).json({ error: 'LIVE_MODERATOR_REQUIRED' })
      if (normalizeId(stream.host_user_id) === normalizeId(targetUserId)) {
        return res.status(409).json({ error: 'LIVE_HOST_CANNOT_BE_MODERATED' })
      }
      if (!membership.isHost) {
        const targetModerator = await pool.query(
          `SELECT 1 FROM fallback_live_moderators
           WHERE live_id = $1::uuid AND lower(user_id) = lower($2)
           LIMIT 1`,
          [liveId, targetUserId],
        )
        if (targetModerator.rows.length > 0) {
          return res.status(403).json({ error: 'LIVE_HOST_REQUIRED' })
        }
      }
      if (action === 'ban') {
        await pool.query(
          `INSERT INTO fallback_live_bans(live_id, user_id, reason, banned_by)
           VALUES ($1::uuid, $2, $3, $4)
           ON CONFLICT (live_id, user_id)
           DO UPDATE SET reason = EXCLUDED.reason, banned_by = EXCLUDED.banned_by, created_at = NOW()`,
          [liveId, targetUserId, reason, identity.displayUserId],
        )
      } else if (action === 'unban') {
        await pool.query(
          `DELETE FROM fallback_live_bans
           WHERE live_id = $1::uuid AND lower(user_id) = lower($2)`,
          [liveId, targetUserId],
        )
      }
      await appendModerationAudit(pool, {
        liveId,
        actorUserId: identity.displayUserId,
        targetUserId,
        action: action === 'kick' ? 'viewer_kicked' : `viewer_${action}ned`,
        reason,
      })
      res.json({
        ok: true,
        action,
        target_user_id: targetUserId,
        moderation: await moderationState(pool, await streamById(pool, liveId), identity),
      })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/live/:liveId/comments', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const stream = await streamById(pool, liveId)
      if (!stream) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      const identity = userIdentity(user)
      if (
        (!stream.is_active && !ownsStream(stream, identity)) ||
        !(await canViewStream(pool, stream, identity))
      ) {
        return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      }
      const limit = intBetween(req.query.limit, 1, 200, 80)
      const { rows } = await pool.query(
        `SELECT id::text, live_id::text, user_id, user_name, avatar_url, content, created_at
         FROM fallback_live_comments
         WHERE live_id = $1::uuid
         ORDER BY created_at DESC
         LIMIT $2`,
        [liveId, limit],
      )
      res.setHeader('Cache-Control', 'private, no-store')
      res.json({ items: rows.reverse() })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/live/:liveId/comments', authRequired, limitLiveComments, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const clientId = req.body?.clientCommentId == null ? null : validUuid(req.body.clientCommentId)
      if (req.body?.clientCommentId != null && !clientId) return res.status(400).json({ error: 'LIVE_COMMENT_ID_INVALID' })
      const content = cleanText(req.body?.content, 300)
      if (!content) return res.status(400).json({ error: 'LIVE_COMMENT_REQUIRED' })
      const stream = await streamById(pool, liveId)
      if (!stream?.is_active) return res.status(409).json({ error: 'LIVE_NOT_ACTIVE' })
      const identity = userIdentity(user)
      if (!(await canViewStream(pool, stream, identity))) {
        return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      }
      const membership = await moderationMembership(pool, stream, identity)
      if (!membership.isHost && await isLiveBanned(pool, liveId, identity)) {
        return res.status(403).json({ error: 'LIVE_BANNED' })
      }
      if (stream.chat_paused && !membership.canModerate) {
        return res.status(423).json({ error: 'LIVE_CHAT_PAUSED' })
      }
      if (!membership.canModerate && containsBlockedWord(content, stream.blocked_words)) {
        return res.status(422).json({ error: 'LIVE_COMMENT_BLOCKED' })
      }
      const profile = await resolvedProfile(pool, user)
      const { rows } = await pool.query(
        `WITH inserted AS (
           INSERT INTO fallback_live_comments(id, live_id, user_id, user_name, avatar_url, content)
           VALUES (COALESCE($6::uuid, gen_random_uuid()), $1::uuid, $2, $3, $4, $5)
           ON CONFLICT (id) DO NOTHING
           RETURNING id::text, live_id::text, user_id, user_name, avatar_url, content, created_at
         ), counted AS (
           UPDATE fallback_live_streams
           SET total_comments = total_comments + 1, updated_at = NOW()
           WHERE id = $1::uuid AND EXISTS (SELECT 1 FROM inserted)
           RETURNING id
         )
         SELECT inserted.* FROM inserted JOIN counted ON counted.id::text = inserted.live_id`,
        [liveId, identity.displayUserId, profile.name, profile.avatarUrl, content, clientId],
      )
      if (!rows[0]) {
        if (clientId) {
          const existing = await pool.query(`SELECT id::text, live_id::text, user_id, user_name, avatar_url, content, created_at
            FROM fallback_live_comments WHERE id=$1::uuid AND live_id=$2::uuid AND user_id=$3 AND content=$4`,
          [clientId, liveId, identity.displayUserId, content])
          if (existing.rows[0]) return res.json({ comment: existing.rows[0] })
        }
        return res.status(409).json({ error: 'LIVE_COMMENT_CONFLICT' })
      }
      await publishRealtimeEvent(`live-comments:${liveId}`, {
        event: 'live_comment_created',
        liveId,
        commentId: rows[0].id,
        comment: rows[0],
      }).catch((error) => {
        console.error('[live] Comment saved but realtime notification failed', error)
      })
      res.status(201).json({ comment: rows[0] })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/live/:liveId/comments/:commentId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      const commentId = validUuid(req.params.commentId)
      if (!liveId || !commentId) return res.status(400).json({ error: 'LIVE_COMMENT_ID_INVALID' })
      const stream = await streamById(pool, liveId)
      if (!stream) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      const identity = userIdentity(user)
      const membership = await moderationMembership(pool, stream, identity)
      const { rows } = await pool.query(
        `DELETE FROM fallback_live_comments
         WHERE id = $1::uuid
           AND live_id = $2::uuid
           AND (lower(user_id) = ANY($3::text[]) OR $4::boolean)
         RETURNING id::text`,
        [commentId, liveId, identity.values, membership.canModerate],
      )
      if (rows.length === 0) return res.status(404).json({ error: 'LIVE_COMMENT_NOT_FOUND' })
      await publishRealtimeEvent(`live-comments:${liveId}`, {
        event: 'live_comment_deleted',
        liveId,
        commentId,
      })
      res.json({ ok: true })
    } catch (error) {
      next(error)
    }
  })
}

module.exports = {
  cleanBlockedWords,
  containsBlockedWord,
  ensureLiveTables,
  issueLiveSfuToken,
  liveSupporterRanking,
  liveSupporterLevel,
  liveGuestsBattlesEnabled,
  liveSfuConfiguration,
  registerLiveRoutes,
}
