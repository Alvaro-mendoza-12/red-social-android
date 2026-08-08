const { createFixedWindowRateLimiter, stableKey } = require('./request-security')

// This route deliberately owns no data source. The injected loader must return
// only already-public, policy-eligible candidates; this boundary then performs
// consent/capability gating and emits a small, fixed card contract.

const SOCIALIZATION_CARD_FLAGS = Object.freeze({
  core: 'SOCIALIZATION_ENABLED',
  cards: 'SOCIALIZATION_CARDS_ENABLED',
  discovery: 'SOCIALIZATION_DISCOVERY_ENABLED',
  communityDiscovery: 'SOCIALIZATION_COMMUNITY_DISCOVERY_ENABLED',
})

const CARD_TYPES = Object.freeze({
  person: Object.freeze({ capability: 'discovery', action: 'OPEN_PROFILE', reason: 'PUBLIC_PROFILE' }),
  community: Object.freeze({ capability: 'communityDiscovery', action: 'OPEN_COMMUNITY', reason: 'PUBLIC_COMMUNITY' }),
  challenge: Object.freeze({ capability: 'discovery', action: 'OPEN_CHALLENGE', reason: 'ACTIVE_CHALLENGE' }),
  live: Object.freeze({ capability: 'discovery', action: 'OPEN_LIVE', reason: 'ACTIVE_LIVE' }),
})

const CARD_SURFACES = new Set(['social', 'video', 'live'])
const CANDIDATE_KEYS = new Set(['type', 'targetId', 'title', 'subtitle', 'imageUrl', 'expiresAt'])
const MAX_LIMIT = 4
const MAX_LOADER_CANDIDATES = 32

function envFlag(name) {
  return /^(1|true|yes|on)$/i.test(String(process.env[name] || '').trim())
}

function socializationCardFeatureFlags() {
  return {
    core: envFlag(SOCIALIZATION_CARD_FLAGS.core),
    cards: envFlag(SOCIALIZATION_CARD_FLAGS.cards),
    discovery: envFlag(SOCIALIZATION_CARD_FLAGS.discovery),
    communityDiscovery: envFlag(SOCIALIZATION_CARD_FLAGS.communityDiscovery),
  }
}

function cleanText(value, maxLength) {
  if (typeof value !== 'string') return null
  const text = value.replace(/[\u0000-\u001F\u007F]/g, ' ').trim()
  return text && text.length <= maxLength ? text : null
}

function opaqueIdentifier(value, maxLength = 160) {
  if (typeof value !== 'string') return null
  const text = value.trim()
  if (!text || text.length > maxLength) return null
  return /^[A-Za-z0-9][A-Za-z0-9:_-]*$/.test(text) ? text : null
}

function opaqueCursor(value) {
  if (typeof value !== 'string') return null
  const text = value.trim()
  if (!text || text.length > 512) return null
  return /^[A-Za-z0-9._~-]+$/.test(text) ? text : null
}

function normalizeSurface(value) {
  const surface = typeof value === 'string' ? value.trim().toLowerCase() : 'social'
  return CARD_SURFACES.has(surface) ? surface : null
}

function normalizePlacement(value) {
  if (value === undefined || value === null || value === '') return 'after_0'
  if (typeof value !== 'string') return null
  const match = /^after_([0-9]{1,3})$/.exec(value.trim())
  if (!match) return null
  const offset = Number(match[1])
  return Number.isSafeInteger(offset) && offset <= 200 ? `after_${offset}` : null
}

function normalizeLimit(value) {
  if (value === undefined || value === null || value === '') return 1
  if (typeof value !== 'string' && typeof value !== 'number') return null
  const parsed = Number(value)
  return Number.isInteger(parsed) && parsed >= 1 && parsed <= MAX_LIMIT ? parsed : null
}

function preferenceGranted(preferences, snakeCase, camelCase) {
  return preferences?.[snakeCase] === true || preferences?.[camelCase] === true
}

function cardCapabilities(preferences, flags = socializationCardFeatureFlags()) {
  const coreEnabled = flags.core === true && flags.cards === true
  return {
    discovery: Boolean(coreEnabled && flags.discovery === true && preferenceGranted(preferences, 'discovery_opt_in', 'discoveryOptIn')),
    communityDiscovery: Boolean(
      coreEnabled &&
      flags.communityDiscovery === true &&
      preferenceGranted(preferences, 'community_discovery_opt_in', 'communityDiscoveryOptIn'),
    ),
  }
}

function enabledCardTypes(capabilities) {
  return Object.keys(CARD_TYPES).filter((type) => capabilities[CARD_TYPES[type].capability] === true)
}

function normalizeImageUrl(value) {
  if (value === undefined || value === null || value === '') return null
  if (typeof value !== 'string' || value.length > 2048) return null
  try {
    const parsed = new URL(value)
    return parsed.protocol === 'https:' ? parsed.toString() : null
  } catch {
    return null
  }
}

function normalizeExpiry(value, now = Date.now()) {
  if (value === undefined || value === null || value === '') return null
  if (typeof value !== 'string') return null
  const timestamp = Date.parse(value)
  if (!Number.isFinite(timestamp) || timestamp <= now) return null
  return new Date(timestamp).toISOString()
}

function isExactCandidate(candidate) {
  return Boolean(candidate) &&
    typeof candidate === 'object' &&
    !Array.isArray(candidate) &&
    Object.keys(candidate).every((key) => CANDIDATE_KEYS.has(key))
}

function normalizeCandidate(candidate, { capabilities, now = Date.now() } = {}) {
  if (!isExactCandidate(candidate)) return null
  const type = typeof candidate.type === 'string' ? candidate.type.trim().toLowerCase() : ''
  const definition = CARD_TYPES[type]
  if (!definition || capabilities?.[definition.capability] !== true) return null

  const targetId = opaqueIdentifier(candidate.targetId)
  const title = cleanText(candidate.title, 96)
  if (!targetId || !title) return null

  const hasSubtitle = Object.prototype.hasOwnProperty.call(candidate, 'subtitle')
  const subtitle = hasSubtitle ? cleanText(candidate.subtitle, 180) : null
  if (hasSubtitle && candidate.subtitle !== null && subtitle === null) return null

  const hasImage = Object.prototype.hasOwnProperty.call(candidate, 'imageUrl')
  const imageUrl = hasImage ? normalizeImageUrl(candidate.imageUrl) : null
  if (hasImage && candidate.imageUrl !== null && candidate.imageUrl !== '' && imageUrl === null) return null

  const hasExpiry = Object.prototype.hasOwnProperty.call(candidate, 'expiresAt')
  const expiresAt = hasExpiry ? normalizeExpiry(candidate.expiresAt, now) : null
  if (hasExpiry && candidate.expiresAt !== null && candidate.expiresAt !== '' && expiresAt === null) return null

  return {
    id: `${type}:${targetId}`,
    type,
    title,
    ...(subtitle ? { subtitle } : {}),
    ...(imageUrl ? { imageUrl } : {}),
    ...(expiresAt ? { expiresAt } : {}),
    reasonCode: definition.reason,
    action: {
      type: definition.action,
      targetId,
    },
    dismissible: true,
  }
}

function sanitizeCards(candidates, { capabilities, limit, now = Date.now() } = {}) {
  if (!Array.isArray(candidates)) return []
  const cards = []
  const seen = new Set()
  for (const candidate of candidates.slice(0, MAX_LOADER_CANDIDATES)) {
    const card = normalizeCandidate(candidate, { capabilities, now })
    if (!card || seen.has(card.id)) continue
    seen.add(card.id)
    cards.push(card)
    if (cards.length >= limit) break
  }
  return cards
}

function normalizeLoaderResult(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  const allowedKeys = new Set(['items', 'nextCursor'])
  if (Object.keys(value).some((key) => !allowedKeys.has(key))) return null
  if (!Array.isArray(value.items)) return null
  return { items: value.items, nextCursor: value.nextCursor ?? null }
}

function createSocializationCardsRateLimiter() {
  return createFixedWindowRateLimiter({ limit: 60, windowMs: 60_000 })
}

function consumeRateLimit(res, limiter, identity) {
  const result = limiter(stableKey(identity.displayUserId))
  res.setHeader?.('x-ratelimit-limit', String(result.limit))
  res.setHeader?.('x-ratelimit-remaining', String(result.remaining))
  if (result.allowed) return true
  res.setHeader?.('retry-after', String(result.retryAfterSeconds))
  res.status(429).json({ error: 'SOCIALIZATION_CARDS_RATE_LIMITED' })
  return false
}

function validIdentity(identity) {
  const identityValues = identity?.ids instanceof Set
    ? Array.from(identity.ids)
    : Array.isArray(identity?.ids)
      ? identity.ids
      : []
  return Boolean(
    identity &&
    opaqueIdentifier(identity.displayUserId) &&
    identityValues.some((value) => opaqueIdentifier(value)),
  )
}

async function decodeCursor(rawCursor, codec, context) {
  if (rawCursor === undefined || rawCursor === null || rawCursor === '') return { value: null }
  const token = opaqueCursor(rawCursor)
  if (!token || !codec || typeof codec.decode !== 'function') return { error: 'SOCIALIZATION_CURSOR_INVALID' }
  const value = await codec.decode(token, context)
  return value === undefined || value === null ? { error: 'SOCIALIZATION_CURSOR_INVALID' } : { value }
}

async function encodeCursor(value, codec, context) {
  if (value === undefined || value === null || value === '') return null
  if (!codec || typeof codec.encode !== 'function') return null
  const token = await codec.encode(value, context)
  return opaqueCursor(token)
}

function requireCardsEnabled(res, flags) {
  if (flags.core !== true) {
    res.status(404).json({ error: 'SOCIALIZATION_DISABLED' })
    return false
  }
  if (flags.cards !== true) {
    res.status(404).json({ error: 'SOCIALIZATION_CARDS_DISABLED' })
    return false
  }
  return true
}

function responseBody({ surface, placement, capabilities, items, nextCursor }) {
  return {
    version: 1,
    enabled: enabledCardTypes(capabilities).length > 0,
    surface,
    placement,
    nextCursor: nextCursor || null,
    items,
    safeguards: {
      consentRequired: true,
      serverFiltered: true,
      automaticContact: false,
    },
  }
}

function registerSocializationCardRoutes(app, {
  pool,
  authRequired,
  getCurrentUser,
  userIdentity,
  loadPreferences,
  loadCandidates,
  cursorCodec = null,
  featureFlags = socializationCardFeatureFlags,
  rateLimiter = createSocializationCardsRateLimiter(),
  now = () => Date.now(),
} = {}) {
  if (!app || typeof app.get !== 'function') throw new TypeError('app.get is required')
  if (typeof authRequired !== 'function') throw new TypeError('authRequired is required')
  if (typeof getCurrentUser !== 'function') throw new TypeError('getCurrentUser is required')
  if (typeof userIdentity !== 'function') throw new TypeError('userIdentity is required')
  if (typeof loadPreferences !== 'function') throw new TypeError('loadPreferences is required')
  if (typeof loadCandidates !== 'function') throw new TypeError('loadCandidates is required')
  if (typeof featureFlags !== 'function') throw new TypeError('featureFlags is required')
  if (typeof rateLimiter !== 'function') throw new TypeError('rateLimiter is required')

  app.get('/v1/data/socialization/cards', authRequired, async (req, res, next) => {
    try {
      const flags = featureFlags()
      if (!requireCardsEnabled(res, flags)) return

      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      if (!validIdentity(identity)) return res.status(403).json({ error: 'SOCIALIZATION_IDENTITY_INVALID' })
      if (!consumeRateLimit(res, rateLimiter, identity)) return

      const surface = normalizeSurface(req?.query?.surface)
      const placement = normalizePlacement(req?.query?.placement)
      const limit = normalizeLimit(req?.query?.limit)
      if (!surface || !placement || limit === null) {
        return res.status(400).json({ error: 'SOCIALIZATION_CARDS_REQUEST_INVALID' })
      }

      const preferences = await loadPreferences({ pool, identity })
      const capabilities = cardCapabilities(preferences, flags)
      const types = enabledCardTypes(capabilities)
      res.setHeader?.('Cache-Control', 'private, no-store')
      if (types.length === 0) {
        return res.json(responseBody({ surface, placement, capabilities, items: [], nextCursor: null }))
      }

      const cursorContext = { identity, surface, placement }
      const cursor = await decodeCursor(req?.query?.cursor, cursorCodec, cursorContext)
      if (cursor.error) return res.status(400).json({ error: cursor.error })

      const loaded = normalizeLoaderResult(await loadCandidates({
        identity,
        surface,
        placement,
        limit: Math.min(MAX_LOADER_CANDIDATES, limit * 4),
        cursor: cursor.value,
        allowedTypes: types,
      }))
      if (!loaded) return res.status(503).json({ error: 'SOCIALIZATION_CARDS_UNAVAILABLE' })

      const cards = sanitizeCards(loaded.items, { capabilities, limit, now: now() })
      const nextCursor = await encodeCursor(loaded.nextCursor, cursorCodec, cursorContext)
      res.json(responseBody({ surface, placement, capabilities, items: cards, nextCursor }))
    } catch (error) {
      next(error)
    }
  })
}

module.exports = {
  CARD_SURFACES,
  CARD_TYPES,
  MAX_LIMIT,
  SOCIALIZATION_CARD_FLAGS,
  cardCapabilities,
  createSocializationCardsRateLimiter,
  decodeCursor,
  enabledCardTypes,
  encodeCursor,
  normalizeCandidate,
  normalizeLimit,
  normalizeLoaderResult,
  normalizePlacement,
  normalizeSurface,
  opaqueCursor,
  registerSocializationCardRoutes,
  responseBody,
  sanitizeCards,
  socializationCardFeatureFlags,
}
