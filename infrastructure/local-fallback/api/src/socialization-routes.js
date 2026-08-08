const { createFixedWindowRateLimiter, stableKey } = require('./request-security')

// The Socializar foundation is intentionally consent-only.  It does not
// discover people, create rooms, inspect direct messages, or read location
// data.  Future discovery/room implementations must consume the effective
// capabilities below; a stored preference by itself never enables a feature.

const SOCIALIZATION_FLAGS = Object.freeze({
  core: 'SOCIALIZATION_ENABLED',
  discovery: 'SOCIALIZATION_DISCOVERY_ENABLED',
  connectionRequests: 'SOCIALIZATION_CONNECTION_REQUESTS_ENABLED',
  communityDiscovery: 'SOCIALIZATION_COMMUNITY_DISCOVERY_ENABLED',
  icebreakers: 'SOCIALIZATION_ICEBREAKERS_ENABLED',
  socialRooms: 'SOCIALIZATION_ROOMS_ENABLED',
})

const PREFERENCE_FIELDS = Object.freeze([
  'discovery_opt_in',
  'connection_requests_opt_in',
  'community_discovery_opt_in',
  'icebreakers_opt_in',
  'social_rooms_opt_in',
])

const CAPABILITY_RULES = Object.freeze({
  discovery: { flag: 'discovery', preference: 'discovery_opt_in' },
  connectionRequests: { flag: 'connectionRequests', preference: 'connection_requests_opt_in' },
  communityDiscovery: { flag: 'communityDiscovery', preference: 'community_discovery_opt_in' },
  icebreakers: { flag: 'icebreakers', preference: 'icebreakers_opt_in' },
  socialRooms: { flag: 'socialRooms', preference: 'social_rooms_opt_in' },
})

const INPUT_ALIASES = Object.freeze({
  discovery: 'discovery_opt_in',
  discoverable: 'discovery_opt_in',
  discoveryOptIn: 'discovery_opt_in',
  discovery_opt_in: 'discovery_opt_in',
  connectionRequests: 'connection_requests_opt_in',
  connectionRequestsOptIn: 'connection_requests_opt_in',
  connection_requests_opt_in: 'connection_requests_opt_in',
  communityDiscovery: 'community_discovery_opt_in',
  communityDiscoveryOptIn: 'community_discovery_opt_in',
  community_discovery_opt_in: 'community_discovery_opt_in',
  icebreakers: 'icebreakers_opt_in',
  icebreakersOptIn: 'icebreakers_opt_in',
  icebreakers_opt_in: 'icebreakers_opt_in',
  socialRooms: 'social_rooms_opt_in',
  socialRoomsOptIn: 'social_rooms_opt_in',
  social_rooms_opt_in: 'social_rooms_opt_in',
})

function envFlag(name) {
  return /^(1|true|yes|on)$/i.test(String(process.env[name] || '').trim())
}

function socializationFeatureFlags() {
  return {
    core: envFlag(SOCIALIZATION_FLAGS.core),
    discovery: envFlag(SOCIALIZATION_FLAGS.discovery),
    connectionRequests: envFlag(SOCIALIZATION_FLAGS.connectionRequests),
    communityDiscovery: envFlag(SOCIALIZATION_FLAGS.communityDiscovery),
    icebreakers: envFlag(SOCIALIZATION_FLAGS.icebreakers),
    socialRooms: envFlag(SOCIALIZATION_FLAGS.socialRooms),
  }
}

function isPlainObject(value) {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function defaultPreferences() {
  return {
    discovery_opt_in: false,
    connection_requests_opt_in: false,
    community_discovery_opt_in: false,
    icebreakers_opt_in: false,
    social_rooms_opt_in: false,
    revision: 0,
    updated_at: null,
  }
}

function normalizeId(value) {
  return String(value || '').trim().toLowerCase()
}

function expectedRevision(input) {
  const value = input?.expectedVersion ?? input?.expected_version ?? input?.revision
  return Number.isInteger(value) && value >= 0 ? value : null
}

function booleanPreferencePatch(input, current = defaultPreferences()) {
  if (!isPlainObject(input)) return { error: 'SOCIALIZATION_PREFERENCES_INVALID' }
  const fields = Object.keys(input)
  if (fields.length === 0 || fields.some((key) => !Object.prototype.hasOwnProperty.call(INPUT_ALIASES, key))) {
    return { error: 'SOCIALIZATION_PREFERENCES_INVALID' }
  }

  const value = Object.fromEntries(
    PREFERENCE_FIELDS.map((field) => [field, Boolean(current[field])]),
  )
  for (const [rawKey, rawValue] of Object.entries(input)) {
    if (typeof rawValue !== 'boolean') return { error: 'SOCIALIZATION_PREFERENCES_INVALID' }
    value[INPUT_ALIASES[rawKey]] = rawValue
  }
  return { value }
}

function preferenceResponse(row = defaultPreferences()) {
  return {
    discoveryOptIn: Boolean(row.discovery_opt_in),
    connectionRequestsOptIn: Boolean(row.connection_requests_opt_in),
    communityDiscoveryOptIn: Boolean(row.community_discovery_opt_in),
    icebreakersOptIn: Boolean(row.icebreakers_opt_in),
    socialRoomsOptIn: Boolean(row.social_rooms_opt_in),
    revision: Number(row.revision || 0),
    updatedAt: row.updated_at || null,
  }
}

function effectiveCapabilities(row = defaultPreferences(), flags = socializationFeatureFlags()) {
  const feature = {}
  for (const [name, rule] of Object.entries(CAPABILITY_RULES)) {
    feature[name] = Boolean(flags.core && flags[rule.flag] && row[rule.preference] === true)
  }
  return feature
}

function capabilitiesResponse(row = defaultPreferences(), flags = socializationFeatureFlags()) {
  return {
    version: 1,
    enabled: Boolean(flags.core),
    features: effectiveCapabilities(row, flags),
    preferences: preferenceResponse(row),
    consentRequired: Object.fromEntries(Object.keys(CAPABILITY_RULES).map((key) => [key, true])),
    safeguards: {
      privateMessagesUsed: false,
      preciseLocationUsed: false,
      sensitiveProfileAttributesUsed: false,
      randomChatEnabled: false,
    },
  }
}

function requireSocializationEnabled(res) {
  const flags = socializationFeatureFlags()
  if (!flags.core) {
    res.status(404).json({ error: 'SOCIALIZATION_DISABLED' })
    return null
  }
  return flags
}

function createSocializationRateLimiters() {
  return {
    capabilities: createFixedWindowRateLimiter({ limit: 120, windowMs: 60_000 }),
    preferencesRead: createFixedWindowRateLimiter({ limit: 60, windowMs: 60_000 }),
    preferencesWrite: createFixedWindowRateLimiter({ limit: 20, windowMs: 60_000 }),
  }
}

function consumeRateLimit(res, limiter, userId) {
  const result = limiter(stableKey(userId))
  res.setHeader?.('x-ratelimit-limit', String(result.limit))
  res.setHeader?.('x-ratelimit-remaining', String(result.remaining))
  if (result.allowed) return true
  res.setHeader?.('retry-after', String(result.retryAfterSeconds))
  res.status(429).json({ error: 'SOCIALIZATION_RATE_LIMITED' })
  return false
}

async function ensureSocializationTables(pool) {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_user_socialization_preferences (
      user_id TEXT PRIMARY KEY,
      discovery_opt_in BOOLEAN NOT NULL DEFAULT FALSE,
      connection_requests_opt_in BOOLEAN NOT NULL DEFAULT FALSE,
      community_discovery_opt_in BOOLEAN NOT NULL DEFAULT FALSE,
      icebreakers_opt_in BOOLEAN NOT NULL DEFAULT FALSE,
      social_rooms_opt_in BOOLEAN NOT NULL DEFAULT FALSE,
      revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_socialization_preferences_updated
    ON fallback_user_socialization_preferences(updated_at DESC)
  `)
}

async function loadSocializationPreferences(pool, identity) {
  const aliases = Array.from(identity?.ids || [])
  if (aliases.length === 0) return defaultPreferences()
  const { rows } = await pool.query(
    `SELECT user_id, ${PREFERENCE_FIELDS.join(', ')}, revision, updated_at
       FROM fallback_user_socialization_preferences
      WHERE lower(user_id::text) = ANY($1::text[])
      ORDER BY updated_at DESC
      LIMIT 1`,
    [aliases],
  )
  return rows[0] || defaultPreferences()
}

function mutationBody(input) {
  if (!isPlainObject(input)) return { error: 'SOCIALIZATION_PREFERENCES_INVALID' }
  const version = expectedRevision(input)
  if (version === null) return { error: 'SOCIALIZATION_VERSION_REQUIRED' }
  const metadataKeys = new Set(['expectedVersion', 'expected_version', 'revision'])
  if (Object.prototype.hasOwnProperty.call(input, 'preferences')) {
    const unexpected = Object.keys(input).some((key) => key !== 'preferences' && !metadataKeys.has(key))
    if (unexpected) return { error: 'SOCIALIZATION_PREFERENCES_INVALID' }
    return { value: { expectedVersion: version, patch: input.preferences } }
  }
  const patch = Object.fromEntries(Object.entries(input).filter(([key]) => !metadataKeys.has(key)))
  return { value: { expectedVersion: version, patch } }
}

async function updateOwnedSocializationPreferences(pool, { identity, expectedVersion: expected, patch }) {
  const client = await pool.connect()
  try {
    await client.query('BEGIN')
    // There is no row to lock on a first opt-in.  Serialize by canonical
    // account id so two expectedVersion=0 requests cannot both reach INSERT
    // and turn a normal optimistic-concurrency conflict into a database error.
    await client.query(
      'SELECT pg_advisory_xact_lock(hashtextextended($1, 0))',
      [identity.displayUserId],
    )
    const { rows } = await client.query(
      `SELECT user_id, ${PREFERENCE_FIELDS.join(', ')}, revision, updated_at
         FROM fallback_user_socialization_preferences
        WHERE lower(user_id::text) = ANY($1::text[])
        ORDER BY updated_at DESC
        LIMIT 1
        FOR UPDATE`,
      [Array.from(identity.ids)],
    )
    const current = rows[0] || null
    const currentRevision = Number(current?.revision || 0)
    if (currentRevision !== expected) {
      await client.query('ROLLBACK')
      return { error: 'SOCIALIZATION_PREFERENCES_CONFLICT', currentRevision }
    }
    const normalized = booleanPreferencePatch(patch, current || defaultPreferences())
    if (normalized.error) {
      await client.query('ROLLBACK')
      return normalized
    }
    const values = PREFERENCE_FIELDS.map((field) => normalized.value[field])
    let updated
    if (current) {
      const result = await client.query(
        `UPDATE fallback_user_socialization_preferences
            SET discovery_opt_in = $2,
                connection_requests_opt_in = $3,
                community_discovery_opt_in = $4,
                icebreakers_opt_in = $5,
                social_rooms_opt_in = $6,
                revision = revision + 1,
                updated_at = NOW()
          WHERE user_id = $1
        RETURNING user_id, ${PREFERENCE_FIELDS.join(', ')}, revision, updated_at`,
        [current.user_id, ...values],
      )
      updated = result.rows[0]
    } else {
      const result = await client.query(
        `INSERT INTO fallback_user_socialization_preferences(
           user_id, discovery_opt_in, connection_requests_opt_in,
           community_discovery_opt_in, icebreakers_opt_in, social_rooms_opt_in,
           revision, created_at, updated_at
         ) VALUES ($1, $2, $3, $4, $5, $6, 1, NOW(), NOW())
         RETURNING user_id, ${PREFERENCE_FIELDS.join(', ')}, revision, updated_at`,
        [identity.displayUserId, ...values],
      )
      updated = result.rows[0]
    }
    await client.query('COMMIT')
    return { preferences: updated }
  } catch (error) {
    await client.query('ROLLBACK').catch(() => {})
    throw error
  } finally {
    client.release()
  }
}

function registerSocializationRoutes(app, { pool, authRequired, getCurrentUser, userIdentity }) {
  const limiters = createSocializationRateLimiters()

  async function currentIdentity(req, res) {
    const user = await getCurrentUser(pool, req)
    if (!user) {
      res.status(404).json({ error: 'USER_NOT_FOUND' })
      return null
    }
    const identity = userIdentity(user)
    if (!identity?.displayUserId || !identity?.ids?.size) {
      res.status(403).json({ error: 'SOCIALIZATION_IDENTITY_INVALID' })
      return null
    }
    return identity
  }

  app.get('/v1/data/socialization/capabilities', authRequired, async (req, res, next) => {
    try {
      const identity = await currentIdentity(req, res)
      if (!identity || !consumeRateLimit(res, limiters.capabilities, identity.displayUserId)) return
      const preferences = await loadSocializationPreferences(pool, identity)
      res.setHeader('Cache-Control', 'private, no-store')
      res.json(capabilitiesResponse(preferences))
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/socialization/preferences', authRequired, async (req, res, next) => {
    try {
      const identity = await currentIdentity(req, res)
      if (!identity || !consumeRateLimit(res, limiters.preferencesRead, identity.displayUserId)) return
      const flags = requireSocializationEnabled(res)
      if (!flags) return
      const preferences = await loadSocializationPreferences(pool, identity)
      res.setHeader('Cache-Control', 'private, no-store')
      res.json({ preferences: preferenceResponse(preferences), capabilities: effectiveCapabilities(preferences, flags) })
    } catch (error) {
      next(error)
    }
  })

  app.put('/v1/data/socialization/preferences', authRequired, async (req, res, next) => {
    try {
      const identity = await currentIdentity(req, res)
      if (!identity || !consumeRateLimit(res, limiters.preferencesWrite, identity.displayUserId)) return
      const flags = requireSocializationEnabled(res)
      if (!flags) return
      const mutation = mutationBody(req.body)
      if (mutation.error) return res.status(400).json({ error: mutation.error })
      const result = await updateOwnedSocializationPreferences(pool, {
        identity,
        expectedVersion: mutation.value.expectedVersion,
        patch: mutation.value.patch,
      })
      if (result.error === 'SOCIALIZATION_PREFERENCES_CONFLICT') {
        return res.status(409).json({ error: result.error, currentVersion: result.currentRevision })
      }
      if (result.error) return res.status(400).json({ error: result.error })
      res.setHeader('Cache-Control', 'private, no-store')
      res.json({
        preferences: preferenceResponse(result.preferences),
        capabilities: effectiveCapabilities(result.preferences, flags),
      })
    } catch (error) {
      next(error)
    }
  })
}

module.exports = {
  CAPABILITY_RULES,
  PREFERENCE_FIELDS,
  SOCIALIZATION_FLAGS,
  booleanPreferencePatch,
  capabilitiesResponse,
  createSocializationRateLimiters,
  defaultPreferences,
  effectiveCapabilities,
  ensureSocializationTables,
  expectedRevision,
  loadSocializationPreferences,
  mutationBody,
  preferenceResponse,
  registerSocializationRoutes,
  socializationFeatureFlags,
  updateOwnedSocializationPreferences,
}
