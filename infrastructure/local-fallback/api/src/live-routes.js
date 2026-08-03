const crypto = require('crypto')
const jwt = require('jsonwebtoken')
const { publishRealtimeEvent } = require('./realtime-publish')

const LIVE_GUESTS_BATTLES_FLAG = 'LIVE_GUESTS_BATTLES_ENABLED'
const LIVE_SFU_FLAG = 'LIVE_SFU_ENABLED'
const DEFAULT_MAX_LIVE_GUESTS = 4
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

function roleGrant(role) {
  const isPublisher = role === 'host' || role === 'guest'
  return {
    roomJoin: true,
    canPublish: isPublisher,
    canSubscribe: true,
    canPublishData: isPublisher,
    canUpdateOwnMetadata: role === 'host' || role === 'guest',
  }
}

function issueLiveSfuToken({ liveId, userId, displayName, role, configuration, now = Math.floor(Date.now() / 1000) }) {
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
        ...roleGrant(role),
        room: liveRoomName(liveId),
      },
      metadata: JSON.stringify({ liveId: normalizeId(liveId), role }),
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
  return normalizeId(value) === 'screen' ? 'screen' : 'camera'
}

function intBetween(value, min, max, fallback = min) {
  const parsed = Number.parseInt(value, 10)
  if (!Number.isFinite(parsed)) return fallback
  return Math.min(max, Math.max(min, parsed))
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
      source TEXT NOT NULL DEFAULT 'camera' CHECK (source IN ('camera', 'screen')),
      hashtags JSONB NOT NULL DEFAULT '[]'::jsonb,
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
    ADD COLUMN IF NOT EXISTS blocked_words JSONB NOT NULL DEFAULT '[]'::jsonb
  `)
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
    CREATE TABLE IF NOT EXISTS fallback_live_guest_invitations (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      live_id UUID NOT NULL REFERENCES fallback_live_streams(id) ON DELETE CASCADE,
      host_user_id TEXT NOT NULL,
      invitee_user_id TEXT NOT NULL,
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
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE (live_id, user_id),
      UNIQUE (live_id, participant_identity)
    )
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
  await pool.query(`
    CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_live_active_battle
    ON fallback_live_battles(live_id)
    WHERE status = 'active'
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

async function streamById(pool, liveId) {
  const { rows } = await pool.query(
    `SELECT id::text, host_user_id, host_name, host_avatar_url, title, source, hashtags,
            viewer_count, peak_viewer_count, total_comments, total_reactions, chat_paused,
            blocked_words, replay_url, replay_thumbnail_url,
            replay_duration_ms::float8 AS replay_duration_ms,
            replay_published, replay_updated_at, is_active,
            started_at, ended_at, created_at, updated_at
     FROM fallback_live_streams
     WHERE id = $1::uuid
     LIMIT 1`,
    [liveId],
  )
  return rows[0] || null
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
  if (!expected || !supplied || supplied !== expected) {
    res.status(404).json({ error: 'NOT_FOUND' })
    return false
  }
  return true
}

async function invitationForUser(pool, liveId, userId) {
  const { rows } = await pool.query(
    `SELECT id::text, live_id::text, host_user_id, invitee_user_id, status,
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
    `SELECT id::text, live_id::text, host_user_id, invitee_user_id, status,
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
    `SELECT id::text, live_id::text, host_user_id, invitee_user_id, status,
            request_nonce, expires_at, responded_at, created_at, updated_at
     FROM fallback_live_guest_invitations
     WHERE live_id = $1::uuid AND id = $2::uuid
     LIMIT 1`,
    [liveId, invitationId],
  )
  return rows[0] || null
}

async function activeGuestCount(pool, liveId) {
  const { rows } = await pool.query(
    `SELECT COUNT(*)::int AS count
     FROM fallback_live_guest_invitations
     WHERE live_id = $1::uuid
       AND status = 'accepted'`,
    [liveId],
  )
  return Number(rows[0]?.count || 0)
}

async function publishLiveCollaborationEvent(liveId, payload) {
  await publishRealtimeEvent(`live-collaboration:${liveId}`, {
    event: 'live_collaboration',
    liveId,
    ...(payload || {}),
  })
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
      if (requestedRole === 'host' && !ownsStream(stream, identity)) {
        return res.status(403).json({ error: 'LIVE_HOST_REQUIRED' })
      }
      if (requestedRole === 'guest') {
        const invitation = await invitationForUser(pool, liveId, identity.displayUserId)
        if (!invitation || invitation.status !== 'accepted') {
          return res.status(403).json({ error: 'LIVE_GUEST_INVITATION_REQUIRED' })
        }
      }
      const participant = issueLiveSfuToken({
        liveId,
        userId: identity.displayUserId,
        displayName: identity.displayName,
        role: requestedRole,
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
        roomName: liveRoomName(liveId),
        token: participant.token,
        expiresAt: participant.expiresAt,
        role: requestedRole,
        maxGuests: configuration.maxGuests,
      })
    } catch (error) {
      next(error)
    }
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
      if (!inviteeUserId || identity.ids.includes(inviteeUserId)) {
        return res.status(400).json({ error: 'LIVE_INVITATION_TARGET_INVALID' })
      }
      const { rows } = await pool.query(
        `INSERT INTO fallback_live_guest_invitations(
           live_id, host_user_id, invitee_user_id, status, request_nonce, expires_at
         )
         VALUES ($1::uuid, $2, $3, 'pending', $4, NOW() + ($5::int * INTERVAL '1 minute'))
         ON CONFLICT (live_id, request_nonce) DO NOTHING
         RETURNING id::text, live_id::text, host_user_id, invitee_user_id, status,
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
        `SELECT id::text, live_id::text, host_user_id, invitee_user_id, status,
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
      const existing = await invitationById(pool, liveId, invitationId)
      if (!existing) return res.status(404).json({ error: 'LIVE_INVITATION_NOT_FOUND' })
      if (!identity.ids.includes(normalizeId(existing.invitee_user_id))) {
        return res.status(403).json({ error: 'LIVE_INVITEE_REQUIRED' })
      }
      if (existing.status !== 'pending' || new Date(existing.expires_at).getTime() <= Date.now()) {
        return res.status(409).json({ error: 'LIVE_INVITATION_NOT_PENDING' })
      }
      if (action === 'accepted' && (await activeGuestCount(pool, liveId)) >= liveSfuConfiguration().maxGuests) {
        return res.status(409).json({ error: 'LIVE_GUEST_LIMIT_REACHED' })
      }
      const { rows } = await pool.query(
        `UPDATE fallback_live_guest_invitations
         SET status = $3, responded_at = NOW(), updated_at = NOW()
         WHERE id = $1::uuid AND live_id = $2::uuid AND status = 'pending' AND expires_at > NOW()
         RETURNING id::text, live_id::text, host_user_id, invitee_user_id, status,
                   request_nonce, expires_at, responded_at, created_at, updated_at`,
        [invitationId, liveId, action],
      )
      const invitation = rows[0]
      if (!invitation) return res.status(409).json({ error: 'LIVE_INVITATION_NOT_PENDING' })
      if (action === 'accepted') {
        await pool.query(
          `INSERT INTO fallback_live_participants(
             live_id, user_id, role, participant_identity, state, updated_at
           ) VALUES ($1::uuid, $2, 'guest', $2, 'invited', NOW())
           ON CONFLICT (live_id, user_id)
           DO UPDATE SET role = 'guest', state = 'invited', updated_at = NOW()`,
          [liveId, identity.displayUserId],
        )
      }
      await publishLiveCollaborationEvent(liveId, {
        type: `guest_invitation_${action}`,
        invitation,
      })
      res.json({ invitation })
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
      const opponentUserId = cleanText(req.body?.opponentUserId || req.body?.opponent_user_id, 160).toLowerCase()
      if (!opponentUserId || identity.ids.includes(opponentUserId)) {
        return res.status(400).json({ error: 'LIVE_BATTLE_OPPONENT_INVALID' })
      }
      const invitation = await invitationForUser(pool, liveId, opponentUserId)
      if (!invitation || invitation.status !== 'accepted') {
        return res.status(409).json({ error: 'LIVE_BATTLE_GUEST_NOT_ACCEPTED' })
      }
      const { rows } = await pool.query(
        `INSERT INTO fallback_live_battles(live_id, left_user_id, right_user_id, status)
         VALUES ($1::uuid, $2, $3, 'active')
         RETURNING id::text, live_id::text, left_user_id, right_user_id, status,
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
        `SELECT id::text, live_id::text, left_user_id, right_user_id, status,
                score_version, left_score, right_score, winner_user_id,
                started_at, ended_at, created_at, updated_at
         FROM fallback_live_battles
         WHERE id = $1::uuid AND live_id = $2::uuid
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
         WHERE id = $1::uuid AND live_id = $2::uuid AND status = 'active'
           AND (lower(left_user_id) = ANY($3::text[]) OR lower(right_user_id) = ANY($3::text[]))
         RETURNING id::text, live_id::text, left_user_id, right_user_id, status,
                   score_version, left_score, right_score, winner_user_id,
                   started_at, ended_at, created_at, updated_at`,
        [battleId, liveId, identity.values],
      )
      if (!rows[0]) return res.status(404).json({ error: 'LIVE_BATTLE_NOT_ACTIVE' })
      await publishLiveCollaborationEvent(liveId, { type: 'battle_ended', battle: rows[0] })
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
        `SELECT id::text, live_id::text, left_user_id, right_user_id, status
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
         RETURNING id::text, live_id::text, left_user_id, right_user_id, status,
                   score_version, left_score, right_score, winner_user_id,
                   started_at, ended_at, created_at, updated_at`,
        [battleId, points],
      )
      await client.query('COMMIT')
      await publishLiveCollaborationEvent(battle.live_id, {
        type: 'battle_score_updated',
        battle: updated.rows[0],
        eventId,
      })
      res.status(201).json({ battle: updated.rows[0], eventId })
    } catch (error) {
      if (client) await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client?.release()
    }
  })

  app.get('/v1/data/live', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const limit = intBetween(req.query.limit, 1, 100, 60)
      const { rows } = await pool.query(
        `SELECT id::text, host_user_id, host_name, host_avatar_url, title, source, hashtags,
                viewer_count, peak_viewer_count, total_comments, total_reactions, chat_paused,
                is_active,
                started_at, ended_at, created_at, updated_at
         FROM fallback_live_streams
         WHERE is_active = TRUE
         ORDER BY viewer_count DESC, started_at DESC NULLS LAST, created_at DESC
         LIMIT $1`,
        [limit],
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
                viewer_count, peak_viewer_count, total_comments, total_reactions, is_active,
                started_at, ended_at, created_at, updated_at, replay_url, replay_thumbnail_url,
                replay_duration_ms::float8 AS replay_duration_ms,
                replay_published, replay_updated_at
         FROM fallback_live_streams
         WHERE is_active = FALSE
           AND (
             ($1::boolean AND lower(host_user_id) = ANY($2::text[])) OR
             (NOT $1::boolean AND replay_published = TRUE AND replay_url IS NOT NULL)
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
      if (!stream.is_active && !ownsStream(stream, userIdentity(user))) {
        return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      }
      res.setHeader('Cache-Control', 'private, no-store')
      res.json({ stream })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/live', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const profile = await resolvedProfile(pool, user)
      const title = cleanText(req.body?.title, 80) || `${profile.name} en vivo`
      const source = sourceOf(req.body?.source)
      const hashtags = cleanHashtags(req.body?.hashtags)
      const liveId = validUuid(req.body?.id) || crypto.randomUUID()

      await pool.query(
        `UPDATE fallback_live_streams
         SET is_active = FALSE,
             viewer_count = 0,
             ended_at = COALESCE(ended_at, NOW()),
             updated_at = NOW()
         WHERE lower(host_user_id) = ANY($1::text[])
           AND is_active = TRUE`,
        [identity.values],
      )
      const { rows } = await pool.query(
        `INSERT INTO fallback_live_streams(
            id, host_user_id, host_name, host_avatar_url, title, source, hashtags,
            viewer_count, is_active, created_at, updated_at
         )
         VALUES ($1::uuid, $2, $3, $4, $5, $6, $7::jsonb, 0, FALSE, NOW(), NOW())
         RETURNING id::text, host_user_id, host_name, host_avatar_url, title, source, hashtags,
                   viewer_count, peak_viewer_count, total_comments, total_reactions, chat_paused,
                   is_active,
                   started_at, ended_at, created_at, updated_at`,
        [liveId, identity.displayUserId, profile.name, profile.avatarUrl, title, source, JSON.stringify(hashtags)],
      )
      res.status(201).json({ stream: rows[0] })
    } catch (error) {
      if (error?.code === '23505') return res.status(409).json({ error: 'LIVE_ALREADY_EXISTS' })
      next(error)
    }
  })

  app.patch('/v1/data/live/:liveId/state', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const existing = await streamById(pool, liveId)
      if (!existing) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      if (!ownsStream(existing, userIdentity(user))) return res.status(403).json({ error: 'LIVE_FORBIDDEN' })

      const hasActive = req.body?.isActive !== undefined || req.body?.is_active !== undefined
      const isActive = Boolean(req.body?.isActive ?? req.body?.is_active)
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
      const { rows } = await pool.query(
        `UPDATE fallback_live_streams
         SET is_active = CASE WHEN $2 THEN $3 ELSE is_active END,
             viewer_count = $4,
             peak_viewer_count = GREATEST(peak_viewer_count, $4, $5),
             total_comments = GREATEST(total_comments, $6),
             total_reactions = GREATEST(total_reactions, $7),
             source = $8,
             started_at = CASE WHEN $2 AND $3 THEN COALESCE(started_at, NOW()) ELSE started_at END,
             ended_at = CASE WHEN $2 AND NOT $3 THEN COALESCE(ended_at, NOW()) ELSE ended_at END,
             updated_at = NOW()
         WHERE id = $1::uuid
         RETURNING id::text, host_user_id, host_name, host_avatar_url, title, source, hashtags,
                   viewer_count, peak_viewer_count, total_comments, total_reactions, chat_paused,
                   is_active,
                   started_at, ended_at, created_at, updated_at`,
        [liveId, hasActive, isActive, hasActive && !isActive ? 0 : viewerCount, peakViewerCount, totalComments, totalReactions, source],
      )
      res.json({ stream: rows[0] })
    } catch (error) {
      next(error)
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
      const state = await moderationState(pool, stream, userIdentity(user))
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
      if (!stream.is_active && !ownsStream(stream, userIdentity(user))) {
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

  app.post('/v1/data/live/:liveId/comments', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const content = cleanText(req.body?.content, 300)
      if (!content) return res.status(400).json({ error: 'LIVE_COMMENT_REQUIRED' })
      const stream = await streamById(pool, liveId)
      if (!stream?.is_active) return res.status(409).json({ error: 'LIVE_NOT_ACTIVE' })
      const identity = userIdentity(user)
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
        `INSERT INTO fallback_live_comments(live_id, user_id, user_name, avatar_url, content)
         VALUES ($1::uuid, $2, $3, $4, $5)
         RETURNING id::text, live_id::text, user_id, user_name, avatar_url, content, created_at`,
        [liveId, identity.displayUserId, profile.name, profile.avatarUrl, content],
      )
      await pool.query(
        `UPDATE fallback_live_streams
         SET total_comments = total_comments + 1, updated_at = NOW()
         WHERE id = $1::uuid`,
        [liveId],
      )
      await publishRealtimeEvent(`live-comments:${liveId}`, {
        event: 'live_comment_created',
        liveId,
        commentId: rows[0].id,
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
  liveGuestsBattlesEnabled,
  liveSfuConfiguration,
  registerLiveRoutes,
}
