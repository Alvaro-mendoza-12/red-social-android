'use strict'

/**
 * Server-authoritative recommendation route, deliberately isolated from the
 * legacy `/v1/data/feed` and `/v1/data/videos` endpoints.
 *
 * The host supplies `loadCandidates`. That callback is the only component that
 * knows how to load public, safety-screened Video or Social candidates from the
 * active datastore. This module owns authentication, profile ownership,
 * feature-gating, opaque cursors, ranking and truthful explanations.
 *
 * `loadCandidates` contract:
 *   async ({ owner, surface, profile, cursor, limit, maxCandidates, now }) => ({
 *     snapshotId: 'stable-provider-snapshot',
 *     providerState: { optional: 'JSON-safe state for the next page' },
 *     inferredSignals: { only server-authorized signal sources },
 *     candidates: [{
 *       id: 'ranking-id',
 *       item: { ...public API item to return },
 *       // safe metadata consumed by algorithm-ranking.js only
 *       creatorId, topics, subtopics, safety, feedSignals, ...
 *     }]
 *   })
 *
 * It must not use request-controlled user ids, private messages, precise
 * location, age or gender. A cursor is bound cryptographically to the
 * authenticated owner, selected profile, surface and configuration revision.
 */

const crypto = require('crypto')
const {
  algorithmFeatureFlags: defaultAlgorithmFeatureFlags,
  findOwnedProfile: defaultFindOwnedProfile,
  loadAlgorithmConfiguration: defaultLoadAlgorithmConfiguration,
} = require('./algorithm-routes')
const {
  RANKING_VERSION,
  rankAlgorithmCandidates: defaultRankAlgorithmCandidates,
} = require('./algorithm-ranking')

const CURSOR_VERSION = 1
const CURSOR_AAD = Buffer.from('novvin-authoritative-recommendations-v1', 'utf8')
const CURSOR_TTL_MS = 15 * 60 * 1000
const MAX_PAGE_SIZE = 60
const MAX_RANKED_CANDIDATES = 200
const MAX_CURSOR_STATE_BYTES = 2048
const SUPPORTED_SURFACES = new Set(['video', 'social'])

class RecommendationRouteError extends Error {
  constructor(code, status = 400) {
    super(code)
    this.code = code
    this.status = status
    this.expose = true
  }
}

function isPlainObject(value) {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function normalizeId(value, maxLength = 180) {
  const text = String(value == null ? '' : value).trim().toLowerCase()
  return text && text.length <= maxLength ? text : null
}

function normalizeSurface(value) {
  const surface = normalizeId(value, 32)
  return surface && SUPPORTED_SURFACES.has(surface) ? surface : null
}

function boundedInteger(value, min, max, fallback) {
  const number = Number(value)
  if (!Number.isInteger(number)) return fallback
  return Math.max(min, Math.min(max, number))
}

function cursorKey(secret) {
  const value = String(secret || '').trim()
  if (value.length < 16) return null
  return crypto.createHash('sha256').update(value, 'utf8').digest()
}

function cursorSecretFrom(options) {
  return String(
    options.cursorSecret
      || process.env.ALGORITHM_RECOMMENDATION_CURSOR_SECRET
      || process.env.JWT_SECRET
      || '',
  ).trim()
}

function base64Url(buffer) {
  return Buffer.from(buffer).toString('base64url')
}

function parseBase64Url(value) {
  try {
    return Buffer.from(String(value || ''), 'base64url')
  } catch {
    return null
  }
}

function normalizeCursorState(value) {
  if (value === undefined || value === null) return null
  let encoded
  try {
    encoded = JSON.stringify(value)
  } catch {
    throw new RecommendationRouteError('RECOMMENDATION_CURSOR_STATE_INVALID', 502)
  }
  if (!encoded || Buffer.byteLength(encoded, 'utf8') > MAX_CURSOR_STATE_BYTES) {
    throw new RecommendationRouteError('RECOMMENDATION_CURSOR_STATE_INVALID', 502)
  }
  try {
    return JSON.parse(encoded)
  } catch {
    throw new RecommendationRouteError('RECOMMENDATION_CURSOR_STATE_INVALID', 502)
  }
}

function normalizeCursorPayload(payload) {
  if (!isPlainObject(payload) || Number(payload.v) !== CURSOR_VERSION) return null
  const ownerId = normalizeId(payload.ownerId)
  const profileId = normalizeId(payload.profileId)
  const surface = normalizeSurface(payload.surface)
  const snapshotId = String(payload.snapshotId || '').trim()
  const position = Number(payload.position)
  const profileRevision = Number(payload.profileRevision)
  const issuedAt = Number(payload.issuedAt)
  if (!ownerId || !profileId || !surface || !snapshotId || snapshotId.length > 180
    || !Number.isInteger(position) || position < 0 || position > MAX_RANKED_CANDIDATES
    || !Number.isInteger(profileRevision) || profileRevision < 1
    || !Number.isFinite(issuedAt)) {
    return null
  }
  try {
    return {
      v: CURSOR_VERSION,
      ownerId,
      profileId,
      surface,
      snapshotId,
      position,
      profileRevision,
      issuedAt,
      providerState: normalizeCursorState(payload.providerState),
    }
  } catch {
    return null
  }
}

function encodeOpaqueCursor(payload, secret) {
  const key = cursorKey(secret)
  const normalized = normalizeCursorPayload(payload)
  if (!key || !normalized) throw new RecommendationRouteError('RECOMMENDATION_CURSOR_UNAVAILABLE', 503)
  const iv = crypto.randomBytes(12)
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv)
  cipher.setAAD(CURSOR_AAD)
  const plaintext = Buffer.from(JSON.stringify(normalized), 'utf8')
  const encrypted = Buffer.concat([cipher.update(plaintext), cipher.final()])
  const tag = cipher.getAuthTag()
  return `r${CURSOR_VERSION}.${base64Url(iv)}.${base64Url(tag)}.${base64Url(encrypted)}`
}

function decodeOpaqueCursor(token, secret, now = Date.now()) {
  const key = cursorKey(secret)
  if (!key) throw new RecommendationRouteError('RECOMMENDATION_CURSOR_UNAVAILABLE', 503)
  const [prefix, ivValue, tagValue, encryptedValue, extra] = String(token || '').split('.')
  if (prefix !== `r${CURSOR_VERSION}` || !ivValue || !tagValue || !encryptedValue || extra) {
    throw new RecommendationRouteError('RECOMMENDATION_CURSOR_INVALID')
  }
  const iv = parseBase64Url(ivValue)
  const tag = parseBase64Url(tagValue)
  const encrypted = parseBase64Url(encryptedValue)
  if (!iv || iv.length !== 12 || !tag || tag.length !== 16 || !encrypted || encrypted.length === 0) {
    throw new RecommendationRouteError('RECOMMENDATION_CURSOR_INVALID')
  }
  try {
    const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv)
    decipher.setAAD(CURSOR_AAD)
    decipher.setAuthTag(tag)
    const payload = normalizeCursorPayload(JSON.parse(Buffer.concat([
      decipher.update(encrypted),
      decipher.final(),
    ]).toString('utf8')))
    if (!payload || payload.issuedAt > now + 60_000 || now - payload.issuedAt > CURSOR_TTL_MS) {
      throw new RecommendationRouteError('RECOMMENDATION_CURSOR_INVALID')
    }
    return payload
  } catch (error) {
    if (error instanceof RecommendationRouteError) throw error
    throw new RecommendationRouteError('RECOMMENDATION_CURSOR_INVALID')
  }
}

function profileIsEnabled(profile) {
  return profile?.is_enabled === true || profile?.isEnabled === true
}

function profileRevision(profile) {
  const revision = Number(profile?.revision)
  return Number.isInteger(revision) && revision >= 1 ? revision : 1
}

function rankingFeedMix(configuration, surface) {
  const match = Array.isArray(configuration?.feedMixes)
    ? configuration.feedMixes.find((mix) => normalizeSurface(mix?.surface) === surface)
      || configuration.feedMixes.find((mix) => String(mix?.surface || '').trim().toLowerCase() === 'global')
    : null
  if (!match) return undefined
  return {
    following: match.following,
    interest: match.interests,
    community: match.communities,
    new_content: match.newContent,
    trend: match.trends,
    exploration: match.exploration,
  }
}

function rankingDataPermissions(permissions) {
  const source = isPlainObject(permissions) ? permissions : {}
  return {
    use_likes: source.likes === true,
    use_follows: source.follows === true,
    use_searches: source.searches === true,
    use_watch_time: source.watchTime === true,
    use_scroll_velocity: source.scrollVelocity === true,
    use_profile_visits: source.profileVisits === true,
    use_public_interactions: source.publicInteractions === true,
    use_community_activity: source.communityActivity === true,
    use_live_activity: source.liveActivity === true,
  }
}

function rankingPreferences(configuration) {
  const permanent = Array.isArray(configuration?.preferences) ? configuration.preferences : []
  const temporary = Array.isArray(configuration?.temporaryPreferences) ? configuration.temporaryPreferences : []
  return [...permanent, ...temporary]
}

function validateCandidateBatch(loaded) {
  if (!isPlainObject(loaded) || !Array.isArray(loaded.candidates)) {
    throw new RecommendationRouteError('RECOMMENDATION_CANDIDATES_INVALID', 502)
  }
  const snapshotId = String(loaded.snapshotId || '').trim()
  if (!snapshotId || snapshotId.length > 180) {
    throw new RecommendationRouteError('RECOMMENDATION_SNAPSHOT_INVALID', 502)
  }
  const candidates = []
  const ids = new Set()
  for (const rawCandidate of loaded.candidates.slice(0, MAX_RANKED_CANDIDATES)) {
    if (!isPlainObject(rawCandidate)) throw new RecommendationRouteError('RECOMMENDATION_CANDIDATE_INVALID', 502)
    const id = normalizeId(rawCandidate.id)
    if (!id || ids.has(id) || !isPlainObject(rawCandidate.item)) {
      throw new RecommendationRouteError('RECOMMENDATION_CANDIDATE_INVALID', 502)
    }
    ids.add(id)
    candidates.push(rawCandidate)
  }
  return {
    snapshotId,
    providerState: normalizeCursorState(loaded.providerState),
    inferredSignals: isPlainObject(loaded.inferredSignals) ? loaded.inferredSignals : {},
    candidates,
  }
}

function candidateItem(candidate) {
  // The injected provider chooses the public envelope. Do not return the
  // ranking metadata/safety flags alongside the API item.
  return { ...candidate.item }
}

function ownerFrom(user, identity) {
  const userId = normalizeId(identity?.localUserId || identity?.preferredUserId || user?.id)
  return userId ? { user, identity, userId } : null
}

function fail(res, status, error) {
  res.status(status).json({ error })
}

/**
 * Register the isolated recommendations endpoint. A later integration only
 * needs to import this module, pass the existing auth helpers/pool, and supply
 * a trusted `loadCandidates` adapter. No legacy route needs to change.
 */
function registerAlgorithmRecommendationRoutes(app, options = {}) {
  const {
    pool,
    authRequired,
    getCurrentUser,
    userIdentity,
    loadCandidates,
    algorithmFeatureFlags = defaultAlgorithmFeatureFlags,
    findOwnedProfile = defaultFindOwnedProfile,
    loadAlgorithmConfiguration = defaultLoadAlgorithmConfiguration,
    rankAlgorithmCandidates = defaultRankAlgorithmCandidates,
    cursorSecret = cursorSecretFrom(options),
    now = () => Date.now(),
  } = options

  if (!app || typeof app.get !== 'function') throw new TypeError('app.get is required')
  if (typeof authRequired !== 'function') throw new TypeError('authRequired is required')
  if (typeof getCurrentUser !== 'function' || typeof userIdentity !== 'function') {
    throw new TypeError('getCurrentUser and userIdentity are required')
  }
  if (typeof loadCandidates !== 'function') throw new TypeError('loadCandidates is required')
  if (typeof algorithmFeatureFlags !== 'function'
    || typeof findOwnedProfile !== 'function'
    || typeof loadAlgorithmConfiguration !== 'function'
    || typeof rankAlgorithmCandidates !== 'function') {
    throw new TypeError('algorithm helpers are required')
  }

  app.get('/v1/data/recommendations', authRequired, async (req, res, next) => {
    try {
      const flags = algorithmFeatureFlags()
      if (!flags?.core) return fail(res, 404, 'USER_ALGORITHM_DISABLED')
      // A first page can be followed by a second one at any time. Refuse the
      // request before reading candidates when a server-only cursor key is not
      // configured, instead of returning a partial feed whose continuation
      // cannot be safely bound to the authenticated profile.
      if (!cursorKey(cursorSecret)) return fail(res, 503, 'RECOMMENDATION_CURSOR_UNAVAILABLE')

      const user = await getCurrentUser(pool, req)
      if (!user) return fail(res, 404, 'USER_NOT_FOUND')
      const owner = ownerFrom(user, userIdentity(user))
      if (!owner) return fail(res, 403, 'USER_ID_INVALID')

      const surface = normalizeSurface(req.query?.surface)
      if (!surface) return fail(res, 400, 'RECOMMENDATION_SURFACE_INVALID')
      const pageSize = boundedInteger(req.query?.limit, 1, MAX_PAGE_SIZE, 20)
      const requestedProfileId = normalizeId(req.query?.profileId ?? req.query?.profile_id)
      const rawCursor = String(req.query?.cursor || '').trim()
      const timestamp = Number(now())
      const decodedCursor = rawCursor ? decodeOpaqueCursor(rawCursor, cursorSecret, timestamp) : null

      if (decodedCursor) {
        if (decodedCursor.ownerId !== owner.userId
          || decodedCursor.surface !== surface
          || (requestedProfileId && requestedProfileId !== decodedCursor.profileId)) {
          return fail(res, 400, 'RECOMMENDATION_CURSOR_INVALID')
        }
      }

      const profileId = decodedCursor?.profileId || requestedProfileId || undefined
      const profile = await findOwnedProfile(pool, owner.userId, profileId)
      if (!profile) return fail(res, 404, 'ALGORITHM_PROFILE_NOT_FOUND')
      if (!profileIsEnabled(profile)) return fail(res, 409, 'ALGORITHM_PROFILE_DISABLED')
      if (decodedCursor && decodedCursor.profileRevision !== profileRevision(profile)) {
        return fail(res, 409, 'RECOMMENDATION_CURSOR_STALE')
      }

      const configuration = await loadAlgorithmConfiguration(pool, profile)
      const loaded = validateCandidateBatch(await loadCandidates({
        owner,
        surface,
        profile,
        cursor: decodedCursor && {
          snapshotId: decodedCursor.snapshotId,
          position: decodedCursor.position,
          providerState: decodedCursor.providerState,
        },
        limit: pageSize,
        maxCandidates: MAX_RANKED_CANDIDATES,
        now: timestamp,
      }))
      if (decodedCursor && decodedCursor.snapshotId !== loaded.snapshotId) {
        return fail(res, 409, 'RECOMMENDATION_CURSOR_STALE')
      }

      const ranking = rankAlgorithmCandidates({
        candidates: loaded.candidates,
        preferences: rankingPreferences(configuration),
        feedMix: rankingFeedMix(configuration, surface),
        inferredSignals: loaded.inferredSignals,
        dataPermissions: rankingDataPermissions(configuration?.dataPermissions),
        surface,
        limit: MAX_RANKED_CANDIDATES,
        now: timestamp,
      })
      const position = decodedCursor?.position || 0
      const selected = ranking.ranked.slice(position, position + pageSize)
      const nextPosition = position + selected.length
      const hasMore = nextPosition < ranking.ranked.length
      const nextCursor = hasMore
        ? encodeOpaqueCursor({
          v: CURSOR_VERSION,
          ownerId: owner.userId,
          profileId: normalizeId(profile.id),
          profileRevision: profileRevision(profile),
          surface,
          snapshotId: loaded.snapshotId,
          position: nextPosition,
          issuedAt: timestamp,
          providerState: loaded.providerState,
        }, cursorSecret)
        : null

      const payload = {
        items: selected.map((entry) => candidateItem(loaded.candidates[entry.sourceIndex])),
        page: {
          nextCursor,
          hasMore,
        },
        algorithm: {
          authoritative: true,
          version: RANKING_VERSION,
          profileId: String(profile.id),
          explanationsEnabled: flags.explanations === true,
        },
      }
      if (flags.explanations === true) {
        payload.explanations = selected.map((entry) => ({
          candidateId: entry.candidateId,
          explanation: entry.explanation,
        }))
      }
      res.setHeader('Cache-Control', 'private, no-store')
      res.json(payload)
    } catch (error) {
      if (error instanceof RecommendationRouteError) {
        return fail(res, error.status, error.code)
      }
      next(error)
    }
  })
}

module.exports = {
  CURSOR_TTL_MS,
  CURSOR_VERSION,
  MAX_PAGE_SIZE,
  MAX_RANKED_CANDIDATES,
  RecommendationRouteError,
  decodeOpaqueCursor,
  encodeOpaqueCursor,
  rankingDataPermissions,
  rankingFeedMix,
  registerAlgorithmRecommendationRoutes,
}
