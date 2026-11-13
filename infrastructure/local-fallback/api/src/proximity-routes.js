const crypto = require('crypto')
const { blockedProfileIds: blockedIds } = require('./blocked-profile-ids')
const { publishRealtimeEvent } = require('./realtime-publish')

const ALLOWED_GENDERS = new Set(['unspecified', 'woman', 'man'])
const ALLOWED_LOOKING_FOR = new Set(['any', 'woman', 'man'])
const RANDOM_MESSAGE_TYPES = new Set(['text', 'sticker', 'audio', 'image'])
const ACTIVE_BOTTLE_STATUSES = new Set(['searching', 'offered'])
const BOTTLE_OFFER_TIMEOUT_SECONDS = 45
const BOTTLE_AVAILABILITY_TTL_SECONDS = 15
const MAX_ACTIVE_RANDOM_SESSIONS = 20

function normalizeId(value) {
  return String(value || '').trim().toLowerCase()
}

function cleanText(value, maxLength) {
  return String(value || '').trim().slice(0, maxLength)
}

function validUuid(value) {
  const text = normalizeId(value)
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(text)
    ? text
    : null
}

function numberBetween(value, min, max, fallback = null) {
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) return fallback
  return Math.min(max, Math.max(min, parsed))
}

function normalizeGender(value) {
  const gender = normalizeId(value) || 'unspecified'
  return ALLOWED_GENDERS.has(gender) ? gender : 'unspecified'
}

function normalizeLookingFor(value) {
  const lookingFor = normalizeId(value) || 'any'
  return ALLOWED_LOOKING_FOR.has(lookingFor) ? lookingFor : 'any'
}

function preferenceMatches(lookingFor, gender) {
  return normalizeLookingFor(lookingFor) === 'any' || normalizeLookingFor(lookingFor) === normalizeGender(gender)
}

function mutuallyCompatible(left, right) {
  return preferenceMatches(left.looking_for, right.gender) && preferenceMatches(right.looking_for, left.gender)
}

function haversineKm(lat1, lon1, lat2, lon2) {
  const toRadians = (degrees) => degrees * Math.PI / 180
  const earthRadiusKm = 6371.0088
  const latitudeDelta = toRadians(lat2 - lat1)
  const longitudeDelta = toRadians(lon2 - lon1)
  const a = Math.sin(latitudeDelta / 2) ** 2 +
    Math.cos(toRadians(lat1)) * Math.cos(toRadians(lat2)) * Math.sin(longitudeDelta / 2) ** 2
  return earthRadiusKm * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

function approximateDistanceKm(distanceKm) {
  if (!Number.isFinite(distanceKm) || distanceKm < 0) return null
  return Math.max(0.5, Math.round(distanceKm * 2) / 2)
}

function profileArray(profile, key) {
  return Array.isArray(profile?.[key])
    ? profile[key].map((value) => cleanText(value, 80)).filter(Boolean).slice(0, 12)
    : []
}

function profileText(profile, keys, fallback = '') {
  for (const key of keys) {
    const value = profile?.[key]
    if (value !== undefined && value !== null && String(value).trim()) return String(value).trim()
  }
  return fallback
}

async function ensureProximityTables(pool) {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_proximity_presence (
      user_id TEXT PRIMARY KEY,
      latitude NUMERIC(8, 3) NOT NULL,
      longitude NUMERIC(9, 3) NOT NULL,
      radius_km NUMERIC(4, 1) NOT NULL DEFAULT 20,
      gender TEXT NOT NULL DEFAULT 'unspecified',
      looking_for TEXT NOT NULL DEFAULT 'any',
      nearby_enabled BOOLEAN NOT NULL DEFAULT TRUE,
      random_enabled BOOLEAN NOT NULL DEFAULT FALSE,
      expires_at TIMESTAMPTZ NOT NULL DEFAULT NOW() + INTERVAL '2 hours',
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_proximity_active
    ON fallback_proximity_presence(nearby_enabled, expires_at, updated_at DESC)
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_random_chat_queue (
      user_id TEXT PRIMARY KEY,
      gender TEXT NOT NULL DEFAULT 'unspecified',
      looking_for TEXT NOT NULL DEFAULT 'any',
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_random_queue_updated
    ON fallback_random_chat_queue(updated_at)
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_random_bottle_availability (
      user_id TEXT PRIMARY KEY,
      gender TEXT NOT NULL DEFAULT 'unspecified',
      looking_for TEXT NOT NULL DEFAULT 'any',
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_bottle_availability_updated
    ON fallback_random_bottle_availability(updated_at)
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_random_chat_sessions (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      user_a TEXT NOT NULL,
      user_b TEXT NOT NULL,
      status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'ended')),
      user_a_reveal INT NOT NULL DEFAULT 0 CHECK (user_a_reveal BETWEEN 0 AND 3),
      user_b_reveal INT NOT NULL DEFAULT 0 CHECK (user_b_reveal BETWEEN 0 AND 3),
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      ended_at TIMESTAMPTZ
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_random_sessions_users
    ON fallback_random_chat_sessions(status, lower(user_a::text), lower(user_b::text), updated_at DESC)
  `)
  // Read cursors let the anonymous inbox expose a real unread count without
  // mixing those messages into the normal notification stream.
  await pool.query(
    `ALTER TABLE fallback_random_chat_sessions
     ADD COLUMN IF NOT EXISTS user_a_read_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
  )
  await pool.query(
    `ALTER TABLE fallback_random_chat_sessions
     ADD COLUMN IF NOT EXISTS user_b_read_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_random_chat_messages (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      session_id UUID NOT NULL REFERENCES fallback_random_chat_sessions(id) ON DELETE CASCADE,
      sender_id TEXT NOT NULL,
      client_message_id TEXT NOT NULL,
      content TEXT NOT NULL,
      content_type TEXT NOT NULL DEFAULT 'text',
      media_url TEXT,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE(session_id, sender_id, client_message_id)
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_random_messages_session
    ON fallback_random_chat_messages(session_id, created_at)
  `)
  await pool.query(
    `ALTER TABLE fallback_random_chat_messages
     ADD COLUMN IF NOT EXISTS content_type TEXT NOT NULL DEFAULT 'text'`,
  )
  await pool.query(
    `ALTER TABLE fallback_random_chat_messages
     ADD COLUMN IF NOT EXISTS media_url TEXT`,
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_random_chat_bottles (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      sender_id TEXT NOT NULL,
      content TEXT NOT NULL,
      sender_gender TEXT NOT NULL DEFAULT 'unspecified',
      looking_for TEXT NOT NULL DEFAULT 'any',
      status TEXT NOT NULL DEFAULT 'searching'
        CHECK (status IN ('searching', 'offered', 'accepted', 'cancelled', 'expired')),
      current_recipient_id TEXT,
      attempted_recipient_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
      session_id UUID REFERENCES fallback_random_chat_sessions(id) ON DELETE SET NULL,
      offer_started_at TIMESTAMPTZ,
      expires_at TIMESTAMPTZ NOT NULL DEFAULT NOW() + INTERVAL '24 hours',
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_random_bottles_sender
    ON fallback_random_chat_bottles(lower(sender_id::text), status, updated_at DESC)
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_random_bottles_recipient
    ON fallback_random_chat_bottles(lower(current_recipient_id::text), status, offer_started_at)
  `)
}

async function profileById(pool, userId) {
  const { rows } = await pool.query(
    `SELECT u.id, u.supabase_user_id, u.email, u.display_name, u.avatar_url,
            COALESCE(o.profile, '{}'::jsonb) AS profile
     FROM fallback_users u
     LEFT JOIN fallback_profile_overrides o ON o.user_id = u.id
     WHERE lower(u.id::text) = $1
        OR lower(COALESCE(u.supabase_user_id::text, '')) = $1
     LIMIT 1`,
    [normalizeId(userId)],
  )
  const row = rows[0]
  if (!row) return null
  const profile = row.profile || {}
  return {
    id: normalizeId(row.supabase_user_id || row.id),
    displayName: profileText(profile, ['display_name', 'displayName', 'full_name', 'fullName', 'username'], row.display_name || 'Usuario'),
    avatarUrl: profileText(profile, ['avatar_url', 'avatarUrl'], row.avatar_url || '') || null,
    city: profileText(profile, ['city'], ''),
    country: profileText(profile, ['country'], ''),
    interests: profileArray(profile, 'interests'),
    hobbies: profileArray(profile, 'hobbies'),
    breakMode: normalizeId(profileText(profile, ['break_mode', 'breakMode'], 'off')),
  }
}

// Query both participants' canonical and imported identities: stale client state
// must not bypass a block created after an offer or session was established.
async function anonymousPairBlocked(db, identity, peerId) {
  const blocked = await blockedIds(db, identity)
  const { rows } = await db.query(
    `SELECT id::text, supabase_user_id::text FROM fallback_users
       WHERE lower(id::text)=$1 OR lower(COALESCE(supabase_user_id::text,''))=$1 LIMIT 1`,
    [normalizeId(peerId)],
  )
  const peer = rows[0]
  return [peerId, peer?.id, peer?.supabase_user_id].some(id => id && blocked.has(normalizeId(id)))
}

async function validateAnonymousMedia(db, user, identity, sessionId, contentType, rawUrl, content = '') {
  if (contentType === 'text') return rawUrl == null
  if (!rawUrl) return contentType === 'sticker' && Boolean(content)
  // Lazy resolution avoids the existing data-routes/proximity module cycle.
  const { parsePublicStorageObjectUrl, currentUserMayReferenceMessageMedia } = require('./data-routes')
  if (contentType === 'sticker') {
    return currentUserMayReferenceMessageMedia(db, user, identity, {
      mediaUrl: rawUrl, contentType, allowedOwnedBuckets: ['chat-media'],
    })
  }
  const ref = parsePublicStorageObjectUrl(rawUrl, ['chat-media'])
  if (!ref) return false
  const userId = normalizeId(user.id)
  const candidates = [ref.objectPath]
  if (!ref.objectPath.startsWith(userId + '/')) candidates.push(userId + '/' + ref.objectPath)
  const { rows } = await db.query(
    `SELECT name, content_type FROM fallback_storage_objects
       WHERE bucket_id=$1 AND owner_id=$2::uuid AND name=ANY($3::text[]) LIMIT 1`,
    [ref.bucket, userId, candidates],
  )
  const object = rows[0]
  if (!object) return false
  const allowedPrefixes = [...new Set([userId, ...identity.values].map(normalizeId))]
    .map(id => id + '/anonymous/' + sessionId + '/')
  if (!allowedPrefixes.some(prefix => String(object.name).startsWith(prefix))) return false
  const mime = String(object.content_type || '').toLowerCase().split(';')[0]
  return contentType === 'audio' ? mime.startsWith('audio/') : mime.startsWith('image/')
}

async function activeSessionForUser(db, userId) {
  const sessions = await activeSessionsForUser(db, userId)
  return sessions[0] || null
}

async function activeSessionsForUser(db, userId) {
  const { rows } = await db.query(
    `SELECT id::text, user_a, user_b, status, user_a_reveal, user_b_reveal, created_at, updated_at
     FROM fallback_random_chat_sessions
     WHERE status = 'active' AND (lower(user_a::text) = $1 OR lower(user_b::text) = $1)
     ORDER BY updated_at DESC
     LIMIT 50`,
    [normalizeId(userId)],
  )
  return rows
}

function ownsSession(session, identity) {
  return identity.ids.has(normalizeId(session?.user_a)) || identity.ids.has(normalizeId(session?.user_b))
}

async function sessionById(pool, sessionId) {
  const { rows } = await pool.query(
    `SELECT id::text, user_a, user_b, status, user_a_reveal, user_b_reveal, created_at, updated_at
     FROM fallback_random_chat_sessions
     WHERE id = $1::uuid
     LIMIT 1`,
    [sessionId],
  )
  return rows[0] || null
}

async function sessionPayload(pool, session, identity) {
  const viewerId = identity.ids.has(normalizeId(session.user_a))
    ? normalizeId(session.user_a)
    : normalizeId(session.user_b)
  const peerId = viewerId === normalizeId(session.user_a)
    ? normalizeId(session.user_b)
    : normalizeId(session.user_a)
  const ownRevealLevel = viewerId === normalizeId(session.user_a)
    ? Number(session.user_a_reveal || 0)
    : Number(session.user_b_reveal || 0)
  const peerRevealLevel = peerId === normalizeId(session.user_a)
    ? Number(session.user_a_reveal || 0)
    : Number(session.user_b_reveal || 0)
  const peer = await profileById(pool, peerId)
  const readColumn = viewerId === normalizeId(session.user_a)
    ? 'user_a_read_at'
    : 'user_b_read_at'
  const { rows: summaryRows } = await pool.query(
    `SELECT
       (SELECT content FROM fallback_random_chat_messages
        WHERE session_id = $1::uuid ORDER BY created_at DESC LIMIT 1) AS last_message,
       (SELECT sender_id FROM fallback_random_chat_messages
        WHERE session_id = $1::uuid ORDER BY created_at DESC LIMIT 1) AS last_message_sender,
       (SELECT COUNT(*)::int FROM fallback_random_chat_messages
        WHERE session_id = $1::uuid) AS message_count,
       (SELECT COUNT(*)::int FROM fallback_random_chat_messages
        WHERE session_id = $1::uuid
          AND lower(sender_id::text) <> $2
          AND created_at > COALESCE((SELECT ${readColumn}
                                     FROM fallback_random_chat_sessions
                                     WHERE id = $1::uuid), to_timestamp(0))) AS unread_count`,
    [session.id, viewerId],
  )
  const summary = summaryRows[0] || {}
  const alias = `Persona ${String(session.id).replace(/-/g, '').slice(0, 4).toUpperCase()}`
  return {
    id: session.id,
    status: session.status,
    own_reveal_level: ownRevealLevel,
    peer_reveal_level: peerRevealLevel,
    peer: {
      alias,
      display_name: peerRevealLevel >= 2 ? peer?.displayName || alias : alias,
      avatar_url: peerRevealLevel >= 2 ? peer?.avatarUrl || null : null,
      interests: peerRevealLevel >= 1 ? peer?.interests || [] : [],
      hobbies: peerRevealLevel >= 1 ? peer?.hobbies || [] : [],
      user_id: peerRevealLevel >= 3 ? peerId : null,
    },
    last_message: summary.last_message || null,
    last_message_sender: summary.last_message_sender
      ? (identity.ids.has(normalizeId(summary.last_message_sender)) ? 'me' : 'peer')
      : null,
    unread_count: Number(summary.unread_count || 0),
    message_count: Number(summary.message_count || 0),
    created_at: session.created_at,
    updated_at: session.updated_at,
  }
}

// Trust grows from actual conversation, never from a button that lets a
// client skip levels. Four exchanged messages unlock the next level for both
// participants, up to the three levels needed to reveal the normal profile.
async function advanceAnonymousProgress(db, sessionId) {
  const { rows: countRows } = await db.query(
    `SELECT COUNT(*)::int AS total
     FROM fallback_random_chat_messages
     WHERE session_id = $1::uuid`,
    [sessionId],
  )
  const total = Number(countRows[0]?.total || 0)
  const level = Math.min(3, Math.floor(total / 4))
  const { rows } = await db.query(
    `UPDATE fallback_random_chat_sessions
     SET user_a_reveal = GREATEST(user_a_reveal, $2),
         user_b_reveal = GREATEST(user_b_reveal, $2),
         updated_at = NOW()
     WHERE id = $1::uuid
     RETURNING id::text, user_a, user_b, status, user_a_reveal, user_b_reveal,
               created_at, updated_at`,
    [sessionId, level],
  )
  return rows[0] || null
}

function bottleCandidateAllowed({
  senderId,
  candidate,
  attemptedIds,
  blockedIds: blocked,
  existingPeerIds,
  busyRecipientIds,
  saturatedUserIds = new Set(),
}) {
  const candidateId = normalizeId(candidate?.user_id)
  if (!candidateId || candidateId === normalizeId(senderId)) return false
  if (attemptedIds.has(candidateId) || blocked.has(candidateId)) return false
  if (existingPeerIds.has(candidateId) || busyRecipientIds.has(candidateId) || saturatedUserIds.has(candidateId)) return false
  // Bottles are open to everyone, including offers created by older clients.
  // Blocks, prior attempts, existing chats and capacity still apply above.
  return true
}

function bottleOfferRespondable(bottle, identityIds, nowMs = Date.now()) {
  if (!bottle || bottle.status !== 'offered') return false
  if (!identityIds?.has(normalizeId(bottle.current_recipient_id))) return false
  const offerStartedMs = new Date(bottle.offer_started_at).getTime()
  const bottleExpiresMs = new Date(bottle.expires_at).getTime()
  if (!Number.isFinite(offerStartedMs) || !Number.isFinite(bottleExpiresMs)) return false
  return bottleExpiresMs > nowMs && nowMs - offerStartedMs < BOTTLE_OFFER_TIMEOUT_SECONDS * 1000
}

function bottlePayload(row, perspective = 'sender') {
  if (!row) return null
  const attempted = Array.isArray(row.attempted_recipient_ids) ? row.attempted_recipient_ids : []
  return {
    id: String(row.id),
    content: String(row.content || ''),
    status: String(row.status || 'searching'),
    perspective,
    attempt_count: attempted.length,
    offered_at: row.offer_started_at || null,
    expires_at: row.expires_at || null,
    created_at: row.created_at || null,
    updated_at: row.updated_at || null,
  }
}

async function bottleById(db, bottleId, lock = false) {
  const { rows } = await db.query(
    `SELECT id::text, sender_id, content, sender_gender, looking_for, status,
            current_recipient_id, attempted_recipient_ids, session_id::text,
            offer_started_at, expires_at, created_at, updated_at
       FROM fallback_random_chat_bottles
      WHERE id=$1::uuid
      LIMIT 1${lock ? ' FOR UPDATE' : ''}`,
    [bottleId],
  )
  return rows[0] || null
}

async function assignBottleRecipient(db, bottle) {
  if (!bottle || bottle.status !== 'searching') return bottle
  const senderId = normalizeId(bottle.sender_id)
  const attemptedIds = new Set((bottle.attempted_recipient_ids || []).map(normalizeId).filter(Boolean))
  const senderSessions = await activeSessionsForUser(db, senderId)
  const existingPeerIds = new Set(senderSessions.map((session) => (
    normalizeId(session.user_a) === senderId ? normalizeId(session.user_b) : normalizeId(session.user_a)
  )))
  const blocked = await blockedIds(db, { ids: new Set([senderId]) })
  const { rows: busyRows } = await db.query(
    `SELECT current_recipient_id
       FROM fallback_random_chat_bottles
      WHERE status='offered' AND current_recipient_id IS NOT NULL AND id<>$1::uuid`,
    [bottle.id],
  )
  const busyRecipientIds = new Set(busyRows.map((row) => normalizeId(row.current_recipient_id)).filter(Boolean))
  const { rows: saturatedRows } = await db.query(
    `SELECT participant_id
       FROM (
         SELECT lower(user_a::text) AS participant_id FROM fallback_random_chat_sessions WHERE status='active'
         UNION ALL
         SELECT lower(user_b::text) AS participant_id FROM fallback_random_chat_sessions WHERE status='active'
       ) participants
      GROUP BY participant_id
     HAVING COUNT(*) >= $1`,
    [MAX_ACTIVE_RANDOM_SESSIONS],
  )
  const saturatedUserIds = new Set(saturatedRows.map((row) => normalizeId(row.participant_id)).filter(Boolean))
  const { rows: candidates } = await db.query(
    `SELECT user_id, created_at
       FROM fallback_random_bottle_availability
      WHERE updated_at > NOW() - INTERVAL '${BOTTLE_AVAILABILITY_TTL_SECONDS} seconds'
      ORDER BY created_at
      FOR UPDATE SKIP LOCKED`,
  )
  const candidate = candidates.find((entry) => bottleCandidateAllowed({
    senderId,
    candidate: entry,
    attemptedIds,
    blockedIds: blocked,
    existingPeerIds,
    busyRecipientIds,
    saturatedUserIds,
  }))
  if (!candidate) return bottle

  const { rows } = await db.query(
    `UPDATE fallback_random_chat_bottles
        SET status='offered', current_recipient_id=$2, offer_started_at=NOW(), updated_at=NOW()
      WHERE id=$1::uuid AND status='searching'
      RETURNING id::text, sender_id, content, sender_gender, looking_for, status,
                current_recipient_id, attempted_recipient_ids, session_id::text,
                offer_started_at, expires_at, created_at, updated_at`,
    [bottle.id, normalizeId(candidate.user_id)],
  )
  return rows[0] || bottle
}

async function advanceBottle(db, bottleId) {
  let bottle = await bottleById(db, bottleId, true)
  if (!bottle) return null
  if (ACTIVE_BOTTLE_STATUSES.has(bottle.status) && new Date(bottle.expires_at).getTime() <= Date.now()) {
    const { rows } = await db.query(
      `UPDATE fallback_random_chat_bottles
          SET status='expired', current_recipient_id=NULL, offer_started_at=NULL, updated_at=NOW()
        WHERE id=$1::uuid
        RETURNING id::text, sender_id, content, sender_gender, looking_for, status,
                  current_recipient_id, attempted_recipient_ids, session_id::text,
                  offer_started_at, expires_at, created_at, updated_at`,
      [bottle.id],
    )
    return rows[0] || bottle
  }
  const offerExpired = bottle.status === 'offered' && bottle.offer_started_at &&
    (Date.now() - new Date(bottle.offer_started_at).getTime()) >= BOTTLE_OFFER_TIMEOUT_SECONDS * 1000
  if (offerExpired) {
    const attempted = Array.from(new Set([
      ...(bottle.attempted_recipient_ids || []).map(normalizeId),
      normalizeId(bottle.current_recipient_id),
    ].filter(Boolean)))
    const { rows } = await db.query(
      `UPDATE fallback_random_chat_bottles
          SET status='searching', current_recipient_id=NULL, offer_started_at=NULL,
              attempted_recipient_ids=$2::jsonb, updated_at=NOW()
        WHERE id=$1::uuid
        RETURNING id::text, sender_id, content, sender_gender, looking_for, status,
                  current_recipient_id, attempted_recipient_ids, session_id::text,
                  offer_started_at, expires_at, created_at, updated_at`,
      [bottle.id, JSON.stringify(attempted)],
    )
    bottle = rows[0] || bottle
  }
  return bottle.status === 'searching' ? assignBottleRecipient(db, bottle) : bottle
}

async function releaseBottleRecipient(db, bottle) {
  const attempted = Array.from(new Set([
    ...(bottle?.attempted_recipient_ids || []).map(normalizeId),
    normalizeId(bottle?.current_recipient_id),
  ].filter(Boolean)))
  const { rows } = await db.query(
    `UPDATE fallback_random_chat_bottles
        SET status='searching',current_recipient_id=NULL,offer_started_at=NULL,
            attempted_recipient_ids=$2::jsonb,updated_at=NOW()
      WHERE id=$1::uuid AND status='offered'
      RETURNING id::text, sender_id, content, sender_gender, looking_for, status,
                current_recipient_id, attempted_recipient_ids, session_id::text,
                offer_started_at, expires_at, created_at, updated_at`,
    [bottle.id, JSON.stringify(attempted)],
  )
  return rows[0] ? assignBottleRecipient(db, rows[0]) : bottle
}

function registerProximityRoutes(app, { pool, authRequired, getCurrentUser, userIdentity }) {
  app.put('/v1/data/proximity/presence', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      if (req.body?.enabled === false) {
        await pool.query(`DELETE FROM fallback_random_chat_queue WHERE lower(user_id::text) = ANY($1::text[])`, [identity.values])
        await pool.query(`DELETE FROM fallback_proximity_presence WHERE lower(user_id::text) = ANY($1::text[])`, [identity.values])
        return res.json({ enabled: false })
      }
      const latitude = numberBetween(req.body?.latitude, -90, 90)
      const longitude = numberBetween(req.body?.longitude, -180, 180)
      const radiusKm = numberBetween(req.body?.radiusKm ?? req.body?.radius_km, 1, 20, 20)
      if (latitude === null || longitude === null) return res.status(400).json({ error: 'LOCATION_INVALID' })
      const gender = normalizeGender(req.body?.gender)
      const lookingFor = normalizeLookingFor(req.body?.lookingFor ?? req.body?.looking_for)
      const randomEnabled = Boolean(req.body?.randomEnabled ?? req.body?.random_enabled)
      await pool.query(
        `INSERT INTO fallback_proximity_presence(
            user_id, latitude, longitude, radius_km, gender, looking_for,
            nearby_enabled, random_enabled, expires_at, updated_at
         )
         VALUES ($1, $2, $3, $4, $5, $6, TRUE, $7, NOW() + INTERVAL '2 hours', NOW())
         ON CONFLICT (user_id) DO UPDATE SET
            latitude = EXCLUDED.latitude,
            longitude = EXCLUDED.longitude,
            radius_km = EXCLUDED.radius_km,
            gender = EXCLUDED.gender,
            looking_for = EXCLUDED.looking_for,
            nearby_enabled = TRUE,
            random_enabled = EXCLUDED.random_enabled,
            expires_at = NOW() + INTERVAL '2 hours',
            updated_at = NOW()`,
        [
          identity.displayUserId,
          Math.round(latitude * 1000) / 1000,
          Math.round(longitude * 1000) / 1000,
          Math.round(radiusKm * 2) / 2,
          gender,
          lookingFor,
          randomEnabled,
        ],
      )
      res.json({ enabled: true, radius_km: Math.round(radiusKm * 2) / 2, expires_in_minutes: 120 })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/proximity/presence', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      await pool.query(`DELETE FROM fallback_random_chat_queue WHERE lower(user_id::text) = ANY($1::text[])`, [identity.values])
      await pool.query(`DELETE FROM fallback_proximity_presence WHERE lower(user_id::text) = ANY($1::text[])`, [identity.values])
      res.json({ enabled: false })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/proximity/nearby', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const { rows: ownRows } = await pool.query(
        `SELECT user_id, latitude::float8, longitude::float8, radius_km::float8
         FROM fallback_proximity_presence
         WHERE lower(user_id::text) = ANY($1::text[]) AND nearby_enabled = TRUE AND expires_at > NOW()
         LIMIT 1`,
        [identity.values],
      )
      const own = ownRows[0]
      if (!own) return res.status(409).json({ error: 'PROXIMITY_NOT_ENABLED' })
      const requestedRadius = numberBetween(req.query.radiusKm ?? req.query.radius_km, 1, 20, own.radius_km)
      const radiusKm = Math.min(requestedRadius, own.radius_km)
      const blocked = await blockedIds(pool, identity)
      const { rows } = await pool.query(
        `SELECT user_id, latitude::float8, longitude::float8, radius_km::float8, updated_at
         FROM fallback_proximity_presence
         WHERE nearby_enabled = TRUE
           AND expires_at > NOW()
           AND NOT (lower(user_id::text) = ANY($1::text[]))
         ORDER BY updated_at DESC
         LIMIT 500`,
        [identity.values],
      )
      const candidates = []
      for (const candidate of rows) {
        const candidateId = normalizeId(candidate.user_id)
        if (blocked.has(candidateId)) continue
        const distance = haversineKm(own.latitude, own.longitude, candidate.latitude, candidate.longitude)
        if (distance > Math.min(radiusKm, Number(candidate.radius_km || 20))) continue
        const profile = await profileById(pool, candidateId)
        if (!profile || profile.breakMode === 'hidden') continue
        candidates.push({
          user_id: candidateId,
          display_name: profile.displayName,
          avatar_url: profile.avatarUrl,
          city: profile.city,
          country: profile.country,
          interests: profile.interests.slice(0, 5),
          distance_km: approximateDistanceKm(distance),
        })
      }
      candidates.sort((left, right) => left.distance_km - right.distance_km)
      res.setHeader('Cache-Control', 'private, no-store')
      res.json({ items: candidates.slice(0, 100), radius_km: radiusKm })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/proximity/random/match', authRequired, async (req, res, next) => {
    const client = await pool.connect()
    try {
      const user = await getCurrentUser(client, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const userId = identity.displayUserId
      const gender = normalizeGender(req.body?.gender)
      const lookingFor = normalizeLookingFor(req.body?.lookingFor ?? req.body?.looking_for)
      await client.query('BEGIN')
      await client.query('SELECT pg_advisory_xact_lock($1)', [728144])
      await client.query(`DELETE FROM fallback_random_chat_queue WHERE updated_at < NOW() - INTERVAL '10 minutes'`)
      const existingSessions = await activeSessionsForUser(client, userId)
      if (existingSessions.length >= 20) {
        await client.query('ROLLBACK')
        return res.status(409).json({ error: 'RANDOM_ACTIVE_SESSION_LIMIT' })
      }
      // Random chat is global. Location presence is only used by the nearby
      // radar; a user can join the anonymous queue without sharing location.
      await client.query(
        `UPDATE fallback_proximity_presence
         SET random_enabled = TRUE,
             gender = $2,
             looking_for = $3,
             expires_at = NOW() + INTERVAL '2 hours',
             updated_at = NOW()
         WHERE lower(user_id::text) = ANY($1::text[])`,
        [identity.values, gender, lookingFor],
      )
      await client.query(
        `INSERT INTO fallback_random_chat_queue(user_id, gender, looking_for, created_at, updated_at)
         VALUES ($1, $2, $3, NOW(), NOW())
         ON CONFLICT (user_id) DO UPDATE SET gender = EXCLUDED.gender, looking_for = EXCLUDED.looking_for, updated_at = NOW()`,
        [userId, gender, lookingFor],
      )
      const blocked = await blockedIds(client, identity)
      const { rows: queueRows } = await client.query(
        `SELECT q.user_id, q.gender, q.looking_for
         FROM fallback_random_chat_queue q
         WHERE lower(q.user_id::text) <> $1
         ORDER BY q.created_at
         FOR UPDATE OF q SKIP LOCKED`,
        [normalizeId(userId)],
      )
      const existingPeerIds = new Set(existingSessions.map((session) => (
        normalizeId(session.user_a) === normalizeId(userId)
          ? normalizeId(session.user_b)
          : normalizeId(session.user_a)
      )))
      const candidate = queueRows.find((entry) => {
        const candidateId = normalizeId(entry.user_id)
        if (
          blocked.has(candidateId) ||
          existingPeerIds.has(candidateId) ||
          !mutuallyCompatible({ gender, looking_for: lookingFor }, entry)
        ) return false
        return true
      })
      if (!candidate) {
        await client.query('COMMIT')
        return res.status(202).json({ status: 'waiting' })
      }
      const { rows: sessions } = await client.query(
        `INSERT INTO fallback_random_chat_sessions(user_a, user_b, status, created_at, updated_at)
         VALUES ($1, $2, 'active', NOW(), NOW())
         RETURNING id::text, user_a, user_b, status, user_a_reveal, user_b_reveal, created_at, updated_at`,
        [normalizeId(candidate.user_id), userId],
      )
      await client.query(
        `DELETE FROM fallback_random_chat_queue WHERE lower(user_id::text) = ANY($1::text[])`,
        [[normalizeId(candidate.user_id), normalizeId(userId)]],
      )
      await client.query('COMMIT')
      const session = sessions[0]
      void publishRealtimeEvent(`random-chat-user:${normalizeId(candidate.user_id)}`, { event: 'random_match_found', sessionId: session.id })
      void publishRealtimeEvent(`random-chat-user:${normalizeId(userId)}`, { event: 'random_match_found', sessionId: session.id })
      res.json({ status: 'matched', session: await sessionPayload(pool, session, identity) })
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client.release()
    }
  })

  app.get('/v1/data/proximity/random/status', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const { rows } = await pool.query(
        `SELECT 1 FROM fallback_random_chat_queue WHERE lower(user_id::text) = ANY($1::text[]) AND updated_at > NOW() - INTERVAL '10 minutes' LIMIT 1`,
        [identity.values],
      )
      // An existing conversation must not consume the polling result while the
      // same user is actively looking for an additional anonymous match.
      if (rows[0]) return res.json({ status: 'waiting' })
      const session = await activeSessionForUser(pool, identity.displayUserId)
      if (session) return res.json({ status: 'matched', session: await sessionPayload(pool, session, identity) })
      res.json({ status: 'idle' })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/proximity/random/sessions', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const sessions = await activeSessionsForUser(pool, identity.displayUserId)
      const items = await Promise.all(sessions.map((session) => sessionPayload(pool, session, identity)))
      res.setHeader('Cache-Control', 'private, no-store')
      res.json({ items })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/proximity/random/queue', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      await pool.query(`DELETE FROM fallback_random_chat_queue WHERE lower(user_id::text) = ANY($1::text[])`, [identity.values])
      await pool.query(
        `UPDATE fallback_proximity_presence SET random_enabled = FALSE, updated_at = NOW() WHERE lower(user_id::text) = ANY($1::text[])`,
        [identity.values],
      )
      res.json({ status: 'idle' })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/proximity/random/availability', authRequired, async (req, res, next) => {
    const client = await pool.connect()
    try {
      const user = await getCurrentUser(client, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const userId = normalizeId(identity.displayUserId)
      const gender = 'unspecified'
      const lookingFor = 'any'
      await client.query('BEGIN')
      await client.query('SELECT pg_advisory_xact_lock($1)', [728145])
      await client.query(`DELETE FROM fallback_random_bottle_availability WHERE updated_at < NOW() - INTERVAL '1 minute'`)
      const activeSessions = await activeSessionsForUser(client, userId)
      if (activeSessions.length >= MAX_ACTIVE_RANDOM_SESSIONS) {
        await client.query(
          `DELETE FROM fallback_random_bottle_availability WHERE lower(user_id::text)=ANY($1::text[])`,
          [identity.values],
        )
        await client.query('COMMIT')
        return res.status(409).json({ error: 'RANDOM_ACTIVE_SESSION_LIMIT' })
      }
      await client.query(
        `INSERT INTO fallback_random_bottle_availability(user_id, gender, looking_for, created_at, updated_at)
         VALUES($1,$2,$3,NOW(),NOW())
         ON CONFLICT(user_id) DO UPDATE SET gender=EXCLUDED.gender,looking_for=EXCLUDED.looking_for,updated_at=NOW()`,
        [userId, gender, lookingFor],
      )
      const pending = await client.query(
        `SELECT id::text FROM fallback_random_chat_bottles
          WHERE status IN ('searching','offered') AND expires_at>NOW()
          ORDER BY created_at LIMIT 25`,
      )
      for (const row of pending.rows) await advanceBottle(client, row.id)
      const incoming = await client.query(
        `SELECT id::text, sender_id, content, sender_gender, looking_for, status,
                current_recipient_id, attempted_recipient_ids, session_id::text,
                offer_started_at, expires_at, created_at, updated_at
           FROM fallback_random_chat_bottles
          WHERE status='offered' AND lower(current_recipient_id::text)=ANY($1::text[])
            AND offer_started_at > NOW() - INTERVAL '${BOTTLE_OFFER_TIMEOUT_SECONDS} seconds'
          ORDER BY offer_started_at LIMIT 1`,
        [identity.values],
      )
      await client.query('COMMIT')
      const bottle = incoming.rows[0] || null
      res.json({ status: bottle ? 'offered' : 'available', bottle: bottlePayload(bottle, 'recipient') })
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client.release()
    }
  })

  app.delete('/v1/data/proximity/random/availability', authRequired, async (req, res, next) => {
    const client = await pool.connect()
    try {
      const user = await getCurrentUser(client, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      await client.query('BEGIN')
      await client.query('SELECT pg_advisory_xact_lock($1)', [728145])
      const offered = await client.query(
        `SELECT id::text, sender_id, content, sender_gender, looking_for, status,
                current_recipient_id, attempted_recipient_ids, session_id::text,
                offer_started_at, expires_at, created_at, updated_at
           FROM fallback_random_chat_bottles
          WHERE status='offered' AND lower(current_recipient_id::text)=ANY($1::text[])
          FOR UPDATE`,
        [identity.values],
      )
      for (const bottle of offered.rows) await releaseBottleRecipient(client, bottle)
      await client.query(
        `DELETE FROM fallback_random_bottle_availability WHERE lower(user_id::text)=ANY($1::text[])`,
        [identity.values],
      )
      await client.query('COMMIT')
      res.json({ status: 'idle' })
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client.release()
    }
  })

  app.post('/v1/data/proximity/random/bottles', authRequired, async (req, res, next) => {
    const client = await pool.connect()
    try {
      const user = await getCurrentUser(client, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const userId = normalizeId(identity.displayUserId)
      const content = cleanText(req.body?.content, 500)
      if (!content) return res.status(400).json({ error: 'BOTTLE_MESSAGE_REQUIRED' })
      await client.query('BEGIN')
      await client.query('SELECT pg_advisory_xact_lock($1)', [728145])
      await client.query(
        `UPDATE fallback_random_chat_bottles
            SET status='expired',current_recipient_id=NULL,offer_started_at=NULL,updated_at=NOW()
          WHERE lower(sender_id::text)=ANY($1::text[])
            AND status IN ('searching','offered') AND expires_at<=NOW()`,
        [identity.values],
      )
      const activeSessions = await activeSessionsForUser(client, userId)
      if (activeSessions.length >= MAX_ACTIVE_RANDOM_SESSIONS) {
        await client.query('ROLLBACK')
        return res.status(409).json({ error: 'RANDOM_ACTIVE_SESSION_LIMIT' })
      }
      const existing = await client.query(
        `SELECT id::text FROM fallback_random_chat_bottles
          WHERE lower(sender_id::text)=ANY($1::text[]) AND status IN ('searching','offered')
          ORDER BY created_at DESC LIMIT 1`,
        [identity.values],
      )
      if (existing.rows[0]) {
        await client.query('ROLLBACK')
        return res.status(409).json({ error: 'BOTTLE_ALREADY_SEARCHING', bottleId: existing.rows[0].id })
      }
      const inserted = await client.query(
        `INSERT INTO fallback_random_chat_bottles(sender_id,content,sender_gender,looking_for)
         VALUES($1,$2,$3,$4)
         RETURNING id::text, sender_id, content, sender_gender, looking_for, status,
                   current_recipient_id, attempted_recipient_ids, session_id::text,
                   offer_started_at, expires_at, created_at, updated_at`,
        [userId, content, 'unspecified', 'any'],
      )
      const bottle = await assignBottleRecipient(client, inserted.rows[0])
      await client.query('COMMIT')
      if (bottle?.current_recipient_id) {
        void publishRealtimeEvent(`random-chat-user:${normalizeId(bottle.current_recipient_id)}`, {
          event: 'anonymous_bottle_offered', bottleId: bottle.id,
        })
      }
      res.status(201).json({ status: bottle?.status || 'searching', bottle: bottlePayload(bottle, 'sender') })
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client.release()
    }
  })

  app.get('/v1/data/proximity/random/bottles/outgoing', authRequired, async (req, res, next) => {
    const client = await pool.connect()
    try {
      const user = await getCurrentUser(client, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      await client.query('BEGIN')
      await client.query('SELECT pg_advisory_xact_lock($1)', [728145])
      const found = await client.query(
        `SELECT id::text FROM fallback_random_chat_bottles
          WHERE lower(sender_id::text)=ANY($1::text[])
            AND (status IN ('searching','offered') OR (status='accepted' AND updated_at>NOW()-INTERVAL '5 minutes'))
          ORDER BY created_at DESC LIMIT 1`,
        [identity.values],
      )
      const bottle = found.rows[0] ? await advanceBottle(client, found.rows[0].id) : null
      const session = bottle?.status === 'accepted' && bottle.session_id
        ? await sessionById(client, bottle.session_id)
        : null
      const responseSession = session ? await sessionPayload(client, session, identity) : null
      await client.query('COMMIT')
      res.setHeader('Cache-Control', 'private, no-store')
      res.json({ status: bottle?.status || 'idle', bottle: bottlePayload(bottle, 'sender'), session: responseSession })
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client.release()
    }
  })

  app.get('/v1/data/proximity/random/bottles/incoming', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const { rows } = await pool.query(
        `SELECT id::text, sender_id, content, sender_gender, looking_for, status,
                current_recipient_id, attempted_recipient_ids, session_id::text,
                offer_started_at, expires_at, created_at, updated_at
           FROM fallback_random_chat_bottles
          WHERE status='offered' AND lower(current_recipient_id::text)=ANY($1::text[])
            AND offer_started_at > NOW() - INTERVAL '${BOTTLE_OFFER_TIMEOUT_SECONDS} seconds'
          ORDER BY offer_started_at LIMIT 1`,
        [identity.values],
      )
      const bottle = rows[0] || null
      res.setHeader('Cache-Control', 'private, no-store')
      res.json({ status: bottle ? 'offered' : 'idle', bottle: bottlePayload(bottle, 'recipient') })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/proximity/random/bottles/:bottleId/respond', authRequired, async (req, res, next) => {
    const client = await pool.connect()
    try {
      const user = await getCurrentUser(client, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const bottleId = validUuid(req.params.bottleId)
      if (!bottleId) return res.status(400).json({ error: 'BOTTLE_ID_INVALID' })
      const action = normalizeId(req.body?.action)
      if (!['accept', 'decline'].includes(action)) return res.status(400).json({ error: 'BOTTLE_RESPONSE_INVALID' })
      await client.query('BEGIN')
      await client.query('SELECT pg_advisory_xact_lock($1)', [728145])
      const bottle = await advanceBottle(client, bottleId)
      if (!bottleOfferRespondable(bottle, identity.ids)) {
        // advanceBottle may have just expired and reassigned this offer. Keep that
        // transition even though the former recipient can no longer respond.
        await client.query('COMMIT')
        return res.status(404).json({ error: 'BOTTLE_OFFER_NOT_FOUND' })
      }
      if (action === 'decline') {
        const reassigned = await releaseBottleRecipient(client, bottle)
        await client.query('COMMIT')
        void publishRealtimeEvent(`random-chat-user:${normalizeId(bottle.sender_id)}`, {
          event: 'anonymous_bottle_reassigned', bottleId: bottle.id,
        })
        if (reassigned?.current_recipient_id) {
          void publishRealtimeEvent(`random-chat-user:${normalizeId(reassigned.current_recipient_id)}`, {
            event: 'anonymous_bottle_offered', bottleId: reassigned.id,
          })
        }
        return res.json({ status: 'declined' })
      }

      const recipientId = normalizeId(identity.displayUserId)
      const senderId = normalizeId(bottle.sender_id)
      if (await anonymousPairBlocked(client, identity, senderId)) {
        await releaseBottleRecipient(client, bottle)
        await client.query('COMMIT')
        return res.status(403).json({ error: 'RANDOM_PAIR_BLOCKED' })
      }
      const senderSessions = await activeSessionsForUser(client, senderId)
      const recipientSessions = await activeSessionsForUser(client, recipientId)
      const alreadyConnected = senderSessions.some((session) => (
        [normalizeId(session.user_a), normalizeId(session.user_b)].includes(recipientId)
      ))
      if (senderSessions.length >= MAX_ACTIVE_RANDOM_SESSIONS ||
          recipientSessions.length >= MAX_ACTIVE_RANDOM_SESSIONS ||
          alreadyConnected) {
        let reassigned = null
        if (senderSessions.length >= MAX_ACTIVE_RANDOM_SESSIONS) {
          await client.query(
            `UPDATE fallback_random_chat_bottles
                SET status='cancelled',current_recipient_id=NULL,offer_started_at=NULL,updated_at=NOW()
              WHERE id=$1::uuid`,
            [bottle.id],
          )
        } else {
          reassigned = await releaseBottleRecipient(client, bottle)
        }
        await client.query('COMMIT')
        if (reassigned?.current_recipient_id) {
          void publishRealtimeEvent(`random-chat-user:${normalizeId(reassigned.current_recipient_id)}`, {
            event: 'anonymous_bottle_offered', bottleId: reassigned.id,
          })
        }
        return res.status(409).json({
          error: alreadyConnected ? 'RANDOM_ALREADY_CONNECTED' : 'RANDOM_ACTIVE_SESSION_LIMIT',
        })
      }
      const sessions = await client.query(
        `INSERT INTO fallback_random_chat_sessions(user_a,user_b,status,created_at,updated_at)
         VALUES($1,$2,'active',NOW(),NOW())
         RETURNING id::text,user_a,user_b,status,user_a_reveal,user_b_reveal,created_at,updated_at`,
        [senderId, recipientId],
      )
      const session = sessions.rows[0]
      await client.query(
        `INSERT INTO fallback_random_chat_messages(session_id,sender_id,client_message_id,content,content_type)
         VALUES($1::uuid,$2,$3,$4,'text')
         ON CONFLICT(session_id,sender_id,client_message_id) DO NOTHING`,
        [session.id, senderId, bottle.id, bottle.content],
      )
      await client.query(
        `UPDATE fallback_random_chat_sessions SET updated_at=NOW() WHERE id=$1::uuid`,
        [session.id],
      )
      await client.query(
        `UPDATE fallback_random_chat_bottles
            SET status='accepted',session_id=$2::uuid,updated_at=NOW()
          WHERE id=$1::uuid`,
        [bottle.id, session.id],
      )
      await client.query(
        `DELETE FROM fallback_random_bottle_availability WHERE lower(user_id::text)=ANY($1::text[])`,
        [[senderId, recipientId]],
      )
      const payload = await sessionPayload(client, session, identity)
      await client.query('COMMIT')
      void publishRealtimeEvent(`random-chat-user:${senderId}`, {
        event: 'anonymous_bottle_accepted', bottleId: bottle.id, sessionId: session.id,
      })
      void publishRealtimeEvent(`random-chat:${session.id}`, {
        event: 'random_message_created', sessionId: session.id,
      })
      res.json({ status: 'accepted', session: payload })
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client.release()
    }
  })

  app.delete('/v1/data/proximity/random/bottles/:bottleId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const bottleId = validUuid(req.params.bottleId)
      if (!bottleId) return res.status(400).json({ error: 'BOTTLE_ID_INVALID' })
      const { rows } = await pool.query(
        `UPDATE fallback_random_chat_bottles
            SET status='cancelled',current_recipient_id=NULL,offer_started_at=NULL,updated_at=NOW()
          WHERE id=$1::uuid AND lower(sender_id::text)=ANY($2::text[])
            AND status IN ('searching','offered')
          RETURNING id::text`,
        [bottleId, identity.values],
      )
      if (!rows[0]) return res.status(404).json({ error: 'BOTTLE_NOT_FOUND' })
      res.json({ status: 'cancelled' })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/proximity/random/sessions/:sessionId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const sessionId = validUuid(req.params.sessionId)
      if (!sessionId) return res.status(400).json({ error: 'SESSION_ID_INVALID' })
      const identity = userIdentity(user)
      const session = await sessionById(pool, sessionId)
      if (!session || !ownsSession(session, identity)) return res.status(404).json({ error: 'SESSION_NOT_FOUND' })
      res.json({ session: await sessionPayload(pool, session, identity) })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/proximity/random/sessions/:sessionId/messages', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const sessionId = validUuid(req.params.sessionId)
      if (!sessionId) return res.status(400).json({ error: 'SESSION_ID_INVALID' })
      const identity = userIdentity(user)
      const session = await sessionById(pool, sessionId)
      if (!session || !ownsSession(session, identity)) return res.status(404).json({ error: 'SESSION_NOT_FOUND' })
      const { rows } = await pool.query(
        `SELECT id::text, sender_id, client_message_id, content, content_type, media_url, created_at
         FROM (SELECT * FROM fallback_random_chat_messages WHERE session_id = $1::uuid
           ORDER BY created_at DESC, id DESC LIMIT 500) recent
         ORDER BY created_at, id`,
        [sessionId],
      )
      const readColumn = identity.ids.has(normalizeId(session.user_a))
        ? 'user_a_read_at'
        : 'user_b_read_at'
      const peerReadAt = identity.ids.has(normalizeId(session.user_a)) ? session.user_b_read_at : session.user_a_read_at
      const latestVisible = rows.at(-1)?.created_at
      if (latestVisible && req.query.markRead !== 'false') {
        const updated = await pool.query(
          `UPDATE fallback_random_chat_sessions SET ${readColumn} = $2::timestamptz
           WHERE id = $1::uuid AND ${readColumn} < $2::timestamptz RETURNING id`, [sessionId, latestVisible])
        if (updated.rows.length) void publishRealtimeEvent(`random-chat:${sessionId}`, { event: 'random_message_read', sessionId })
      }
      res.json({
        items: rows.map((row) => ({
          id: row.id,
          client_message_id: row.client_message_id,
          sender: identity.ids.has(normalizeId(row.sender_id)) ? 'me' : 'peer',
          content: row.content,
          content_type: row.content_type || 'text',
          media_url: row.media_url || null,
          created_at: row.created_at,
          is_read: identity.ids.has(normalizeId(row.sender_id)) && new Date(row.created_at).getTime() <= new Date(peerReadAt || 0).getTime(),
        })),
      })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/proximity/random/sessions/:sessionId/messages', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const sessionId = validUuid(req.params.sessionId)
      if (!sessionId) return res.status(400).json({ error: 'SESSION_ID_INVALID' })
      const identity = userIdentity(user)
      const session = await sessionById(pool, sessionId)
      if (!session || !ownsSession(session, identity) || session.status !== 'active') {
        return res.status(404).json({ error: 'SESSION_NOT_FOUND' })
      }
      const peerId = identity.ids.has(normalizeId(session.user_a)) ? session.user_b : session.user_a
      if (await anonymousPairBlocked(pool, identity, peerId)) {
        return res.status(403).json({ error: 'RANDOM_PAIR_BLOCKED' })
      }
      const content = cleanText(req.body?.content, 1000)
      const contentType = normalizeId(req.body?.contentType ?? req.body?.content_type) || 'text'
      const mediaUrl = cleanText(req.body?.mediaUrl ?? req.body?.media_url, 1200) || null
      const clientMessageId = validUuid(req.body?.clientMessageId ?? req.body?.client_message_id) || crypto.randomUUID()
      if (!RANDOM_MESSAGE_TYPES.has(contentType)) {
        return res.status(400).json({ error: 'RANDOM_MESSAGE_TYPE_INVALID' })
      }
      const { rows: countRows } = await pool.query(
        `SELECT COUNT(*)::int AS total FROM fallback_random_chat_messages WHERE session_id = $1::uuid`,
        [sessionId],
      )
      const currentMessageCount = Number(countRows[0]?.total || 0)
      // Capabilities depend on messages already accepted by the server.
      // Match the composer and ViewModel: audio after3, stickers after6, images after9.
      const requiredMessages = { text: 0, audio: 3, sticker: 6, image: 9 }[contentType]
      if (currentMessageCount < requiredMessages) {
        return res.status(403).json({ error: 'RANDOM_MESSAGE_TYPE_LOCKED' })
      }
      if (contentType === 'text' && !content) return res.status(400).json({ error: 'MESSAGE_REQUIRED' })
      if (contentType !== 'text' && !mediaUrl && !content) {
        return res.status(400).json({ error: 'MESSAGE_MEDIA_REQUIRED' })
      }
      if (!(await validateAnonymousMedia(pool, user, identity, sessionId, contentType, mediaUrl, content))) {
        return res.status(403).json({ error: 'RANDOM_MESSAGE_MEDIA_FORBIDDEN' })
      }
      let { rows } = await pool.query(
        `INSERT INTO fallback_random_chat_messages(
           session_id, sender_id, client_message_id, content, content_type, media_url
         )
         VALUES ($1::uuid, $2, $3, $4, $5, $6)
         ON CONFLICT (session_id, sender_id, client_message_id) DO NOTHING
         RETURNING id::text, client_message_id, content, content_type, media_url, created_at`,
        [sessionId, identity.displayUserId, clientMessageId, content, contentType, mediaUrl],
      )
      if (!rows[0]) {
        const existing = await pool.query(
          `SELECT id::text, client_message_id, content, content_type, media_url, created_at
           FROM fallback_random_chat_messages
           WHERE session_id = $1::uuid AND lower(sender_id::text) = $2 AND client_message_id = $3
           LIMIT 1`,
          [sessionId, normalizeId(identity.displayUserId), clientMessageId],
        )
        rows = existing.rows
      }
      await advanceAnonymousProgress(pool, sessionId)
      await pool.query(`UPDATE fallback_random_chat_sessions SET updated_at = NOW() WHERE id = $1::uuid`, [sessionId])
      void publishRealtimeEvent(`random-chat:${sessionId}`, { event: 'random_message_created', sessionId, messageId: rows[0]?.id })
      res.status(201).json({ message: { ...rows[0], sender: 'me' } })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/proximity/random/sessions/:sessionId/reveal', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const sessionId = validUuid(req.params.sessionId)
      if (!sessionId) return res.status(400).json({ error: 'SESSION_ID_INVALID' })
      const identity = userIdentity(user)
      const session = await sessionById(pool, sessionId)
      if (!session || !ownsSession(session, identity) || session.status !== 'active') {
        return res.status(404).json({ error: 'SESSION_NOT_FOUND' })
      }
      // Kept as a compatibility endpoint for older clients, but the server
      // no longer accepts a requested level. Progress is derived exclusively
      // from exchanged messages in advanceAnonymousProgress().
      const progressed = await advanceAnonymousProgress(pool, sessionId)
      const rows = progressed ? [progressed] : []
      void publishRealtimeEvent(`random-chat:${sessionId}`, { event: 'random_reveal_updated', sessionId })
      res.json({ session: await sessionPayload(pool, rows[0], identity) })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/proximity/random/sessions/:sessionId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const sessionId = validUuid(req.params.sessionId)
      if (!sessionId) return res.status(400).json({ error: 'SESSION_ID_INVALID' })
      const identity = userIdentity(user)
      const session = await sessionById(pool, sessionId)
      if (!session || !ownsSession(session, identity)) return res.status(404).json({ error: 'SESSION_NOT_FOUND' })
      await pool.query(
        `UPDATE fallback_random_chat_sessions SET status = 'ended', ended_at = NOW(), updated_at = NOW() WHERE id = $1::uuid`,
        [sessionId],
      )
      void publishRealtimeEvent(`random-chat:${sessionId}`, { event: 'random_session_ended', sessionId })
      res.json({ status: 'ended' })
    } catch (error) {
      next(error)
    }
  })
}

module.exports = {
  anonymousPairBlocked,
  validateAnonymousMedia,
  approximateDistanceKm,
  bottleCandidateAllowed,
  bottleOfferRespondable,
  ensureProximityTables,
  haversineKm,
  mutuallyCompatible,
  registerProximityRoutes,
}
