'use strict'

const { createHash } = require('node:crypto')

/**
 * Pure, server-side ranking kernel for the future Novvin algorithm rollout.
 *
 * This module deliberately does not query a database, inspect a request, or
 * call an external service. Callers must provide already-authorized candidate
 * metadata and only the permitted inferred signals. Keeping the kernel pure
 * makes its precedence rules testable and prevents a client from becoming the
 * source of truth for a recommendation decision.
 *
 * Privacy contract:
 * - private messages, precise location, raw age and gender are never read;
 * - the only inferred sources accepted are the explicit allow-list below;
 * - age eligibility is a boolean safety verdict produced elsewhere, never a
 *   raw age used for ranking.
 */

const RANKING_VERSION = 'novvin-authoritative-ranking-v3'

const PREFERENCE_DIMENSIONS = Object.freeze([
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

const PREFERENCE_DIMENSION_SET = new Set(PREFERENCE_DIMENSIONS)

// These values describe the whole surface rather than a meaningful content
// category.  Treating them as a hard negative would make a preference such as
// "video" match every video (the legacy candidate source includes `video` as
// a compatibility format), which can empty the entire feed.  A negative
// preference must always identify a specific, comparable value.
const NON_SPECIFIC_PREFERENCE_KEYS = Object.freeze({
  topic: new Set(['all', 'any', 'general', 'unknown', 'misc', 'video-general', 'social-general']),
  format: new Set(['all', 'any', 'general', 'unknown', 'misc', 'video', 'content']),
})

const PERMITTED_INFERENCE_SOURCES = Object.freeze([
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

const PERMITTED_INFERENCE_SOURCE_SET = new Set(PERMITTED_INFERENCE_SOURCES)

const FORBIDDEN_RANKING_INPUTS = Object.freeze([
  'private_messages',
  'direct_messages',
  'messages',
  'precise_location',
  'exact_location',
  'location',
  'age',
  'gender',
])

const FEED_MIX_KEYS = Object.freeze([
  'following',
  'interest',
  'community',
  'new_content',
  'trend',
  'exploration',
])

const DEFAULT_FEED_MIX = Object.freeze({
  following: 35,
  interest: 35,
  community: 10,
  new_content: 10,
  trend: 5,
  exploration: 5,
})

const DEFAULT_DIVERSITY_LIMITS = Object.freeze({
  maxPerCreator: 2,
  maxPerSubtopic: 2,
  maxPerDuplicate: 1,
})

const UNSAFE_STATUSES = new Set([
  'removed',
  'suspended',
  'blocked',
  'private',
  'restricted',
  'age_restricted',
])

function isPlainObject(value) {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function normalizedKey(value, maxLength = 160) {
  const text = String(value == null ? '' : value).trim().toLowerCase()
  if (!text || text.length > maxLength) return null
  return text
}

function boundedNumber(value, min, max, fallback = 0) {
  const number = Number(value)
  if (!Number.isFinite(number)) return fallback
  return Math.max(min, Math.min(max, number))
}

function boundedInteger(value, min, max, fallback) {
  const number = Number(value)
  if (!Number.isInteger(number)) return fallback
  return Math.max(min, Math.min(max, number))
}

function toArray(value) {
  if (Array.isArray(value)) return value
  if (value == null) return []
  return [value]
}

function normalizedValues(value, maxItems = 32) {
  const values = []
  const seen = new Set()
  for (const item of toArray(value)) {
    const key = normalizedKey(item)
    if (key && !seen.has(key)) {
      seen.add(key)
      values.push(key)
    }
    if (values.length >= maxItems) break
  }
  return values
}

function dateMs(value) {
  if (!value) return null
  const result = new Date(value).getTime()
  return Number.isFinite(result) ? result : null
}

function normaliseSourceName(value) {
  const source = normalizedKey(value, 64)
  if (!source) return null
  const aliases = {
    watchtime: 'watch_time',
    watch_time: 'watch_time',
    scrollvelocity: 'scroll_velocity',
    scroll_velocity: 'scroll_velocity',
    profilevisits: 'profile_visits',
    profile_visits: 'profile_visits',
    publicinteractions: 'public_interactions',
    public_interactions: 'public_interactions',
    communityactivity: 'community_activity',
    community_activity: 'community_activity',
    liveactivity: 'live_activity',
    live_activity: 'live_activity',
  }
  return aliases[source] || source
}

function permissionEnabled(dataPermissions, source) {
  if (!isPlainObject(dataPermissions)) return false
  const compact = source.replace(/_/g, '')
  const camel = source.replace(/_([a-z])/g, (_match, letter) => letter.toUpperCase())
  return dataPermissions[source] === true
    || dataPermissions[`use_${source}`] === true
    || dataPermissions[compact] === true
    || dataPermissions[`use_${compact}`] === true
    || dataPermissions[camel] === true
    || dataPermissions[`use_${camel}`] === true
}

function candidateValues(candidate, dimension) {
  switch (dimension) {
    case 'content':
      return normalizedValues([
        candidate.contentKey,
        candidate.content_key,
        candidate.contentId,
        candidate.content_id,
        candidate.id,
      ])
    case 'topic':
      return normalizedValues(candidate.topics || candidate.topic)
    case 'subtopic':
      return normalizedValues(candidate.subtopics || candidate.subtopic)
    case 'creator':
      return normalizedValues([
        candidate.creatorKey,
        candidate.creator_key,
        candidate.creatorId,
        candidate.creator_id,
      ])
    case 'format':
      return normalizedValues(candidate.formats || candidate.format)
    case 'audio':
      return normalizedValues(candidate.audioKeys || candidate.audio_keys || candidate.audio)
    case 'language':
      return normalizedValues(candidate.languages || candidate.language)
    case 'community':
      return normalizedValues(candidate.communityKeys || candidate.community_keys || candidate.community)
    case 'relationship':
      return normalizedValues(candidate.relationshipKeys || candidate.relationship_keys || candidate.relationship)
    default:
      return []
  }
}

function primaryValue(candidate, dimension) {
  return candidateValues(candidate, dimension)[0] || null
}

function normalizePreference(preference, index, nowMs, surface) {
  if (!isPlainObject(preference)) return null
  const dimension = normalizedKey(preference.dimension, 32)
  const subjectKey = normalizedKey(
    preference.subjectKey ?? preference.subject_key ?? preference.key,
    160,
  )
  const preferenceSurface = normalizedKey(preference.surface || 'global', 32)
  const score = Number(preference.score)
  if (!PREFERENCE_DIMENSION_SET.has(dimension)
    || !subjectKey
    || !Number.isInteger(score)
    || score < -100
    || score > 100
    || (preferenceSurface !== 'global' && preferenceSurface !== surface)) {
    return null
  }
  const startsAtMs = dateMs(preference.startsAt ?? preference.starts_at)
  const expiresAtMs = dateMs(preference.expiresAt ?? preference.expires_at)
  if ((startsAtMs && startsAtMs > nowMs) || (expiresAtMs && expiresAtMs <= nowMs)) return null
  return {
    dimension,
    subjectKey,
    score,
    surface: preferenceSurface,
    index,
  }
}

function isSpecificPreference(preference) {
  const blockedKeys = NON_SPECIFIC_PREFERENCE_KEYS[preference?.dimension]
  return !blockedKeys || !blockedKeys.has(preference.subjectKey)
}

function normalizePreferences(preferences, nowMs, surface) {
  // The server stores one value per (surface, dimension, key). Keeping the
  // last supplied value makes this pure function deterministic even during a
  // migration or an eventually-consistent cache refresh.
  const unique = new Map()
  for (const [index, preference] of toArray(preferences).entries()) {
    const normalized = normalizePreference(preference, index, nowMs, surface)
    if (!normalized) continue
    unique.set(`${normalized.surface}:${normalized.dimension}:${normalized.subjectKey}`, normalized)
  }
  return [...unique.values()]
}

function explicitMatches(candidate, preferences) {
  const matches = []
  for (const preference of preferences) {
    // Keep the preference in the user's configuration for migration/UI
    // visibility, but never let a generic legacy key become a global block.
    if (!isSpecificPreference(preference)) continue
    if (candidateValues(candidate, preference.dimension).includes(preference.subjectKey)) {
      matches.push(preference)
    }
  }
  const hardBlock = matches.find((match) => match.score === -100)
  return {
    hardBlock: hardBlock || null,
    matches,
    score: matches.reduce((sum, match) => sum + match.score, 0),
  }
}

function evaluateSafety(candidate) {
  const safety = isPlainObject(candidate.safety) ? candidate.safety : {}
  const status = normalizedKey(
    safety.status
      ?? candidate.moderationStatus
      ?? candidate.moderation_status
      ?? candidate.visibility,
    64,
  )
  if (candidate.isPublic === false || safety.isPublic === false || status === 'hidden') {
    return { allowed: false, reason: 'SAFETY_NOT_PUBLIC' }
  }
  if (candidate.viewerBlockedCreator === true
    || candidate.creatorBlockedViewer === true
    || safety.viewerBlockedCreator === true
    || safety.creatorBlockedViewer === true) {
    return { allowed: false, reason: 'SAFETY_USER_BLOCK' }
  }
  if (candidate.ageEligible === false || safety.ageEligible === false) {
    return { allowed: false, reason: 'SAFETY_AGE_POLICY' }
  }
  if (candidate.isEligible === false || safety.isEligible === false || (status && UNSAFE_STATUSES.has(status))) {
    return { allowed: false, reason: 'SAFETY_MODERATION_OR_POLICY' }
  }
  return { allowed: true, reason: 'SAFETY_PASSED' }
}

function normalizeFeedMix(value) {
  const source = isPlainObject(value) ? value : {}
  const aliases = {
    following: ['following', 'following_weight', 'followingWeight'],
    interest: ['interest', 'interests', 'interest_weight', 'interestWeight'],
    community: ['community', 'communities', 'community_weight', 'communityWeight'],
    new_content: ['new_content', 'newContent', 'new_content_weight', 'newContentWeight'],
    trend: ['trend', 'trends', 'trend_weight', 'trendWeight'],
    exploration: ['exploration', 'exploration_weight', 'explorationWeight'],
  }
  const raw = {}
  let total = 0
  for (const key of FEED_MIX_KEYS) {
    const supplied = aliases[key]
      .map((alias) => source[alias])
      .find((candidate) => candidate !== undefined)
    raw[key] = boundedNumber(supplied, 0, 100, DEFAULT_FEED_MIX[key])
    total += raw[key]
  }
  if (total <= 0) return { ...DEFAULT_FEED_MIX }
  return Object.fromEntries(FEED_MIX_KEYS.map((key) => [key, (raw[key] / total) * 100]))
}

function candidateFeedSignal(candidate, key) {
  const signals = isPlainObject(candidate.feedSignals)
    ? candidate.feedSignals
    : (isPlainObject(candidate.feed_signals) ? candidate.feed_signals : {})
  const aliases = {
    following: ['following', 'isFollowedCreator', 'is_followed_creator'],
    interest: ['interest', 'interestMatch', 'interest_match'],
    community: ['community', 'communityMatch', 'community_match'],
    new_content: ['new_content', 'newContent', 'freshness'],
    trend: ['trend', 'trendScore', 'trend_score'],
    exploration: ['exploration', 'explorationScore', 'exploration_score'],
  }
  for (const alias of aliases[key] || [key]) {
    if (signals[alias] !== undefined) return boundedNumber(signals[alias], 0, 1, 0)
  }
  if (key === 'following' && (candidate.isFollowedCreator === true || candidate.is_followed_creator === true)) return 1
  return 0
}

function feedMixScore(candidate, feedMix) {
  return FEED_MIX_KEYS.reduce(
    (sum, key) => sum + feedMix[key] * candidateFeedSignal(candidate, key),
    0,
  )
}

function normalizeInferredSignals(inferredSignals, dataPermissions) {
  const sourceInput = isPlainObject(inferredSignals) && isPlainObject(inferredSignals.sources)
    ? inferredSignals.sources
    : (isPlainObject(inferredSignals) ? inferredSignals : {})
  const permitted = []
  const ignoredSources = []
  for (const [rawSource, rawSignals] of Object.entries(sourceInput)) {
    const source = normaliseSourceName(rawSource)
    if (!source || !PERMITTED_INFERENCE_SOURCE_SET.has(source)) {
      ignoredSources.push(String(rawSource))
      continue
    }
    if (!permissionEnabled(dataPermissions, source) || !isPlainObject(rawSignals)) continue
    const dimensions = {}
    for (const [rawDimension, rawValues] of Object.entries(rawSignals)) {
      const dimension = normalizedKey(rawDimension, 32)
      if (!PREFERENCE_DIMENSION_SET.has(dimension) || !isPlainObject(rawValues)) continue
      const values = {}
      for (const [rawKey, rawValue] of Object.entries(rawValues)) {
        const key = normalizedKey(rawKey)
        if (!key) continue
        values[key] = boundedNumber(rawValue, -1, 1, 0)
      }
      if (Object.keys(values).length > 0) dimensions[dimension] = values
    }
    if (Object.keys(dimensions).length > 0) permitted.push({ source, dimensions })
  }
  return { permitted, ignoredSources }
}

function inferredScore(candidate, permittedSources) {
  const matches = []
  for (const source of permittedSources) {
    for (const [dimension, values] of Object.entries(source.dimensions)) {
      for (const value of candidateValues(candidate, dimension)) {
        if (values[value] === undefined) continue
        const contribution = values[value] * 100
        matches.push({
          source: source.source,
          dimension,
          subjectKey: value,
          contribution,
        })
      }
    }
  }
  return {
    score: matches.reduce((sum, match) => sum + match.contribution, 0),
    matches,
  }
}

function creatorScore(candidate) {
  const followed = candidate.isFollowedCreator === true || candidate.is_followed_creator === true
  const affinity = boundedNumber(candidate.creatorAffinity ?? candidate.creator_affinity, -1, 1, 0)
  return {
    followed,
    affinity,
    score: (followed ? 100 : 0) + (affinity * 25),
  }
}

function trendScore(candidate, feedMix) {
  return boundedNumber(candidate.trendScore ?? candidate.trend_score, -1, 1, 0)
    * boundedNumber(feedMix?.trend, 0, 100, 0)
}

function explorationScore(candidate, feedMix) {
  return boundedNumber(candidate.explorationScore ?? candidate.exploration_score, -1, 1, 0)
    * boundedNumber(feedMix?.exploration, 0, 100, 0)
}

function seededUnitInterval(seed, candidateId) {
  const value = `${String(seed || '')}:${String(candidateId || '')}`
  if (!String(seed || '').trim() || !String(candidateId || '').trim()) return 0
  return createHash('sha256').update(value).digest().readUInt32BE(0) / 0xFFFFFFFF
}

function sessionExplorationScore(candidate, feedMix, sessionSeed) {
  const weight = boundedNumber(feedMix?.exploration, 0, 100, 0)
  if (!weight || !String(sessionSeed || '').trim()) return 0
  const candidateId = candidate?.id ?? candidate?.contentId ?? candidate?.content_id
  return seededUnitInterval(sessionSeed, candidateId) * weight
}

function normalizeDiversityLimits(value) {
  const limits = isPlainObject(value) ? value : {}
  return {
    maxPerCreator: boundedInteger(limits.maxPerCreator ?? limits.max_per_creator, 1, 20, DEFAULT_DIVERSITY_LIMITS.maxPerCreator),
    maxPerSubtopic: boundedInteger(limits.maxPerSubtopic ?? limits.max_per_subtopic, 1, 20, DEFAULT_DIVERSITY_LIMITS.maxPerSubtopic),
    maxPerDuplicate: boundedInteger(limits.maxPerDuplicate ?? limits.max_per_duplicate, 1, 20, DEFAULT_DIVERSITY_LIMITS.maxPerDuplicate),
  }
}

function createDiversityCounts() {
  return {
    creators: new Map(),
    subtopics: new Map(),
    duplicates: new Map(),
  }
}

function countOf(map, key) {
  return key ? (map.get(key) || 0) : 0
}

function diversityKeys(candidate) {
  const source = candidate && candidate.candidate ? candidate.candidate : candidate
  return {
    creator: primaryValue(source, 'creator'),
    subtopic: primaryValue(source, 'subtopic'),
    duplicate: normalizedKey(source.duplicateKey ?? source.duplicate_key ?? source.contentKey ?? source.content_key ?? source.id),
  }
}

function previewDiversity(candidate, counts, limits) {
  const keys = diversityKeys(candidate)
  const duplicateCount = countOf(counts.duplicates, keys.duplicate)
  const creatorCount = countOf(counts.creators, keys.creator)
  const subtopicCount = countOf(counts.subtopics, keys.subtopic)
  const duplicateAtLimit = Boolean(keys.duplicate) && duplicateCount >= limits.maxPerDuplicate
  const creatorAtLimit = Boolean(keys.creator) && creatorCount >= limits.maxPerCreator
  const subtopicAtLimit = Boolean(keys.subtopic) && subtopicCount >= limits.maxPerSubtopic
  const explicitlyPreferred = candidate.explicit.score > 0

  if (duplicateAtLimit) {
    return {
      allowed: false,
      reason: 'DIVERSITY_DUPLICATE_LIMIT',
      keys,
      creatorAtLimit,
      subtopicAtLimit,
      duplicateAtLimit,
      overriddenByExplicitPreference: false,
      priority: -2,
    }
  }

  if ((creatorAtLimit || subtopicAtLimit) && !explicitlyPreferred) {
    return {
      allowed: false,
      reason: creatorAtLimit ? 'DIVERSITY_CREATOR_LIMIT' : 'DIVERSITY_SUBTOPIC_LIMIT',
      keys,
      creatorAtLimit,
      subtopicAtLimit,
      duplicateAtLimit,
      overriddenByExplicitPreference: false,
      priority: -1,
    }
  }

  return {
    allowed: true,
    reason: explicitlyPreferred && (creatorAtLimit || subtopicAtLimit)
      ? 'DIVERSITY_EXPLICIT_PREFERENCE_OVERRIDE'
      : 'DIVERSITY_PASSED',
    keys,
    creatorAtLimit,
    subtopicAtLimit,
    duplicateAtLimit,
    overriddenByExplicitPreference: explicitlyPreferred && (creatorAtLimit || subtopicAtLimit),
    priority: (creatorAtLimit || subtopicAtLimit) ? 0 : 1,
  }
}

function increment(map, key) {
  if (!key) return
  map.set(key, (map.get(key) || 0) + 1)
}

function recordDiversity(counts, diversity) {
  increment(counts.creators, diversity.keys.creator)
  increment(counts.subtopics, diversity.keys.subtopic)
  increment(counts.duplicates, diversity.keys.duplicate)
}

function compareNumberDescending(left, right) {
  if (left === right) return 0
  return left > right ? -1 : 1
}

function compareCandidates(left, right) {
  const values = [
    [left.explicit.score, right.explicit.score],
    [left.creator.score, right.creator.score],
    // Exploration must contribute to the mix. As a tie breaker it never ran
    // when freshness differed slightly, giving new accounts the same feed.
    // Its configured weight bounds the variation; direct preferences and
    // creator choices above still take priority, and zero disables it.
    [left.feedMix + left.sessionExploration, right.feedMix + right.sessionExploration],
    [left.inferred.score, right.inferred.score],
    [left.diversity.priority, right.diversity.priority],
    [left.sessionExploration, right.sessionExploration],
    [left.trend, right.trend],
    [left.exploration, right.exploration],
  ]
  for (const [leftValue, rightValue] of values) {
    const comparison = compareNumberDescending(leftValue, rightValue)
    if (comparison !== 0) return comparison
  }
  return left.index - right.index || left.candidateId.localeCompare(right.candidateId)
}

function contributionSummary(prepared) {
  return {
    explicit: prepared.explicit.score,
    followedCreator: prepared.creator.score,
    feedMix: prepared.feedMix,
    permittedInference: prepared.inferred.score,
    diversity: prepared.diversity.priority,
    trends: prepared.trend,
    exploration: prepared.exploration + prepared.sessionExploration,
  }
}

function publicPreferenceMatch(match) {
  return {
    dimension: match.dimension,
    subjectKey: match.subjectKey,
    score: match.score,
    surface: match.surface,
  }
}

function buildExplanation(prepared, outcome, extra = {}) {
  const stages = [
    { stage: 'safety', outcome: 'passed', reasonCode: 'SAFETY_PASSED' },
    { stage: 'explicit_block', outcome: 'passed', reasonCode: 'NO_EXPLICIT_BLOCK' },
    {
      stage: 'explicit_preferences',
      outcome: prepared.explicit.matches.length ? 'matched' : 'neutral',
      contribution: prepared.explicit.score,
      matches: prepared.explicit.matches.map(publicPreferenceMatch),
    },
    {
      stage: 'followed_creator',
      outcome: prepared.creator.followed ? 'matched' : 'neutral',
      contribution: prepared.creator.score,
    },
    { stage: 'feed_mix', outcome: 'applied', contribution: prepared.feedMix },
    {
      stage: 'permitted_inference',
      outcome: prepared.inferred.matches.length ? 'matched' : 'neutral',
      contribution: prepared.inferred.score,
      signals: prepared.inferred.matches,
    },
    {
      stage: 'diversity',
      outcome: prepared.diversity.overriddenByExplicitPreference ? 'overridden_by_explicit_preference' : 'applied',
      reasonCode: prepared.diversity.reason,
    },
    { stage: 'trends', outcome: prepared.trend ? 'matched' : 'neutral', contribution: prepared.trend },
    { stage: 'exploration', outcome: (prepared.exploration || prepared.sessionExploration) ? 'matched' : 'neutral', contribution: prepared.exploration + prepared.sessionExploration },
  ]

  return {
    version: RANKING_VERSION,
    outcome,
    truthful: true,
    stages,
    privacy: {
      neverUsed: FORBIDDEN_RANKING_INPUTS,
      permittedInferenceSources: [...new Set(prepared.permittedInferenceSources)],
      ignoredInferenceSources: prepared.ignoredInferenceSources,
    },
    ...extra,
  }
}

function buildExclusion(candidateId, index, stage, reasonCode, extra = {}) {
  return {
    candidateId,
    sourceIndex: index,
    stage,
    reasonCode,
    explanation: {
      version: RANKING_VERSION,
      outcome: 'excluded',
      truthful: true,
      stages: [{ stage, outcome: 'excluded', reasonCode }],
      privacy: {
        neverUsed: FORBIDDEN_RANKING_INPUTS,
        permittedInferenceSources: [],
        ignoredInferenceSources: extra.ignoredInferenceSources || [],
      },
    },
  }
}

function prepareCandidate(candidate, index, context) {
  if (!isPlainObject(candidate)) return buildExclusion(`invalid-${index}`, index, 'input', 'CANDIDATE_INVALID')
  const candidateId = normalizedKey(candidate.id ?? candidate.contentId ?? candidate.content_id)
  if (!candidateId) return buildExclusion(`invalid-${index}`, index, 'input', 'CANDIDATE_ID_INVALID')

  const safety = evaluateSafety(candidate)
  if (!safety.allowed) return buildExclusion(candidateId, index, 'safety', safety.reason, {
    ignoredInferenceSources: context.ignoredInferenceSources,
  })

  const explicit = explicitMatches(candidate, context.preferences)
  if (explicit.hardBlock) {
    return buildExclusion(candidateId, index, 'explicit_block', 'EXPLICIT_PREFERENCE_BLOCKED', {
      ignoredInferenceSources: context.ignoredInferenceSources,
    })
  }

  return {
    candidate,
    candidateId,
    index,
    explicit,
    creator: creatorScore(candidate),
    feedMix: feedMixScore(candidate, context.feedMix),
    inferred: inferredScore(candidate, context.permittedInferredSources),
    trend: trendScore(candidate, context.feedMix),
    exploration: explorationScore(candidate, context.feedMix),
    sessionExploration: sessionExplorationScore(candidate, context.feedMix, context.sessionSeed),
    permittedInferenceSources: context.permittedInferredSources.map((item) => item.source),
    ignoredInferenceSources: context.ignoredInferenceSources,
    diversity: null,
  }
}

/**
 * Rank candidate ids with lexicographic precedence. It does not mutate the
 * candidates or read from an external store. All personal-data filtering and
 * content safety decisions must happen before this function receives input.
 *
 * @param {object} input
 * @param {Array<object>} input.candidates public/safe candidate metadata
 * @param {Array<object>} input.preferences explicit, server-owned preferences
 * @param {object} input.feedMix explicit composition choice for this surface
 * @param {object} input.inferredSignals only permitted source maps
 * @param {object} input.dataPermissions explicit per-source opt-ins
 * @param {object} input.diversityLimits per-creator/subtopic/dedupe limits
 * @returns {{version: string, ranked: Array<object>, excluded: Array<object>, diagnostics: object}}
 */
function rankAlgorithmCandidates(input = {}) {
  const safeInput = isPlainObject(input) ? input : {}
  const nowMs = Number.isFinite(Number(safeInput.now)) ? Number(safeInput.now) : Date.now()
  const surface = normalizedKey(safeInput.surface || 'global', 32) || 'global'
  const limit = boundedInteger(safeInput.limit, 1, 200, 20)
  const preferences = normalizePreferences(safeInput.preferences, nowMs, surface)
  const feedMix = normalizeFeedMix(safeInput.feedMix)
  const inference = normalizeInferredSignals(safeInput.inferredSignals, safeInput.dataPermissions)
  const context = {
    preferences,
    feedMix,
    permittedInferredSources: inference.permitted,
    ignoredInferenceSources: inference.ignoredSources,
    sessionSeed: String(safeInput.sessionSeed || '').trim(),
  }
  const excluded = []
  const remaining = []

  for (const [index, candidate] of toArray(safeInput.candidates).entries()) {
    const prepared = prepareCandidate(candidate, index, context)
    if (prepared && prepared.reasonCode) excluded.push(prepared)
    else remaining.push(prepared)
  }

  const diversityCounts = createDiversityCounts()
  // A continued feed spreads creators/topics across bounded windows. Diversity
  // must not remove otherwise eligible content from all subsequent pages.
  // Existing isolated callers retain strict exclusion unless they opt in.
  const spreadDiversity = safeInput.diversityMode === 'spread'
  const diversityWindowSize = boundedInteger(safeInput.diversityWindowSize, 1, 20, 20)
  const diversityLimits = normalizeDiversityLimits(safeInput.diversityLimits)
  const ranked = []

  while (remaining.length > 0 && ranked.length < limit) {
    if (spreadDiversity && ranked.length > 0 && ranked.length % diversityWindowSize === 0) {
      diversityCounts.creators.clear()
      diversityCounts.subtopics.clear()
      // Exact duplicates remain excluded across the entire cursor snapshot.
    }
    for (const candidate of remaining) {
      candidate.diversity = previewDiversity(candidate, diversityCounts, diversityLimits)
    }
    remaining.sort(spreadDiversity
      ? (left, right) => Number(right.diversity.allowed) - Number(left.diversity.allowed) || compareCandidates(left, right)
      : compareCandidates)
    const selected = remaining.shift()
    if (spreadDiversity && !selected.diversity.allowed && !selected.diversity.duplicateAtLimit) {
      // When every remaining creator/topic reached the spread target, continue
      // with a fresh window rather than report that the catalog is exhausted.
      diversityCounts.creators.clear()
      diversityCounts.subtopics.clear()
      selected.diversity = previewDiversity(selected, diversityCounts, diversityLimits)
    }
    if (!selected.diversity.allowed) {
      excluded.push(buildExclusion(
        selected.candidateId,
        selected.index,
        'diversity',
        selected.diversity.reason,
        { ignoredInferenceSources: selected.ignoredInferenceSources },
      ))
      continue
    }
    recordDiversity(diversityCounts, selected.diversity)
    ranked.push({
      candidateId: selected.candidateId,
      sourceIndex: selected.index,
      score: contributionSummary(selected),
      explanation: buildExplanation(selected, 'selected'),
    })
  }

  return {
    version: RANKING_VERSION,
    ranked,
    excluded,
    diagnostics: {
      surface,
      limit,
      feedMix,
      appliedPreferenceCount: preferences.length,
      permittedInferenceSources: inference.permitted.map((item) => item.source),
      ignoredInferenceSources: inference.ignoredSources,
      diversityLimits,
      neverUsed: FORBIDDEN_RANKING_INPUTS,
    },
  }
}

module.exports = {
  DEFAULT_DIVERSITY_LIMITS,
  DEFAULT_FEED_MIX,
  FEED_MIX_KEYS,
  FORBIDDEN_RANKING_INPUTS,
  PERMITTED_INFERENCE_SOURCES,
  PREFERENCE_DIMENSIONS,
  RANKING_VERSION,
  seededUnitInterval,
  rankAlgorithmCandidates,
  // Named alias makes the intended integration point obvious in route code.
  rankCandidates: rankAlgorithmCandidates,
}
