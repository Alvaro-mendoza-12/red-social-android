const { createFixedWindowRateLimiter, stableKey } = require('./request-security')

// This module deliberately models only user-owned recommendation controls. It
// has no columns, endpoints, or queries for private messages, precise location,
// age, or gender. Content classification is server-side only; mobile clients
// can never submit a ranking score or classify an arbitrary piece of content.

const ALGORITHM_FLAGS = Object.freeze({
  socialDiscovery: 'SOCIAL_DISCOVERY_ENABLED',
  socialRooms: 'SOCIAL_ROOMS_ENABLED',
})

const ALGORITHM_MODES = new Set(['manual', 'assisted', 'automatic'])
const ALGORITHM_SURFACES = new Set([
  'global',
  'video',
  'social',
  'live',
  'community',
  'people',
  'challenge',
  'event',
])
const PREFERENCE_DIMENSIONS = new Set([
  'content',
  'topic',
  'subtopic',
  'creator',
  'format',
  'audio',
  'language',
  'community',
  'relationship',
])
const CONTENT_KINDS = new Set(['video', 'post', 'live', 'community', 'challenge', 'event'])
const DATA_PERMISSION_FIELDS = Object.freeze([
  'use_likes',
  'use_follows',
  'use_searches',
  'use_watch_time',
  'use_scroll_velocity',
  'use_profile_visits',
  'use_public_interactions',
  'use_community_activity',
  'use_live_activity',
])
const FEED_MIX_FIELDS = Object.freeze([
  'following_weight',
  'interest_weight',
  'community_weight',
  'new_content_weight',
  'trend_weight',
  'exploration_weight',
])
const MAX_PREFERENCES_PER_MUTATION = 50
const MAX_ACTIVE_TEMPORARY_PREFERENCES = 100
const MAX_TEMPORARY_DURATION_MS = 90 * 24 * 60 * 60 * 1000
const MAX_FUTURE_START_MS = 30 * 24 * 60 * 60 * 1000

function envFlag(name) {
  return /^(1|true|yes|on)$/i.test(String(process.env[name] || '').trim())
}

function algorithmFeatureFlags() {
  return {
    // These are product capabilities, not rollout switches. Mi algoritmo is
    // authenticated, owner-scoped and backed by tables ensured at startup, so
    // advertising a visible screen while an environment flag returns 404 is
    // both misleading and functionally broken.
    core: true,
    classification: true,
    preferences: true,
    feedMix: true,
    dataPermissions: true,
    explanations: true,
    socialDiscovery: envFlag(ALGORITHM_FLAGS.socialDiscovery),
    socialRooms: envFlag(ALGORITHM_FLAGS.socialRooms),
  }
}

function algorithmCapabilitiesResponse(flags = algorithmFeatureFlags()) {
  return {
    enabled: Boolean(flags.core),
    capabilities: {
      classification: Boolean(flags.core && flags.classification),
      preferences: Boolean(flags.core && flags.preferences),
      feedMix: Boolean(flags.core && flags.feedMix),
      dataPermissions: Boolean(flags.core && flags.dataPermissions),
      explanations: Boolean(flags.core && flags.explanations),
      // Discovery and rooms are rollout capabilities of this module.  A
      // partial environment must not advertise either while the algorithm is
      // globally disabled.
      socialDiscovery: Boolean(flags.core && flags.socialDiscovery),
      socialRooms: Boolean(flags.core && flags.socialRooms),
    },
  }
}

function normalizeId(value) {
  return String(value || '').trim().toLowerCase()
}

function cleanText(value, maxLength = 160) {
  const text = String(value || '').trim()
  return text ? text.slice(0, maxLength) : ''
}

function strictText(value, maxLength = 160) {
  if (typeof value !== 'string') return null
  const text = value.trim()
  return text ? text.slice(0, maxLength) : ''
}

function validUuid(value) {
  const text = normalizeId(value)
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(text)
    ? text
    : null
}

function isPlainObject(value) {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function normalizedKey(value, maxLength = 80) {
  const text = normalizeId(value)
  if (!text || text.length > maxLength) return null
  return /^[a-z0-9][a-z0-9:_-]*$/.test(text) ? text : null
}

function normalizedSurface(value, fallback = 'global') {
  const surface = normalizedKey(value || fallback, 32)
  return surface && ALGORITHM_SURFACES.has(surface) ? surface : null
}

function preferenceLevel(score) {
  if (score <= -100) return 'blocked'
  if (score < 0) return 'less'
  if (score >= 70) return 'high'
  return 'normal'
}

function defaultFeedMix(surface) {
  switch (surface) {
    case 'video':
      return {
        following_weight: 30,
        interest_weight: 40,
        community_weight: 0,
        new_content_weight: 0,
        trend_weight: 10,
        exploration_weight: 20,
      }
    case 'social':
      return {
        following_weight: 50,
        interest_weight: 20,
        community_weight: 10,
        new_content_weight: 10,
        trend_weight: 0,
        exploration_weight: 10,
      }
    case 'live':
      return {
        following_weight: 40,
        interest_weight: 25,
        community_weight: 10,
        new_content_weight: 10,
        trend_weight: 5,
        exploration_weight: 10,
      }
    case 'community':
      return {
        following_weight: 20,
        interest_weight: 35,
        community_weight: 30,
        new_content_weight: 5,
        trend_weight: 0,
        exploration_weight: 10,
      }
    default:
      return {
        following_weight: 40,
        interest_weight: 30,
        community_weight: 10,
        new_content_weight: 10,
        trend_weight: 5,
        exploration_weight: 5,
      }
  }
}

function defaultDataPermissions() {
  return Object.fromEntries(DATA_PERMISSION_FIELDS.map((field) => [field, false]))
}

function numberOrNull(value) {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function normalizePreferenceInput(input) {
  if (!isPlainObject(input)) return { error: 'PREFERENCE_INVALID' }
  const surface = normalizedSurface(input.surface, 'global')
  const dimension = normalizedKey(input.dimension, 32)
  const subjectKey = normalizedKey(input.subjectKey ?? input.subject_key ?? input.key, 120)
  const suppliedLabel = input.subjectLabel ?? input.subject_label ?? input.label
  const subjectLabel = suppliedLabel === undefined ? subjectKey : strictText(suppliedLabel, 120)
  const score = numberOrNull(input.score)
  if (!surface || !dimension || !PREFERENCE_DIMENSIONS.has(dimension) || !subjectKey || !subjectLabel) {
    return { error: 'PREFERENCE_INVALID' }
  }
  if (!Number.isInteger(score) || score < -100 || score > 100) {
    return { error: 'PREFERENCE_SCORE_INVALID' }
  }
  return {
    value: {
      surface,
      dimension,
      subjectKey,
      subjectLabel,
      score,
      level: preferenceLevel(score),
    },
  }
}

function preferenceIdentityKey(value) {
  return [value.surface, value.dimension, value.subjectKey].join(':')
}

function parseIsoDate(value) {
  if (typeof value !== 'string' || !value.trim()) return null
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? null : parsed
}

function normalizeTemporaryPreferenceInput(input, now = new Date()) {
  const normalized = normalizePreferenceInput(input)
  if (normalized.error) return normalized
  const startsAt = parseIsoDate(input.startsAt ?? input.starts_at) || new Date(now)
  const expiresAt = parseIsoDate(input.expiresAt ?? input.expires_at)
  const nowMs = now.getTime()
  if (!expiresAt || startsAt.getTime() < nowMs - 5 * 60 * 1000 || startsAt.getTime() > nowMs + MAX_FUTURE_START_MS) {
    return { error: 'TEMPORARY_PREFERENCE_TIME_INVALID' }
  }
  const durationMs = expiresAt.getTime() - startsAt.getTime()
  if (durationMs <= 0 || durationMs > MAX_TEMPORARY_DURATION_MS) {
    return { error: 'TEMPORARY_PREFERENCE_EXPIRY_INVALID' }
  }
  return {
    value: {
      ...normalized.value,
      startsAt: startsAt.toISOString(),
      expiresAt: expiresAt.toISOString(),
    },
  }
}

function normalizeFeedMix(input, current = {}) {
  if (!isPlainObject(input)) return { error: 'FEED_MIX_INVALID' }
  const aliases = {
    following: 'following_weight',
    followingWeight: 'following_weight',
    interests: 'interest_weight',
    interestWeight: 'interest_weight',
    communities: 'community_weight',
    communityWeight: 'community_weight',
    newContent: 'new_content_weight',
    new_content: 'new_content_weight',
    newContentWeight: 'new_content_weight',
    trends: 'trend_weight',
    trendWeight: 'trend_weight',
    exploration: 'exploration_weight',
    explorationWeight: 'exploration_weight',
  }
  const allowedKeys = new Set([...FEED_MIX_FIELDS, ...Object.keys(aliases)])
  if (Object.keys(input).some((key) => !allowedKeys.has(key))) return { error: 'FEED_MIX_INVALID' }
  const result = Object.fromEntries(
    FEED_MIX_FIELDS.map((field) => [field, Number(current[field] ?? 0)]),
  )
  let supplied = 0
  for (const [rawKey, rawValue] of Object.entries(input)) {
    const field = aliases[rawKey] || rawKey
    const value = numberOrNull(rawValue)
    if (!Number.isInteger(value) || value < 0 || value > 100) return { error: 'FEED_MIX_WEIGHT_INVALID' }
    result[field] = value
    supplied += 1
  }
  if (supplied === 0 || FEED_MIX_FIELDS.reduce((sum, field) => sum + result[field], 0) !== 100) {
    return { error: 'FEED_MIX_TOTAL_INVALID' }
  }
  return { value: result }
}

function normalizeDataPermissions(input, current = {}) {
  if (!isPlainObject(input)) return { error: 'DATA_PERMISSIONS_INVALID' }
  const aliases = {
    likes: 'use_likes',
    follows: 'use_follows',
    searches: 'use_searches',
    watchTime: 'use_watch_time',
    watch_time: 'use_watch_time',
    scrollVelocity: 'use_scroll_velocity',
    scroll_velocity: 'use_scroll_velocity',
    profileVisits: 'use_profile_visits',
    profile_visits: 'use_profile_visits',
    publicInteractions: 'use_public_interactions',
    public_interactions: 'use_public_interactions',
    communityActivity: 'use_community_activity',
    community_activity: 'use_community_activity',
    liveActivity: 'use_live_activity',
    live_activity: 'use_live_activity',
  }
  const allowedKeys = new Set([...DATA_PERMISSION_FIELDS, ...Object.keys(aliases)])
  if (Object.keys(input).some((key) => !allowedKeys.has(key))) return { error: 'DATA_PERMISSIONS_INVALID' }
  const result = Object.fromEntries(
    DATA_PERMISSION_FIELDS.map((field) => [field, Boolean(current[field])]),
  )
  let supplied = 0
  for (const [rawKey, value] of Object.entries(input)) {
    if (typeof value !== 'boolean') return { error: 'DATA_PERMISSIONS_INVALID' }
    result[aliases[rawKey] || rawKey] = value
    supplied += 1
  }
  return supplied > 0 ? { value: result } : { error: 'DATA_PERMISSIONS_INVALID' }
}

function normalizeProfileCreate(input) {
  if (!isPlainObject(input)) return { error: 'ALGORITHM_PROFILE_INVALID' }
  const name = strictText(input.name, 60)
  const derivedSlug = name ? name.toLowerCase().replace(/\s+/g, '-') : ''
  const rawSlug = input.slug === undefined || input.slug === null ? derivedSlug : input.slug
  const slug = normalizedKey(rawSlug, 60)
  const mode = normalizedKey(input.mode || 'manual', 20)
  if (!name || /[<>]/.test(name) || !slug || !mode || !ALGORITHM_MODES.has(mode)) {
    return { error: 'ALGORITHM_PROFILE_INVALID' }
  }
  return { value: { name, slug, mode } }
}

function normalizeProfilePatch(input) {
  if (!isPlainObject(input)) return { error: 'ALGORITHM_PROFILE_INVALID' }
  const patch = {}
  if (Object.prototype.hasOwnProperty.call(input, 'name')) {
    const name = strictText(input.name, 60)
    if (!name || /[<>]/.test(name)) return { error: 'ALGORITHM_PROFILE_INVALID' }
    patch.name = name
  }
  if (Object.prototype.hasOwnProperty.call(input, 'mode')) {
    const mode = normalizedKey(input.mode, 20)
    if (!mode || !ALGORITHM_MODES.has(mode)) return { error: 'ALGORITHM_MODE_INVALID' }
    patch.mode = mode
  }
  if (Object.prototype.hasOwnProperty.call(input, 'isEnabled') || Object.prototype.hasOwnProperty.call(input, 'is_enabled')) {
    const enabled = input.isEnabled ?? input.is_enabled
    if (typeof enabled !== 'boolean') return { error: 'ALGORITHM_PROFILE_INVALID' }
    patch.is_enabled = enabled
  }
  if (Object.prototype.hasOwnProperty.call(input, 'makeDefault') || Object.prototype.hasOwnProperty.call(input, 'make_default')) {
    const makeDefault = input.makeDefault ?? input.make_default
    if (typeof makeDefault !== 'boolean') return { error: 'ALGORITHM_PROFILE_INVALID' }
    patch.make_default = makeDefault
  }
  return Object.keys(patch).length > 0 ? { value: patch } : { error: 'ALGORITHM_PROFILE_INVALID' }
}

function expectedRevision(input) {
  const value = input?.expectedVersion ?? input?.expected_version ?? input?.revision
  return Number.isInteger(value) && value >= 1 ? value : null
}

function withoutMutationMetadata(input) {
  if (!isPlainObject(input)) return input
  const { expectedVersion, expected_version, revision, ...payload } = input
  return payload
}

function profileResponse(row) {
  if (!row) return null
  return {
    id: row.id,
    name: row.name,
    slug: row.slug,
    mode: row.mode,
    isEnabled: Boolean(row.is_enabled),
    isDefault: Boolean(row.is_default),
    revision: Number(row.revision || 1),
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  }
}

function preferenceResponse(row) {
  return {
    id: row.id,
    surface: row.surface,
    dimension: row.dimension,
    subjectKey: row.subject_key,
    subjectLabel: row.subject_label,
    score: Number(row.score),
    level: preferenceLevel(Number(row.score)),
    source: 'explicit',
    updatedAt: row.updated_at,
  }
}

function feedMixResponse(row) {
  const surface = row.surface
  const weights = Object.fromEntries(FEED_MIX_FIELDS.map((field) => [field, Number(row[field] || 0)]))
  return {
    surface,
    following: weights.following_weight,
    interests: weights.interest_weight,
    communities: weights.community_weight,
    newContent: weights.new_content_weight,
    trends: weights.trend_weight,
    exploration: weights.exploration_weight,
    updatedAt: row.updated_at,
  }
}

function dataPermissionsResponse(row) {
  const values = Object.fromEntries(DATA_PERMISSION_FIELDS.map((field) => [field, Boolean(row[field])]))
  return {
    likes: values.use_likes,
    follows: values.use_follows,
    searches: values.use_searches,
    watchTime: values.use_watch_time,
    scrollVelocity: values.use_scroll_velocity,
    profileVisits: values.use_profile_visits,
    publicInteractions: values.use_public_interactions,
    communityActivity: values.use_community_activity,
    liveActivity: values.use_live_activity,
    updatedAt: row.updated_at,
  }
}

function temporaryPreferenceResponse(row) {
  return {
    id: row.id,
    surface: row.surface,
    dimension: row.dimension,
    subjectKey: row.subject_key,
    subjectLabel: row.subject_label,
    score: Number(row.score),
    level: preferenceLevel(Number(row.score)),
    startsAt: row.starts_at,
    expiresAt: row.expires_at,
    createdAt: row.created_at,
  }
}

function requireAlgorithmFeature(res, capability = null) {
  const flags = algorithmFeatureFlags()
  if (!flags.core) {
    res.status(404).json({ error: 'USER_ALGORITHM_DISABLED' })
    return false
  }
  if (capability && !flags[capability]) {
    res.status(404).json({ error: 'USER_ALGORITHM_CAPABILITY_DISABLED' })
    return false
  }
  return true
}

function createRouteLimiters() {
  return {
    read: createFixedWindowRateLimiter({ limit: 120, windowMs: 60_000 }),
    write: createFixedWindowRateLimiter({ limit: 40, windowMs: 60_000 }),
    create: createFixedWindowRateLimiter({ limit: 8, windowMs: 60 * 60 * 1000 }),
  }
}

function consumeRateLimit(res, limiter, userId) {
  const result = limiter(stableKey(userId))
  res.setHeader('x-ratelimit-limit', String(result.limit))
  res.setHeader('x-ratelimit-remaining', String(result.remaining))
  if (result.allowed) return true
  res.setHeader('retry-after', String(result.retryAfterSeconds))
  res.status(429).json({ error: 'ALGORITHM_RATE_LIMITED' })
  return false
}

async function ensureAlgorithmTables(pool) {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_user_algorithm_profiles (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      user_id TEXT NOT NULL,
      name TEXT NOT NULL DEFAULT 'Para mí',
      slug TEXT NOT NULL DEFAULT 'for-you',
      mode TEXT NOT NULL DEFAULT 'manual'
        CHECK (mode IN ('manual', 'assisted', 'automatic')),
      is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
      is_default BOOLEAN NOT NULL DEFAULT FALSE,
      revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE (user_id, slug)
    )
  `)
  await pool.query(`
    ALTER TABLE fallback_user_algorithm_profiles
    ALTER COLUMN is_enabled SET DEFAULT TRUE
  `)
  // Profiles created by older releases started disabled even though manual
  // mode consumes no inferred behaviour. Enable only untouched defaults;
  // revision > 1 remains authoritative so an explicit opt-out is preserved.
  await pool.query(`
    UPDATE fallback_user_algorithm_profiles
       SET is_enabled = TRUE,
           updated_at = NOW()
     WHERE is_default = TRUE
       AND slug = 'for-you'
       AND mode = 'manual'
       AND revision = 1
       AND is_enabled = FALSE
  `)
  await pool.query(`
    CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_user_algorithm_default_profile
    ON fallback_user_algorithm_profiles(user_id) WHERE is_default
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_user_algorithm_profiles_owner
    ON fallback_user_algorithm_profiles(lower(user_id), is_default DESC, updated_at DESC)
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_user_algorithm_preferences (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      profile_id UUID NOT NULL REFERENCES fallback_user_algorithm_profiles(id) ON DELETE CASCADE,
      surface TEXT NOT NULL DEFAULT 'global'
        CHECK (surface IN ('global', 'video', 'social', 'live', 'community', 'people', 'challenge', 'event')),
      dimension TEXT NOT NULL
        CHECK (dimension IN ('content', 'topic', 'subtopic', 'creator', 'format', 'audio', 'language', 'community', 'relationship')),
      subject_key TEXT NOT NULL,
      subject_label TEXT NOT NULL,
      score SMALLINT NOT NULL CHECK (score BETWEEN -100 AND 100),
      source TEXT NOT NULL DEFAULT 'explicit' CHECK (source = 'explicit'),
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE(profile_id, surface, dimension, subject_key)
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_algorithm_preferences_profile
    ON fallback_user_algorithm_preferences(profile_id, surface, dimension, score, updated_at DESC)
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_user_algorithm_feed_mixes (
      profile_id UUID NOT NULL REFERENCES fallback_user_algorithm_profiles(id) ON DELETE CASCADE,
      surface TEXT NOT NULL
        CHECK (surface IN ('global', 'video', 'social', 'live', 'community', 'people', 'challenge', 'event')),
      following_weight SMALLINT NOT NULL DEFAULT 40 CHECK (following_weight BETWEEN 0 AND 100),
      interest_weight SMALLINT NOT NULL DEFAULT 30 CHECK (interest_weight BETWEEN 0 AND 100),
      community_weight SMALLINT NOT NULL DEFAULT 10 CHECK (community_weight BETWEEN 0 AND 100),
      new_content_weight SMALLINT NOT NULL DEFAULT 10 CHECK (new_content_weight BETWEEN 0 AND 100),
      trend_weight SMALLINT NOT NULL DEFAULT 5 CHECK (trend_weight BETWEEN 0 AND 100),
      exploration_weight SMALLINT NOT NULL DEFAULT 5 CHECK (exploration_weight BETWEEN 0 AND 100),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY(profile_id, surface),
      CHECK (
        following_weight + interest_weight + community_weight + new_content_weight + trend_weight + exploration_weight = 100
      )
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_user_algorithm_data_permissions (
      profile_id UUID PRIMARY KEY REFERENCES fallback_user_algorithm_profiles(id) ON DELETE CASCADE,
      use_likes BOOLEAN NOT NULL DEFAULT FALSE,
      use_follows BOOLEAN NOT NULL DEFAULT FALSE,
      use_searches BOOLEAN NOT NULL DEFAULT FALSE,
      use_watch_time BOOLEAN NOT NULL DEFAULT FALSE,
      use_scroll_velocity BOOLEAN NOT NULL DEFAULT FALSE,
      use_profile_visits BOOLEAN NOT NULL DEFAULT FALSE,
      use_public_interactions BOOLEAN NOT NULL DEFAULT FALSE,
      use_community_activity BOOLEAN NOT NULL DEFAULT FALSE,
      use_live_activity BOOLEAN NOT NULL DEFAULT FALSE,
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_user_algorithm_temporary_preferences (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      profile_id UUID NOT NULL REFERENCES fallback_user_algorithm_profiles(id) ON DELETE CASCADE,
      surface TEXT NOT NULL DEFAULT 'global'
        CHECK (surface IN ('global', 'video', 'social', 'live', 'community', 'people', 'challenge', 'event')),
      dimension TEXT NOT NULL
        CHECK (dimension IN ('content', 'topic', 'subtopic', 'creator', 'format', 'audio', 'language', 'community', 'relationship')),
      subject_key TEXT NOT NULL,
      subject_label TEXT NOT NULL,
      score SMALLINT NOT NULL CHECK (score BETWEEN -100 AND 100),
      starts_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      expires_at TIMESTAMPTZ NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      CHECK (expires_at > starts_at)
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_algorithm_temporary_preferences_active
    ON fallback_user_algorithm_temporary_preferences(profile_id, starts_at, expires_at)
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_algorithm_content_classifications (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      content_kind TEXT NOT NULL CHECK (content_kind IN ('video', 'post', 'live', 'community', 'challenge', 'event')),
      content_id TEXT NOT NULL,
      creator_id TEXT,
      language_code TEXT,
      format_key TEXT,
      audio_key TEXT,
      community_id TEXT,
      safety_state TEXT NOT NULL DEFAULT 'unknown'
        CHECK (safety_state IN ('unknown', 'allowed', 'limited', 'blocked')),
      classifier_version TEXT NOT NULL DEFAULT 'manual-v1',
      classified_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE(content_kind, content_id)
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_algorithm_content_topics (
      classification_id UUID NOT NULL REFERENCES fallback_algorithm_content_classifications(id) ON DELETE CASCADE,
      topic_key TEXT NOT NULL,
      subtopic_key TEXT NOT NULL DEFAULT '',
      confidence NUMERIC(4, 3) NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
      source TEXT NOT NULL DEFAULT 'server' CHECK (source IN ('server', 'reviewed')),
      PRIMARY KEY(classification_id, topic_key, subtopic_key)
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_algorithm_content_topics_lookup
    ON fallback_algorithm_content_topics(topic_key, subtopic_key, confidence DESC)
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_algorithm_classification_candidate
    ON fallback_algorithm_content_classifications(content_kind, lower(content_id), safety_state)
  `)
}

async function ensureProfileDefaults(pool, profileId) {
  for (const surface of ALGORITHM_SURFACES) {
    const mix = defaultFeedMix(surface)
    await pool.query(
      `INSERT INTO fallback_user_algorithm_feed_mixes(
         profile_id, surface, following_weight, interest_weight, community_weight,
         new_content_weight, trend_weight, exploration_weight
       ) VALUES ($1::uuid, $2, $3, $4, $5, $6, $7, $8)
       ON CONFLICT (profile_id, surface) DO NOTHING`,
      [
        profileId,
        surface,
        mix.following_weight,
        mix.interest_weight,
        mix.community_weight,
        mix.new_content_weight,
        mix.trend_weight,
        mix.exploration_weight,
      ],
    )
  }
  const permissions = defaultDataPermissions()
  await pool.query(
    `INSERT INTO fallback_user_algorithm_data_permissions(
       profile_id, ${DATA_PERMISSION_FIELDS.join(', ')}
     ) VALUES ($1::uuid, ${DATA_PERMISSION_FIELDS.map((_field, index) => `$${index + 2}`).join(', ')})
     ON CONFLICT (profile_id) DO NOTHING`,
    [profileId, ...DATA_PERMISSION_FIELDS.map((field) => permissions[field])],
  )
}

async function ensureDefaultProfile(pool, userId) {
  const { rows } = await pool.query(
    `INSERT INTO fallback_user_algorithm_profiles(user_id, name, slug, mode, is_enabled, is_default)
     VALUES ($1, 'Para mí', 'for-you', 'manual', TRUE, TRUE)
     ON CONFLICT (user_id) WHERE is_default
     DO UPDATE SET updated_at = fallback_user_algorithm_profiles.updated_at
     RETURNING id::text, name, slug, mode, is_enabled, is_default, revision, created_at, updated_at`,
    [userId],
  )
  const profile = rows[0]
  if (profile) await ensureProfileDefaults(pool, profile.id)
  return profile || null
}

async function findOwnedProfile(pool, userId, profileId) {
  if (!profileId) return ensureDefaultProfile(pool, userId)
  const id = validUuid(profileId)
  if (!id) return null
  const { rows } = await pool.query(
    `SELECT id::text, name, slug, mode, is_enabled, is_default, revision, created_at, updated_at
       FROM fallback_user_algorithm_profiles
      WHERE id = $1::uuid AND lower(user_id) = lower($2)
      LIMIT 1`,
    [id, userId],
  )
  const profile = rows[0] || null
  if (profile) await ensureProfileDefaults(pool, profile.id)
  return profile
}

async function mutateOwnedProfile(pool, { userId, profileId, expectedVersion, mutate }) {
  const client = await pool.connect()
  try {
    await client.query('BEGIN')
    const { rows } = await client.query(
      `SELECT id::text, name, slug, mode, is_enabled, is_default, revision, created_at, updated_at
         FROM fallback_user_algorithm_profiles
        WHERE id = $1::uuid AND lower(user_id) = lower($2)
        FOR UPDATE`,
      [profileId, userId],
    )
    const profile = rows[0]
    if (!profile) {
      await client.query('ROLLBACK')
      return { error: 'ALGORITHM_PROFILE_NOT_FOUND' }
    }
    if (Number(profile.revision) !== expectedVersion) {
      await client.query('ROLLBACK')
      return { error: 'ALGORITHM_PROFILE_CONFLICT', currentRevision: Number(profile.revision) }
    }
    const result = await mutate(client, profile)
    if (result?.error) {
      await client.query('ROLLBACK')
      return result
    }
    const updated = await client.query(
      `UPDATE fallback_user_algorithm_profiles
          SET revision = revision + 1, updated_at = NOW()
        WHERE id = $1::uuid
      RETURNING id::text, name, slug, mode, is_enabled, is_default, revision, created_at, updated_at`,
      [profileId],
    )
    await client.query('COMMIT')
    return { profile: updated.rows[0], ...(result || {}) }
  } catch (error) {
    await client.query('ROLLBACK').catch(() => {})
    throw error
  } finally {
    client.release()
  }
}

async function loadAlgorithmConfiguration(pool, profile) {
  await pool.query(
    `DELETE FROM fallback_user_algorithm_temporary_preferences
      WHERE profile_id = $1::uuid AND expires_at <= NOW()`,
    [profile.id],
  )
  const [preferencesResult, mixesResult, permissionsResult, temporaryResult] = await Promise.all([
    pool.query(
      `SELECT id::text, surface, dimension, subject_key, subject_label, score, updated_at
         FROM fallback_user_algorithm_preferences
        WHERE profile_id = $1::uuid
        ORDER BY surface, dimension, subject_label, updated_at DESC
        LIMIT 500`,
      [profile.id],
    ),
    pool.query(
      `SELECT surface, ${FEED_MIX_FIELDS.join(', ')}, updated_at
         FROM fallback_user_algorithm_feed_mixes
        WHERE profile_id = $1::uuid
        ORDER BY surface`,
      [profile.id],
    ),
    pool.query(
      `SELECT ${DATA_PERMISSION_FIELDS.join(', ')}, updated_at
         FROM fallback_user_algorithm_data_permissions
        WHERE profile_id = $1::uuid`,
      [profile.id],
    ),
    pool.query(
      `SELECT id::text, surface, dimension, subject_key, subject_label, score, starts_at, expires_at, created_at
         FROM fallback_user_algorithm_temporary_preferences
        WHERE profile_id = $1::uuid AND starts_at <= NOW() AND expires_at > NOW()
        ORDER BY expires_at ASC
        LIMIT 100`,
      [profile.id],
    ),
  ])
  return {
    profile: profileResponse(profile),
    preferences: preferencesResult.rows.map(preferenceResponse),
    feedMixes: mixesResult.rows.map(feedMixResponse),
    dataPermissions: dataPermissionsResponse(permissionsResult.rows[0] || defaultDataPermissions()),
    temporaryPreferences: temporaryResult.rows.map(temporaryPreferenceResponse),
  }
}

function normalizeContentClassification(input) {
  if (!isPlainObject(input)) return { error: 'CLASSIFICATION_INVALID' }
  const contentKind = normalizedKey(input.contentKind ?? input.content_kind, 20)
  const contentId = normalizedKey(input.contentId ?? input.content_id, 120)
  const creatorCandidate = input.creatorId ?? input.creator_id
  const languageCandidate = input.languageCode ?? input.language_code
  const formatCandidate = input.formatKey ?? input.format_key
  const audioCandidate = input.audioKey ?? input.audio_key
  const communityCandidate = input.communityId ?? input.community_id
  const creatorId = creatorCandidate ? normalizedKey(creatorCandidate, 120) : null
  const languageCode = languageCandidate ? normalizedKey(languageCandidate, 20) : null
  const formatKey = formatCandidate ? normalizedKey(formatCandidate, 80) : null
  const audioKey = audioCandidate ? normalizedKey(audioCandidate, 120) : null
  const communityId = communityCandidate ? normalizedKey(communityCandidate, 120) : null
  const safetyState = normalizedKey(input.safetyState ?? input.safety_state ?? 'unknown', 20)
  const classifierVersion = strictText(input.classifierVersion ?? input.classifier_version ?? 'server-v1', 80)
  if (!contentKind || !CONTENT_KINDS.has(contentKind) || !contentId || !['unknown', 'allowed', 'limited', 'blocked'].includes(safetyState) || !classifierVersion) {
    return { error: 'CLASSIFICATION_INVALID' }
  }
  const rawTopics = Array.isArray(input.topics) ? input.topics : []
  if (rawTopics.length > 5) return { error: 'CLASSIFICATION_TOPICS_INVALID' }
  const topics = []
  for (const rawTopic of rawTopics) {
    if (!isPlainObject(rawTopic)) return { error: 'CLASSIFICATION_TOPICS_INVALID' }
    const topicKey = normalizedKey(rawTopic.topicKey ?? rawTopic.topic_key, 80)
    const subtopicKey = rawTopic.subtopicKey ?? rawTopic.subtopic_key
      ? normalizedKey(rawTopic.subtopicKey ?? rawTopic.subtopic_key, 80)
      : null
    const confidence = numberOrNull(rawTopic.confidence)
    if (!topicKey || confidence === null || confidence < 0 || confidence > 1) {
      return { error: 'CLASSIFICATION_TOPICS_INVALID' }
    }
    topics.push({ topicKey, subtopicKey, confidence })
  }
  return {
    value: {
      contentKind,
      contentId,
      creatorId,
      languageCode,
      formatKey,
      audioKey,
      communityId,
      safetyState,
      classifierVersion,
      topics,
    },
  }
}

// Trusted ingestion only. This is intentionally not registered as an HTTP
// route, so an Android client cannot forge topic or safety classifications.
async function upsertTrustedContentClassification(client, input) {
  const flags = algorithmFeatureFlags()
  if (!flags.core || !flags.classification) return { error: 'USER_ALGORITHM_CLASSIFICATION_DISABLED' }
  const normalized = normalizeContentClassification(input)
  if (normalized.error) return normalized
  const value = normalized.value
  // First move the row to an ineligible state. Candidate queries only accept
  // `allowed`, so an interrupted topic rewrite can never leave a stale or
  // partially-classified item visible in recommendations.
  const classificationResult = await client.query(
    `INSERT INTO fallback_algorithm_content_classifications(
       content_kind, content_id, creator_id, language_code, format_key, audio_key,
       community_id, safety_state, classifier_version, classified_at, updated_at
     ) VALUES ($1, $2, $3, $4, $5, $6, $7, 'unknown', $8, NOW(), NOW())
     ON CONFLICT (content_kind, content_id)
     DO UPDATE SET creator_id = EXCLUDED.creator_id,
                   language_code = EXCLUDED.language_code,
                   format_key = EXCLUDED.format_key,
                   audio_key = EXCLUDED.audio_key,
                   community_id = EXCLUDED.community_id,
                   safety_state = 'unknown',
                   classifier_version = EXCLUDED.classifier_version,
                   classified_at = NOW(),
                   updated_at = NOW()
     RETURNING id::text`,
    [
      value.contentKind,
      value.contentId,
      value.creatorId,
      value.languageCode,
      value.formatKey,
      value.audioKey,
      value.communityId,
      value.classifierVersion,
    ],
  )
  const classificationId = classificationResult.rows[0]?.id
  if (!classificationId) return { error: 'CLASSIFICATION_WRITE_FAILED' }
  // Rewrite the topic set and publish the final safety state in one statement.
  // PostgreSQL serializes the row update, preventing concurrent classifications
  // from interleaving two topic sets.
  const finalState = await client.query(
    `WITH locked_classification AS (
       SELECT id FROM fallback_algorithm_content_classifications
        WHERE id = $1::uuid
        FOR UPDATE
     ), deleted_topics AS (
       DELETE FROM fallback_algorithm_content_topics
        WHERE classification_id IN (SELECT id FROM locked_classification)
        RETURNING classification_id
     ), inserted_topics AS (
       INSERT INTO fallback_algorithm_content_topics(
         classification_id, topic_key, subtopic_key, confidence, source
       )
       SELECT classification.id,
              topic.topic_key,
              COALESCE(topic.subtopic_key, ''),
              topic.confidence,
              'server'
         FROM locked_classification classification
         CROSS JOIN LATERAL jsonb_to_recordset($8::jsonb)
           AS topic(topic_key TEXT, subtopic_key TEXT, confidence NUMERIC)
        WHERE (SELECT COUNT(*) FROM deleted_topics) >= 0
       RETURNING classification_id
     )
     UPDATE fallback_algorithm_content_classifications
        SET creator_id = $2,
            language_code = $3,
            format_key = $4,
            audio_key = $5,
            community_id = $6,
            classifier_version = $7,
            safety_state = $9,
            classified_at = NOW(),
            updated_at = NOW()
      WHERE id IN (SELECT id FROM locked_classification)
        AND (SELECT COUNT(*) FROM inserted_topics) >= 0
      RETURNING id::text`,
    [
      classificationId,
      value.creatorId,
      value.languageCode,
      value.formatKey,
      value.audioKey,
      value.communityId,
      value.classifierVersion,
      JSON.stringify(value.topics.map((topic) => ({
        topic_key: topic.topicKey,
        subtopic_key: topic.subtopicKey || '',
        confidence: topic.confidence,
      }))),
      value.safetyState,
    ],
  )
  if (!finalState.rows[0]?.id) return { error: 'CLASSIFICATION_WRITE_FAILED' }
  return { id: classificationId }
}

function registerAlgorithmRoutes(app, { pool, authRequired, getCurrentUser, userIdentity }) {
  const limiters = createRouteLimiters()

  async function currentOwner(req, res, limiter) {
    const user = await getCurrentUser(pool, req)
    if (!user) {
      res.status(404).json({ error: 'USER_NOT_FOUND' })
      return null
    }
    const identity = userIdentity ? userIdentity(user) : null
    const userId = normalizeId(identity?.localUserId || user.id)
    if (!userId) {
      res.status(403).json({ error: 'USER_ID_INVALID' })
      return null
    }
    if (!consumeRateLimit(res, limiter, userId)) return null
    return { user, userId }
  }

  async function ownedProfile(req, res, owner, profileId) {
    const profile = await findOwnedProfile(pool, owner.userId, profileId)
    if (!profile) {
      res.status(404).json({ error: 'ALGORITHM_PROFILE_NOT_FOUND' })
      return null
    }
    return profile
  }

  function mutationVersion(req, res) {
    const revision = expectedRevision(req.body)
    if (revision !== null) return revision
    res.status(428).json({ error: 'ALGORITHM_PROFILE_VERSION_REQUIRED' })
    return null
  }

  app.get('/v1/data/algorithm/capabilities', authRequired, async (req, res, next) => {
    try {
      const owner = await currentOwner(req, res, limiters.read)
      if (!owner) return
      const flags = algorithmFeatureFlags()
      res.setHeader('Cache-Control', 'no-store')
      res.json(algorithmCapabilitiesResponse(flags))
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/algorithm/profiles', authRequired, async (req, res, next) => {
    try {
      if (!requireAlgorithmFeature(res)) return
      const owner = await currentOwner(req, res, limiters.read)
      if (!owner) return
      await ensureDefaultProfile(pool, owner.userId)
      const { rows } = await pool.query(
        `SELECT id::text, name, slug, mode, is_enabled, is_default, revision, created_at, updated_at
           FROM fallback_user_algorithm_profiles
          WHERE lower(user_id) = lower($1)
          ORDER BY is_default DESC, updated_at DESC, created_at ASC
          LIMIT 20`,
        [owner.userId],
      )
      res.setHeader('Cache-Control', 'no-store')
      res.json({ profiles: rows.map(profileResponse) })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/algorithm/profiles', authRequired, async (req, res, next) => {
    try {
      if (!requireAlgorithmFeature(res, 'preferences')) return
      const owner = await currentOwner(req, res, limiters.create)
      if (!owner) return
      const normalized = normalizeProfileCreate(req.body)
      if (normalized.error) return res.status(400).json({ error: normalized.error })
      const { rows } = await pool.query(
        `INSERT INTO fallback_user_algorithm_profiles(user_id, name, slug, mode, is_enabled, is_default)
         VALUES ($1, $2, $3, $4, FALSE, FALSE)
         ON CONFLICT (user_id, slug) DO NOTHING
         RETURNING id::text, name, slug, mode, is_enabled, is_default, revision, created_at, updated_at`,
        [owner.userId, normalized.value.name, normalized.value.slug, normalized.value.mode],
      )
      if (!rows[0]) return res.status(409).json({ error: 'ALGORITHM_PROFILE_SLUG_EXISTS' })
      await ensureProfileDefaults(pool, rows[0].id)
      res.status(201).json({ profile: profileResponse(rows[0]) })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/algorithm/profiles/:profileId/config', authRequired, async (req, res, next) => {
    try {
      if (!requireAlgorithmFeature(res)) return
      const owner = await currentOwner(req, res, limiters.read)
      if (!owner) return
      const profile = await ownedProfile(req, res, owner, req.params.profileId)
      if (!profile) return
      res.setHeader('Cache-Control', 'no-store')
      res.json(await loadAlgorithmConfiguration(pool, profile))
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/algorithm/config', authRequired, async (req, res, next) => {
    try {
      if (!requireAlgorithmFeature(res)) return
      const owner = await currentOwner(req, res, limiters.read)
      if (!owner) return
      const profile = await ownedProfile(req, res, owner, req.query.profileId || req.query.profile_id)
      if (!profile) return
      res.setHeader('Cache-Control', 'no-store')
      res.json(await loadAlgorithmConfiguration(pool, profile))
    } catch (error) {
      next(error)
    }
  })

  app.patch('/v1/data/algorithm/profiles/:profileId', authRequired, async (req, res, next) => {
    try {
      if (!requireAlgorithmFeature(res, 'preferences')) return
      const owner = await currentOwner(req, res, limiters.write)
      if (!owner) return
      const profile = await ownedProfile(req, res, owner, req.params.profileId)
      if (!profile) return
      const revision = mutationVersion(req, res)
      if (revision === null) return
      const normalized = normalizeProfilePatch(req.body)
      if (normalized.error) return res.status(400).json({ error: normalized.error })
      const result = await mutateOwnedProfile(pool, {
        userId: owner.userId,
        profileId: profile.id,
        expectedVersion: revision,
        mutate: async (client) => {
          const patch = normalized.value
          if (patch.make_default === true) {
            await client.query(
              `UPDATE fallback_user_algorithm_profiles
                  SET is_default = FALSE, revision = revision + 1, updated_at = NOW()
                WHERE lower(user_id) = lower($1) AND id <> $2::uuid AND is_default = TRUE`,
              [owner.userId, profile.id],
            )
          }
          const assignments = []
          const values = []
          for (const field of ['name', 'mode', 'is_enabled']) {
            if (Object.prototype.hasOwnProperty.call(patch, field)) {
              values.push(patch[field])
              assignments.push(`${field} = $${values.length}`)
            }
          }
          if (patch.make_default === true) assignments.push('is_default = TRUE')
          if (assignments.length > 0) {
            values.push(profile.id)
            await client.query(
              `UPDATE fallback_user_algorithm_profiles SET ${assignments.join(', ')}, updated_at = NOW()
                WHERE id = $${values.length}::uuid`,
              values,
            )
          }
          return {}
        },
      })
      if (result.error) return res.status(result.error === 'ALGORITHM_PROFILE_CONFLICT' ? 409 : 404).json(result)
      res.json({ profile: profileResponse(result.profile) })
    } catch (error) {
      next(error)
    }
  })

  app.put('/v1/data/algorithm/profiles/:profileId/preferences', authRequired, async (req, res, next) => {
    try {
      if (!requireAlgorithmFeature(res, 'preferences')) return
      const owner = await currentOwner(req, res, limiters.write)
      if (!owner) return
      const profile = await ownedProfile(req, res, owner, req.params.profileId)
      if (!profile) return
      const revision = mutationVersion(req, res)
      if (revision === null) return
      const rawPreferences = Array.isArray(req.body?.preferences) ? req.body.preferences : null
      if (!rawPreferences || rawPreferences.length === 0 || rawPreferences.length > MAX_PREFERENCES_PER_MUTATION) {
        return res.status(400).json({ error: 'PREFERENCES_BATCH_INVALID' })
      }
      const preferences = []
      const seen = new Set()
      for (const rawPreference of rawPreferences) {
        const normalized = normalizePreferenceInput(rawPreference)
        if (normalized.error) return res.status(400).json({ error: normalized.error })
        const key = preferenceIdentityKey(normalized.value)
        if (seen.has(key)) return res.status(400).json({ error: 'DUPLICATE_PREFERENCE' })
        seen.add(key)
        preferences.push(normalized.value)
      }
      const result = await mutateOwnedProfile(pool, {
        userId: owner.userId,
        profileId: profile.id,
        expectedVersion: revision,
        mutate: async (client) => {
          const updated = []
          for (const preference of preferences) {
            const query = await client.query(
              `INSERT INTO fallback_user_algorithm_preferences(
                 profile_id, surface, dimension, subject_key, subject_label, score, source, updated_at
               ) VALUES ($1::uuid, $2, $3, $4, $5, $6, 'explicit', NOW())
               ON CONFLICT (profile_id, surface, dimension, subject_key)
               DO UPDATE SET subject_label = EXCLUDED.subject_label,
                             score = EXCLUDED.score,
                             source = 'explicit',
                             updated_at = NOW()
               RETURNING id::text, surface, dimension, subject_key, subject_label, score, updated_at`,
              [profile.id, preference.surface, preference.dimension, preference.subjectKey, preference.subjectLabel, preference.score],
            )
            updated.push(preferenceResponse(query.rows[0]))
          }
          return { preferences: updated }
        },
      })
      if (result.error) return res.status(result.error === 'ALGORITHM_PROFILE_CONFLICT' ? 409 : 404).json(result)
      res.json({ profile: profileResponse(result.profile), preferences: result.preferences })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/algorithm/profiles/:profileId/preferences/:preferenceId', authRequired, async (req, res, next) => {
    try {
      if (!requireAlgorithmFeature(res, 'preferences')) return
      const owner = await currentOwner(req, res, limiters.write)
      if (!owner) return
      const profile = await ownedProfile(req, res, owner, req.params.profileId)
      if (!profile) return
      const revision = mutationVersion(req, res)
      if (revision === null) return
      const preferenceId = validUuid(req.params.preferenceId)
      if (!preferenceId) return res.status(400).json({ error: 'PREFERENCE_ID_INVALID' })
      const result = await mutateOwnedProfile(pool, {
        userId: owner.userId,
        profileId: profile.id,
        expectedVersion: revision,
        mutate: async (client) => {
          const deleted = await client.query(
            `DELETE FROM fallback_user_algorithm_preferences
              WHERE id = $1::uuid AND profile_id = $2::uuid
              RETURNING id::text`,
            [preferenceId, profile.id],
          )
          return deleted.rows[0] ? {} : { error: 'PREFERENCE_NOT_FOUND' }
        },
      })
      if (result.error) return res.status(result.error === 'ALGORITHM_PROFILE_CONFLICT' ? 409 : 404).json(result)
      res.status(204).end()
    } catch (error) {
      next(error)
    }
  })

  app.put('/v1/data/algorithm/profiles/:profileId/feed-mix/:surface', authRequired, async (req, res, next) => {
    try {
      if (!requireAlgorithmFeature(res, 'feedMix')) return
      const owner = await currentOwner(req, res, limiters.write)
      if (!owner) return
      const profile = await ownedProfile(req, res, owner, req.params.profileId)
      if (!profile) return
      const surface = normalizedSurface(req.params.surface)
      if (!surface) return res.status(400).json({ error: 'ALGORITHM_SURFACE_INVALID' })
      const revision = mutationVersion(req, res)
      if (revision === null) return
      const result = await mutateOwnedProfile(pool, {
        userId: owner.userId,
        profileId: profile.id,
        expectedVersion: revision,
        mutate: async (client) => {
          const current = await client.query(
            `SELECT surface, ${FEED_MIX_FIELDS.join(', ')}, updated_at
               FROM fallback_user_algorithm_feed_mixes
              WHERE profile_id = $1::uuid AND surface = $2
              FOR UPDATE`,
            [profile.id, surface],
          )
          const normalized = normalizeFeedMix(
            withoutMutationMetadata(req.body),
            current.rows[0] || defaultFeedMix(surface),
          )
          if (normalized.error) return normalized
          const mix = normalized.value
          const updated = await client.query(
            `INSERT INTO fallback_user_algorithm_feed_mixes(
               profile_id, surface, following_weight, interest_weight, community_weight,
               new_content_weight, trend_weight, exploration_weight, updated_at
             ) VALUES ($1::uuid, $2, $3, $4, $5, $6, $7, $8, NOW())
             ON CONFLICT (profile_id, surface)
             DO UPDATE SET following_weight = EXCLUDED.following_weight,
                           interest_weight = EXCLUDED.interest_weight,
                           community_weight = EXCLUDED.community_weight,
                           new_content_weight = EXCLUDED.new_content_weight,
                           trend_weight = EXCLUDED.trend_weight,
                           exploration_weight = EXCLUDED.exploration_weight,
                           updated_at = NOW()
             RETURNING surface, ${FEED_MIX_FIELDS.join(', ')}, updated_at`,
            [
              profile.id,
              surface,
              mix.following_weight,
              mix.interest_weight,
              mix.community_weight,
              mix.new_content_weight,
              mix.trend_weight,
              mix.exploration_weight,
            ],
          )
          return { feedMix: feedMixResponse(updated.rows[0]) }
        },
      })
      if (result.error) return res.status(result.error === 'ALGORITHM_PROFILE_CONFLICT' ? 409 : 400).json(result)
      res.json({ profile: profileResponse(result.profile), feedMix: result.feedMix })
    } catch (error) {
      next(error)
    }
  })

  app.put('/v1/data/algorithm/profiles/:profileId/data-permissions', authRequired, async (req, res, next) => {
    try {
      if (!requireAlgorithmFeature(res, 'dataPermissions')) return
      const owner = await currentOwner(req, res, limiters.write)
      if (!owner) return
      const profile = await ownedProfile(req, res, owner, req.params.profileId)
      if (!profile) return
      const revision = mutationVersion(req, res)
      if (revision === null) return
      const result = await mutateOwnedProfile(pool, {
        userId: owner.userId,
        profileId: profile.id,
        expectedVersion: revision,
        mutate: async (client) => {
          const current = await client.query(
            `SELECT ${DATA_PERMISSION_FIELDS.join(', ')}, updated_at
               FROM fallback_user_algorithm_data_permissions
              WHERE profile_id = $1::uuid
              FOR UPDATE`,
            [profile.id],
          )
          const normalized = normalizeDataPermissions(
            isPlainObject(req.body?.permissions) ? req.body.permissions : withoutMutationMetadata(req.body),
            current.rows[0] || defaultDataPermissions(),
          )
          if (normalized.error) return normalized
          const permissions = normalized.value
          const updated = await client.query(
            `INSERT INTO fallback_user_algorithm_data_permissions(
               profile_id, ${DATA_PERMISSION_FIELDS.join(', ')}, updated_at
             ) VALUES ($1::uuid, ${DATA_PERMISSION_FIELDS.map((_field, index) => `$${index + 2}`).join(', ')}, NOW())
             ON CONFLICT (profile_id)
             DO UPDATE SET ${DATA_PERMISSION_FIELDS.map((field) => `${field} = EXCLUDED.${field}`).join(', ')},
                           updated_at = NOW()
             RETURNING ${DATA_PERMISSION_FIELDS.join(', ')}, updated_at`,
            [profile.id, ...DATA_PERMISSION_FIELDS.map((field) => permissions[field])],
          )
          return { dataPermissions: dataPermissionsResponse(updated.rows[0]) }
        },
      })
      if (result.error) return res.status(result.error === 'ALGORITHM_PROFILE_CONFLICT' ? 409 : 400).json(result)
      res.json({ profile: profileResponse(result.profile), dataPermissions: result.dataPermissions })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/algorithm/profiles/:profileId/temporary-preferences', authRequired, async (req, res, next) => {
    try {
      if (!requireAlgorithmFeature(res, 'preferences')) return
      const owner = await currentOwner(req, res, limiters.write)
      if (!owner) return
      const profile = await ownedProfile(req, res, owner, req.params.profileId)
      if (!profile) return
      const revision = mutationVersion(req, res)
      if (revision === null) return
      const normalized = normalizeTemporaryPreferenceInput(req.body || {})
      if (normalized.error) return res.status(400).json({ error: normalized.error })
      const result = await mutateOwnedProfile(pool, {
        userId: owner.userId,
        profileId: profile.id,
        expectedVersion: revision,
        mutate: async (client) => {
          const active = await client.query(
            `SELECT COUNT(*)::int AS count
               FROM fallback_user_algorithm_temporary_preferences
              WHERE profile_id = $1::uuid AND expires_at > NOW()`,
            [profile.id],
          )
          if (Number(active.rows[0]?.count || 0) >= MAX_ACTIVE_TEMPORARY_PREFERENCES) {
            return { error: 'TEMPORARY_PREFERENCES_LIMIT' }
          }
          const preference = normalized.value
          const inserted = await client.query(
            `INSERT INTO fallback_user_algorithm_temporary_preferences(
               profile_id, surface, dimension, subject_key, subject_label, score, starts_at, expires_at
             ) VALUES ($1::uuid, $2, $3, $4, $5, $6, $7::timestamptz, $8::timestamptz)
             RETURNING id::text, surface, dimension, subject_key, subject_label, score, starts_at, expires_at, created_at`,
            [
              profile.id,
              preference.surface,
              preference.dimension,
              preference.subjectKey,
              preference.subjectLabel,
              preference.score,
              preference.startsAt,
              preference.expiresAt,
            ],
          )
          return { temporaryPreference: temporaryPreferenceResponse(inserted.rows[0]) }
        },
      })
      if (result.error) return res.status(result.error === 'ALGORITHM_PROFILE_CONFLICT' ? 409 : 400).json(result)
      res.status(201).json({ profile: profileResponse(result.profile), temporaryPreference: result.temporaryPreference })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/algorithm/profiles/:profileId/temporary-preferences/:preferenceId', authRequired, async (req, res, next) => {
    try {
      if (!requireAlgorithmFeature(res, 'preferences')) return
      const owner = await currentOwner(req, res, limiters.write)
      if (!owner) return
      const profile = await ownedProfile(req, res, owner, req.params.profileId)
      if (!profile) return
      const revision = mutationVersion(req, res)
      if (revision === null) return
      const temporaryId = validUuid(req.params.preferenceId)
      if (!temporaryId) return res.status(400).json({ error: 'TEMPORARY_PREFERENCE_ID_INVALID' })
      const result = await mutateOwnedProfile(pool, {
        userId: owner.userId,
        profileId: profile.id,
        expectedVersion: revision,
        mutate: async (client) => {
          const deleted = await client.query(
            `DELETE FROM fallback_user_algorithm_temporary_preferences
              WHERE id = $1::uuid AND profile_id = $2::uuid
              RETURNING id::text`,
            [temporaryId, profile.id],
          )
          return deleted.rows[0] ? {} : { error: 'TEMPORARY_PREFERENCE_NOT_FOUND' }
        },
      })
      if (result.error) return res.status(result.error === 'ALGORITHM_PROFILE_CONFLICT' ? 409 : 404).json(result)
      res.status(204).end()
    } catch (error) {
      next(error)
    }
  })
}

module.exports = {
  ALGORITHM_FLAGS,
  ALGORITHM_MODES,
  ALGORITHM_SURFACES,
  DATA_PERMISSION_FIELDS,
  FEED_MIX_FIELDS,
  PREFERENCE_DIMENSIONS,
  algorithmCapabilitiesResponse,
  algorithmFeatureFlags,
  defaultDataPermissions,
  defaultFeedMix,
  ensureAlgorithmTables,
  ensureDefaultProfile,
  findOwnedProfile,
  loadAlgorithmConfiguration,
  normalizeContentClassification,
  normalizeDataPermissions,
  normalizeFeedMix,
  normalizePreferenceInput,
  normalizeProfileCreate,
  normalizeTemporaryPreferenceInput,
  preferenceIdentityKey,
  registerAlgorithmRoutes,
  upsertTrustedContentClassification,
}
