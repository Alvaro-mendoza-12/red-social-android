const crypto = require('crypto')
const { publishRealtimeEvent } = require('./realtime-publish')

const ALLOWED_GENDERS = new Set(['unspecified', 'woman', 'man', 'nonbinary'])
const ALLOWED_LOOKING_FOR = new Set(['any', 'woman', 'man', 'nonbinary'])

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
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_random_chat_messages (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      session_id UUID NOT NULL REFERENCES fallback_random_chat_sessions(id) ON DELETE CASCADE,
      sender_id TEXT NOT NULL,
      client_message_id TEXT NOT NULL,
      content TEXT NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE(session_id, sender_id, client_message_id)
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_random_messages_session
    ON fallback_random_chat_messages(session_id, created_at)
  `)
}

async function profileById(pool, userId) {
  const { rows } = await pool.query(
    `SELECT u.id, u.email, u.display_name, u.avatar_url, COALESCE(o.profile, '{}'::jsonb) AS profile
     FROM fallback_users u
     LEFT JOIN fallback_profile_overrides o ON o.user_id = u.id
     WHERE lower(u.id::text) = $1
     LIMIT 1`,
    [normalizeId(userId)],
  )
  const row = rows[0]
  if (!row) return null
  const profile = row.profile || {}
  return {
    id: normalizeId(row.id),
    displayName: profileText(profile, ['display_name', 'displayName', 'full_name', 'fullName', 'username'], row.display_name || 'Usuario'),
    avatarUrl: profileText(profile, ['avatar_url', 'avatarUrl'], row.avatar_url || '') || null,
    city: profileText(profile, ['city'], ''),
    country: profileText(profile, ['country'], ''),
    interests: profileArray(profile, 'interests'),
    hobbies: profileArray(profile, 'hobbies'),
    breakMode: normalizeId(profileText(profile, ['break_mode', 'breakMode'], 'off')),
  }
}

async function blockedIds(pool, identity) {
  const values = Array.from(identity.ids)
  const { rows } = await pool.query(
    `SELECT lower(blocker_id::text) AS blocker_id, lower(blocked_id::text) AS blocked_id
     FROM fallback_user_blocks
     WHERE lower(blocker_id::text) = ANY($1::text[])
        OR lower(blocked_id::text) = ANY($1::text[])`,
    [values],
  )
  const blocked = new Set()
  rows.forEach((row) => {
    const blocker = normalizeId(row.blocker_id)
    const blockedId = normalizeId(row.blocked_id)
    if (identity.ids.has(blocker)) blocked.add(blockedId)
    if (identity.ids.has(blockedId)) blocked.add(blocker)
  })
  return blocked
}

async function activeSessionForUser(db, userId) {
  const { rows } = await db.query(
    `SELECT id::text, user_a, user_b, status, user_a_reveal, user_b_reveal, created_at, updated_at
     FROM fallback_random_chat_sessions
     WHERE status = 'active' AND (lower(user_a::text) = $1 OR lower(user_b::text) = $1)
     ORDER BY updated_at DESC
     LIMIT 1`,
    [normalizeId(userId)],
  )
  return rows[0] || null
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
    created_at: session.created_at,
    updated_at: session.updated_at,
  }
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
      const existing = await activeSessionForUser(client, userId)
      if (existing) {
        await client.query('COMMIT')
        return res.json({ status: 'matched', session: await sessionPayload(pool, existing, identity) })
      }
      const { rows: ownPresenceRows } = await client.query(
        `SELECT latitude::float8, longitude::float8, radius_km::float8
         FROM fallback_proximity_presence
         WHERE lower(user_id::text) = ANY($1::text[]) AND nearby_enabled = TRUE AND expires_at > NOW()
         LIMIT 1`,
        [identity.values],
      )
      if (!ownPresenceRows[0]) {
        await client.query('ROLLBACK')
        return res.status(409).json({ error: 'PROXIMITY_NOT_ENABLED' })
      }
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
        `SELECT q.user_id, q.gender, q.looking_for,
                p.latitude::float8, p.longitude::float8, p.radius_km::float8
         FROM fallback_random_chat_queue q
         JOIN fallback_proximity_presence p ON lower(p.user_id::text) = lower(q.user_id::text)
         WHERE lower(q.user_id::text) <> $1
           AND p.nearby_enabled = TRUE
           AND p.random_enabled = TRUE
           AND p.expires_at > NOW()
         ORDER BY q.created_at
         FOR UPDATE OF q SKIP LOCKED`,
        [normalizeId(userId)],
      )
      const ownPresence = ownPresenceRows[0]
      const candidate = queueRows.find((entry) => {
        const candidateId = normalizeId(entry.user_id)
        if (blocked.has(candidateId) || !mutuallyCompatible({ gender, looking_for: lookingFor }, entry)) return false
        const distance = haversineKm(ownPresence.latitude, ownPresence.longitude, entry.latitude, entry.longitude)
        return distance <= Math.min(Number(ownPresence.radius_km || 20), Number(entry.radius_km || 20), 20)
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
      const session = await activeSessionForUser(pool, identity.displayUserId)
      if (session) return res.json({ status: 'matched', session: await sessionPayload(pool, session, identity) })
      const { rows } = await pool.query(
        `SELECT 1 FROM fallback_random_chat_queue WHERE lower(user_id::text) = ANY($1::text[]) AND updated_at > NOW() - INTERVAL '10 minutes' LIMIT 1`,
        [identity.values],
      )
      res.json({ status: rows[0] ? 'waiting' : 'idle' })
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
        `SELECT id::text, sender_id, client_message_id, content, created_at
         FROM fallback_random_chat_messages
         WHERE session_id = $1::uuid
         ORDER BY created_at
         LIMIT 500`,
        [sessionId],
      )
      res.json({
        items: rows.map((row) => ({
          id: row.id,
          client_message_id: row.client_message_id,
          sender: identity.ids.has(normalizeId(row.sender_id)) ? 'me' : 'peer',
          content: row.content,
          created_at: row.created_at,
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
      const content = cleanText(req.body?.content, 1000)
      const clientMessageId = validUuid(req.body?.clientMessageId ?? req.body?.client_message_id) || crypto.randomUUID()
      if (!content) return res.status(400).json({ error: 'MESSAGE_REQUIRED' })
      let { rows } = await pool.query(
        `INSERT INTO fallback_random_chat_messages(session_id, sender_id, client_message_id, content)
         VALUES ($1::uuid, $2, $3, $4)
         ON CONFLICT (session_id, sender_id, client_message_id) DO NOTHING
         RETURNING id::text, client_message_id, content, created_at`,
        [sessionId, identity.displayUserId, clientMessageId, content],
      )
      if (!rows[0]) {
        const existing = await pool.query(
          `SELECT id::text, client_message_id, content, created_at
           FROM fallback_random_chat_messages
           WHERE session_id = $1::uuid AND lower(sender_id::text) = $2 AND client_message_id = $3
           LIMIT 1`,
          [sessionId, normalizeId(identity.displayUserId), clientMessageId],
        )
        rows = existing.rows
      }
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
      const isUserA = identity.ids.has(normalizeId(session.user_a))
      const currentLevel = Number(isUserA ? session.user_a_reveal : session.user_b_reveal)
      const requested = numberBetween(req.body?.level, currentLevel, Math.min(3, currentLevel + 1), currentLevel + 1)
      const nextLevel = Math.trunc(requested)
      const column = isUserA ? 'user_a_reveal' : 'user_b_reveal'
      const { rows } = await pool.query(
        `UPDATE fallback_random_chat_sessions
         SET ${column} = GREATEST(${column}, $2), updated_at = NOW()
         WHERE id = $1::uuid
         RETURNING id::text, user_a, user_b, status, user_a_reveal, user_b_reveal, created_at, updated_at`,
        [sessionId, nextLevel],
      )
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
      await pool.query(
        `UPDATE fallback_proximity_presence SET random_enabled = FALSE, updated_at = NOW() WHERE lower(user_id::text) = ANY($1::text[])`,
        [identity.values],
      )
      void publishRealtimeEvent(`random-chat:${sessionId}`, { event: 'random_session_ended', sessionId })
      res.json({ status: 'ended' })
    } catch (error) {
      next(error)
    }
  })
}

module.exports = {
  approximateDistanceKm,
  ensureProximityTables,
  haversineKm,
  mutuallyCompatible,
  registerProximityRoutes,
}
