'use strict'

/**
 * Safe candidate adapter for the isolated algorithm recommendations route.
 *
 * This module deliberately has no database queries and does not register an
 * HTTP route. A deployment must inject two server-side readers:
 *
 * - loadRawCandidates: returns a stable, already-snapshotted page for one
 *   surface. Each row has the trusted safety envelope documented below.
 * - loadBlockEdges: returns the authoritative block edges for the viewer and
 *   candidate creator aliases.
 *
 * That separation lets the adapter enforce public visibility, account state,
 * self-exclusion and *both* directions of blocking before ranking. It must
 * never be fed client candidates or private-message/location/age/gender data.
 *
 * Required raw candidate envelope (all server-derived):
 * {
 *   id, surface: 'video' | 'social', creatorId, creatorAliases?: string[],
 *   safety: {
 *     contentPublic: true,
 *     contentEligible: true,
 *     creatorVisible: true,
 *     creatorHidden: false,
 *     creatorSuspended: false,
 *     // ageEligible may be a policy verdict; never pass an actual age.
 *     ageEligible?: boolean,
 *   },
 *   item: { public response fields only },
 *   // optional ranking metadata accepted by algorithm-ranking.js
 * }
 */

const SUPPORTED_SURFACES = new Set(['video', 'social'])
const MAX_CANDIDATES = 200
const MAX_ITEM_DEPTH = 8
const MAX_ITEM_KEYS = 160
const MAX_ITEM_ARRAY = 100
const MAX_ITEM_TEXT = 8_000

const UNSAFE_STATUSES = new Set([
  'removed',
  'suspended',
  'blocked',
  'private',
  'hidden',
  'restricted',
  'age_restricted',
  'deleted',
])

const RANKING_DIMENSIONS = new Set([
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

const INFERENCE_SOURCES = new Set([
  'likes',
  'follows',
  'searches',
  'watch_time',
  'scroll_velocity',
  'profile_visits',
  'public_interactions',
  'community_activity',
  'live_activity',
])

const FEED_SIGNAL_KEYS = new Set([
  'following',
  'interest',
  'community',
  'new_content',
  'trend',
  'exploration',
])

// These fields must never cross the candidate-provider boundary. Public-item
// mappers should still explicitly select their contract; this is a final
// defensive scrubber, not a substitute for schema-level access control.
const SENSITIVE_ITEM_KEY = /(?:^|_)(?:password|secret|token|authorization|cookie|session|email|phone|address|latitude|longitude|location|coordinates?|private(?:_?messages?)?|direct(?:_?messages?)?|message(?:_?body)?|birth(?:_?date)?|age|gender|government(?:_?id)?)(?:$|_)/i

class AlgorithmCandidateProviderError extends Error {
  constructor(code) {
    super(code)
    this.name = 'AlgorithmCandidateProviderError'
    this.code = code
  }
}

function isPlainObject(value) {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function text(value, maxLength = 180) {
  const result = String(value == null ? '' : value).trim()
  return result && result.length <= maxLength ? result : null
}

function identifier(value, maxLength = 180) {
  const result = text(value, maxLength)
  return result ? result.toLowerCase() : null
}

function normalizedSurface(value) {
  const result = identifier(value, 32)
  return result && SUPPORTED_SURFACES.has(result) ? result : null
}

function boundedNumber(value, min, max, fallback = 0) {
  const number = Number(value)
  return Number.isFinite(number) ? Math.max(min, Math.min(max, number)) : fallback
}

function uniqueIdentifiers(values, maxItems = 32) {
  const result = []
  const seen = new Set()
  const source = Array.isArray(values) ? values : [values]
  for (const value of source) {
    const normalized = identifier(value)
    if (!normalized || seen.has(normalized)) continue
    seen.add(normalized)
    result.push(normalized)
    if (result.length >= maxItems) break
  }
  return result
}

function valuesOf(object, keys) {
  if (!isPlainObject(object)) return []
  return keys.flatMap((key) => {
    const value = object[key]
    return Array.isArray(value) ? value : [value]
  })
}

function ownerAliases(owner) {
  const identity = owner?.identity || {}
  const identityIds = identity?.ids instanceof Set
    ? Array.from(identity.ids)
    : Array.isArray(identity?.ids)
      ? identity.ids
      : []
  return new Set(uniqueIdentifiers([
    owner?.userId,
    identity?.displayUserId,
    identity?.localUserId,
    identity?.preferredUserId,
    ...identityIds,
  ]))
}

function candidateCreatorAliases(raw) {
  const creator = isPlainObject(raw?.creator) ? raw.creator : {}
  const aliases = uniqueIdentifiers([
    raw?.creatorId,
    raw?.creator_id,
    raw?.ownerId,
    raw?.owner_id,
    raw?.authorId,
    raw?.author_id,
    raw?.userId,
    raw?.user_id,
    creator.id,
    creator.userId,
    creator.user_id,
    ...valuesOf(raw, ['creatorAliases', 'creator_aliases', 'creatorIds', 'creator_ids']),
    ...valuesOf(creator, ['aliases', 'ids']),
  ])
  return aliases
}

function statusOf(...sources) {
  for (const source of sources) {
    if (!isPlainObject(source)) continue
    for (const key of ['status', 'moderationStatus', 'moderation_status', 'accountStatus', 'account_status']) {
      const value = identifier(source[key], 64)
      if (value) return value
    }
  }
  return null
}

function hasTrueFlag(sources, keys) {
  for (const source of sources) {
    if (!isPlainObject(source)) continue
    if (keys.some((key) => source[key] === true)) return true
  }
  return false
}

function nonPublicVisibility(sources, keys) {
  for (const source of sources) {
    if (!isPlainObject(source)) continue
    for (const key of keys) {
      if (source[key] === undefined || source[key] === null || source[key] === '') continue
      const visibility = identifier(source[key], 48)
      if (visibility && visibility !== 'public' && visibility !== 'everyone') return true
    }
  }
  return false
}

function hiddenBreakMode(sources) {
  for (const source of sources) {
    if (!isPlainObject(source)) continue
    const mode = identifier(source.breakMode ?? source.break_mode, 48)
    if (mode === 'hidden') return true
  }
  return false
}

function hasActiveSuspension(sources, now = Date.now()) {
  for (const source of sources) {
    if (!isPlainObject(source)) continue
    for (const key of ['suspendedUntil', 'suspended_until', 'suspensionEndDate', 'suspension_end_date']) {
      if (!source[key]) continue
      const timestamp = Date.parse(source[key])
      if (Number.isFinite(timestamp) && timestamp > now) return true
    }
  }
  return false
}

function safetyVerdict(raw, now = Date.now()) {
  if (!isPlainObject(raw)) return { allowed: false, reason: 'CANDIDATE_INVALID' }
  const safety = raw.safety
  const creator = isPlainObject(raw.creator) ? raw.creator : {}
  if (!isPlainObject(safety)
    || safety.contentPublic !== true
    || safety.contentEligible !== true
    || safety.creatorVisible !== true
    || safety.creatorHidden !== false
    || safety.creatorSuspended !== false) {
    return { allowed: false, reason: 'SAFETY_ENVELOPE_INCOMPLETE' }
  }
  if (raw.isPublic === false || raw.is_public === false
    || nonPublicVisibility([raw, safety], ['visibility', 'contentVisibility', 'content_visibility'])) {
    return { allowed: false, reason: 'CONTENT_NOT_PUBLIC' }
  }
  if (nonPublicVisibility([creator, safety], ['profileVisibility', 'profile_visibility', 'visibility', 'creatorVisibility', 'creator_visibility'])) {
    return { allowed: false, reason: 'CREATOR_NOT_PUBLIC' }
  }
  if (hasTrueFlag([raw, safety, creator], ['hidden', 'isHidden', 'is_hidden', 'creatorHidden', 'creator_hidden'])) {
    return { allowed: false, reason: 'CREATOR_HIDDEN' }
  }
  if (hiddenBreakMode([raw, safety, creator])) {
    return { allowed: false, reason: 'CREATOR_HIDDEN' }
  }
  if (hasTrueFlag([raw, safety, creator], ['suspended', 'isSuspended', 'is_suspended', 'creatorSuspended', 'creator_suspended'])) {
    return { allowed: false, reason: 'CREATOR_SUSPENDED' }
  }
  if (hasActiveSuspension([raw, safety, creator], now)) {
    return { allowed: false, reason: 'CREATOR_SUSPENDED' }
  }
  const status = statusOf(raw, safety, creator)
  if (status && UNSAFE_STATUSES.has(status)) {
    return { allowed: false, reason: 'CONTENT_OR_CREATOR_INELIGIBLE' }
  }
  if (safety.ageEligible === false || raw.ageEligible === false || raw.age_eligible === false) {
    return { allowed: false, reason: 'AGE_POLICY' }
  }
  if (raw.isEligible === false || raw.is_eligible === false || safety.isEligible === false) {
    return { allowed: false, reason: 'CONTENT_INELIGIBLE' }
  }
  return { allowed: true, reason: 'SAFE' }
}

function cleanPublicValue(value, depth = 0) {
  if (value === null || value === undefined) return value === null ? null : undefined
  if (typeof value === 'string') {
    const cleaned = value.replace(/[\u0000-\u001F\u007F]/g, ' ').trim()
    return cleaned.length <= MAX_ITEM_TEXT ? cleaned : cleaned.slice(0, MAX_ITEM_TEXT)
  }
  if (typeof value === 'boolean') return value
  if (typeof value === 'number') return Number.isFinite(value) ? value : undefined
  if (depth >= MAX_ITEM_DEPTH) return undefined
  if (Array.isArray(value)) {
    return value
      .slice(0, MAX_ITEM_ARRAY)
      .map((entry) => cleanPublicValue(entry, depth + 1))
      .filter((entry) => entry !== undefined)
  }
  if (!isPlainObject(value)) return undefined
  const result = {}
  for (const key of Object.keys(value).slice(0, MAX_ITEM_KEYS)) {
    if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(key) || sensitiveItemKey(key)) continue
    const cleaned = cleanPublicValue(value[key], depth + 1)
    if (cleaned !== undefined) result[key] = cleaned
  }
  return result
}

function sensitiveItemKey(key) {
  const snakeCase = String(key || '').replace(/([a-z0-9])([A-Z])/g, '$1_$2')
  return SENSITIVE_ITEM_KEY.test(snakeCase)
}

function normalizePublicItem(value, candidateId) {
  const item = cleanPublicValue(value)
  if (!isPlainObject(item)) return null
  const itemId = identifier(item.id)
  if (itemId && itemId !== candidateId) return null
  return { ...item, id: item.id === undefined ? candidateId : item.id }
}

function normalizedKeys(value, maxItems = 32) {
  return uniqueIdentifiers(Array.isArray(value) ? value : [value], maxItems)
}

function normalizedFeedSignals(raw) {
  const source = isPlainObject(raw?.feedSignals)
    ? raw.feedSignals
    : (isPlainObject(raw?.feed_signals) ? raw.feed_signals : {})
  const result = {}
  for (const key of FEED_SIGNAL_KEYS) {
    if (source[key] !== undefined) result[key] = boundedNumber(source[key], 0, 1)
  }
  return result
}

function normalizedInferenceSignals(value) {
  const root = isPlainObject(value?.sources) ? value.sources : value
  if (!isPlainObject(root)) return { sources: {} }
  const sources = {}
  for (const [sourceName, rawDimensions] of Object.entries(root)) {
    const source = identifier(sourceName, 64)
    if (!source || !INFERENCE_SOURCES.has(source) || !isPlainObject(rawDimensions)) continue
    const dimensions = {}
    for (const [dimensionName, rawValues] of Object.entries(rawDimensions)) {
      const dimension = identifier(dimensionName, 32)
      if (!dimension || !RANKING_DIMENSIONS.has(dimension) || !isPlainObject(rawValues)) continue
      const values = {}
      for (const [key, rawScore] of Object.entries(rawValues)) {
        const normalized = identifier(key)
        if (!normalized) continue
        values[normalized] = boundedNumber(rawScore, -1, 1)
      }
      if (Object.keys(values).length > 0) dimensions[dimension] = values
    }
    if (Object.keys(dimensions).length > 0) sources[source] = dimensions
  }
  return { sources }
}

function normalizeRawBatch(value) {
  if (!isPlainObject(value) || !Array.isArray(value.candidates)) {
    throw new AlgorithmCandidateProviderError('RECOMMENDATION_PROVIDER_CANDIDATES_INVALID')
  }
  const snapshotId = text(value.snapshotId)
  if (!snapshotId) throw new AlgorithmCandidateProviderError('RECOMMENDATION_PROVIDER_SNAPSHOT_INVALID')
  return {
    snapshotId,
    providerState: value.providerState === undefined ? null : value.providerState,
    inferredSignals: normalizedInferenceSignals(value.inferredSignals),
    candidates: value.candidates.slice(0, MAX_CANDIDATES),
  }
}

function normalizeBlockEdge(value) {
  if (!isPlainObject(value)) return null
  const blockerId = identifier(value.blockerId ?? value.blocker_id ?? value.fromId ?? value.from_id)
  const blockedId = identifier(value.blockedId ?? value.blocked_id ?? value.toId ?? value.to_id)
  return blockerId && blockedId ? { blockerId, blockedId } : null
}

function normalizeBlockEdges(value) {
  if (!Array.isArray(value)) {
    throw new AlgorithmCandidateProviderError('RECOMMENDATION_PROVIDER_BLOCKS_UNAVAILABLE')
  }
  return value.map(normalizeBlockEdge).filter(Boolean)
}

function hasBidirectionalBlock({ viewerAliases, creatorAliases, blockEdges }) {
  for (const edge of blockEdges) {
    const viewerBlockedCreator = viewerAliases.has(edge.blockerId) && creatorAliases.includes(edge.blockedId)
    const creatorBlockedViewer = creatorAliases.includes(edge.blockerId) && viewerAliases.has(edge.blockedId)
    if (viewerBlockedCreator || creatorBlockedViewer) return true
  }
  return false
}

function candidateClaimsBlock(raw) {
  const safety = isPlainObject(raw?.safety) ? raw.safety : {}
  return hasTrueFlag([raw, safety], [
    'viewerBlockedCreator',
    'viewer_blocked_creator',
    'creatorBlockedViewer',
    'creator_blocked_viewer',
    'isBlocked',
    'is_blocked',
  ])
}

function rankingCandidate(raw, { id, creatorId, item }) {
  const stringValue = (keys, maxLength = 180) => {
    for (const key of keys) {
      const value = identifier(raw[key], maxLength)
      if (value) return value
    }
    return null
  }
  const arrayValue = (keys) => normalizedKeys(keys.flatMap((key) => {
    const value = raw[key]
    return Array.isArray(value) ? value : [value]
  }))
  const safety = raw.safety || {}
  return {
    id,
    item,
    creatorId,
    contentKey: stringValue(['contentKey', 'content_key', 'contentId', 'content_id']) || id,
    topics: arrayValue(['topics', 'topic']),
    subtopics: arrayValue(['subtopics', 'subtopic']),
    formats: arrayValue(['formats', 'format']),
    audioKeys: arrayValue(['audioKeys', 'audio_keys', 'audio']),
    languages: arrayValue(['languages', 'language']),
    communityKeys: arrayValue(['communityKeys', 'community_keys', 'community']),
    relationshipKeys: arrayValue(['relationshipKeys', 'relationship_keys', 'relationship']),
    isFollowedCreator: raw.isFollowedCreator === true || raw.is_followed_creator === true,
    creatorAffinity: boundedNumber(raw.creatorAffinity ?? raw.creator_affinity, -1, 1),
    feedSignals: normalizedFeedSignals(raw),
    trendScore: boundedNumber(raw.trendScore ?? raw.trend_score, -1, 1),
    explorationScore: boundedNumber(raw.explorationScore ?? raw.exploration_score, -1, 1),
    safety: {
      isPublic: true,
      isEligible: true,
      ageEligible: safety.ageEligible !== false,
    },
  }
}

function preliminaryCandidate(raw, { surface, viewerAliases, now }) {
  if (!isPlainObject(raw) || normalizedSurface(raw.surface) !== surface) return null
  const id = identifier(raw.id)
  const creatorAliases = candidateCreatorAliases(raw)
  const verdict = safetyVerdict(raw, now)
  if (!id || !verdict.allowed || creatorAliases.length === 0) return null
  if (creatorAliases.some((alias) => viewerAliases.has(alias))) return null
  return { raw, id, creatorAliases, creatorId: creatorAliases[0] }
}

/**
 * Creates the `loadCandidates` function consumed by
 * `registerAlgorithmRecommendationRoutes`. It is intentionally not coupled to
 * `data-routes.js`; a future server integration must supply read-only,
 * server-authoritative selector functions and only then register the route.
 */
function createAlgorithmCandidateProvider({
  loadRawCandidates,
  loadBlockEdges,
  toPublicItem = async (raw) => raw.item,
} = {}) {
  if (typeof loadRawCandidates !== 'function') throw new TypeError('loadRawCandidates is required')
  if (typeof loadBlockEdges !== 'function') throw new TypeError('loadBlockEdges is required')
  if (typeof toPublicItem !== 'function') throw new TypeError('toPublicItem must be a function')

  return async function loadCandidates(input = {}) {
    const surface = normalizedSurface(input.surface)
    const viewerAliases = ownerAliases(input.owner)
    if (!surface || viewerAliases.size === 0) {
      throw new AlgorithmCandidateProviderError('RECOMMENDATION_PROVIDER_IDENTITY_INVALID')
    }
    const limit = Math.max(1, Math.min(Number(input.limit) || 20, 60))
    const maxCandidates = Math.max(limit, Math.min(Number(input.maxCandidates) || MAX_CANDIDATES, MAX_CANDIDATES))
    const now = Number.isFinite(Number(input.now)) ? Number(input.now) : Date.now()
    const batch = normalizeRawBatch(await loadRawCandidates({
      owner: input.owner,
      surface,
      cursor: input.cursor || null,
      limit,
      maxCandidates,
      now: input.now,
    }))
    const preliminaries = batch.candidates
      .map((raw) => preliminaryCandidate(raw, { surface, viewerAliases, now }))
      .filter(Boolean)
    const creatorIds = uniqueIdentifiers(preliminaries.flatMap((candidate) => candidate.creatorAliases), MAX_CANDIDATES)
    const blockEdges = creatorIds.length > 0
      ? normalizeBlockEdges(await loadBlockEdges({
        owner: input.owner,
        viewerIds: Array.from(viewerAliases),
        creatorIds,
        surface,
      }))
      : []

    const candidates = []
    const seen = new Set()
    for (const candidate of preliminaries) {
      if (seen.has(candidate.id) || candidateClaimsBlock(candidate.raw)) continue
      if (hasBidirectionalBlock({ viewerAliases, creatorAliases: candidate.creatorAliases, blockEdges })) continue
      const item = normalizePublicItem(await toPublicItem(candidate.raw, {
        owner: input.owner,
        surface,
      }), candidate.id)
      if (!item) continue
      seen.add(candidate.id)
      candidates.push(rankingCandidate(candidate.raw, {
        id: candidate.id,
        creatorId: candidate.creatorId,
        item,
      }))
    }

    return {
      snapshotId: batch.snapshotId,
      providerState: batch.providerState,
      inferredSignals: batch.inferredSignals,
      candidates,
    }
  }
}

module.exports = {
  AlgorithmCandidateProviderError,
  MAX_CANDIDATES,
  SUPPORTED_SURFACES,
  candidateCreatorAliases,
  cleanPublicValue,
  createAlgorithmCandidateProvider,
  hasBidirectionalBlock,
  normalizeBlockEdges,
  normalizePublicItem,
  normalizedInferenceSignals,
  safetyVerdict,
  sensitiveItemKey,
}
