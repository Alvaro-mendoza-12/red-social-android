'use strict'

// This is a deliberately isolated adapter for the future socialization-card
// route. It owns no SQL and no legacy feed/query code: callers inject a
// candidate source and an authoritative policy evaluator. The provider fails
// closed unless both layers meet the contract below.
//
// Candidate sources may supply only approved public presentation fields. They
// must never include direct-message content, GPS/location, age, gender, email,
// phone numbers, or other private profile fields. The policy evaluator must
// resolve visibility/account state/block edges from authoritative data at read
// time. The provider then checks every returned boolean itself before exposing
// a card-shaped candidate to `socialization-cards-routes`.

const SUPPORTED_SOCIALIZATION_CARD_TYPES = Object.freeze([
  'person',
  'community',
  'challenge',
  'live',
])

const CARD_SURFACES = new Set(['social', 'video', 'live'])
const RAW_CANDIDATE_KEYS = new Set([
  'type',
  'targetId',
  'subjectId',
  'title',
  'subtitle',
  'imageUrl',
  'expiresAt',
])
const SAFETY_DECISION_KEYS = new Set([
  'isPublic',
  'isDeleted',
  'isHidden',
  'isSuspended',
  'viewerBlockedCandidate',
  'candidateBlockedViewer',
])
const MAX_INPUT_LIMIT = 32
const MAX_SOURCE_CANDIDATES = 128

const SOCIALIZATION_CARD_CANDIDATE_CONTRACT = Object.freeze({
  publicFields: Object.freeze([
    'type',
    'targetId',
    'subjectId',
    'title',
    'subtitle',
    'imageUrl',
    'expiresAt',
  ]),
  requiredSafetyDecision: Object.freeze([
    'isPublic=true',
    'isDeleted=false',
    'isHidden=false',
    'isSuspended=false',
    'viewerBlockedCandidate=false',
    'candidateBlockedViewer=false',
  ]),
  forbiddenData: Object.freeze([
    'direct messages',
    'GPS or location',
    'age or date of birth',
    'gender or sex',
    'email, phone, or other private identifiers',
  ]),
})

function isPlainObject(value) {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function normalizedOpaqueId(value, maxLength = 160) {
  if (typeof value !== 'string') return null
  const text = value.trim()
  if (!text || text.length > maxLength) return null
  if (!/^[A-Za-z0-9][A-Za-z0-9:_-]*$/.test(text)) return null
  return text.toLowerCase()
}

function publicOpaqueId(value, maxLength = 160) {
  if (typeof value !== 'string') return null
  const text = value.trim()
  if (!text || text.length > maxLength) return null
  return /^[A-Za-z0-9][A-Za-z0-9:_-]*$/.test(text) ? text : null
}

function cleanPublicText(value, maxLength) {
  if (typeof value !== 'string') return null
  const text = value.replace(/[\u0000-\u001F\u007F]/g, ' ').trim()
  if (!text || text.length > maxLength) return null
  // Public labels are allowed, but e-mail addresses and phone-like values are
  // not a valid presentation value for a socialization card.
  if (/\b[^\s@]+@[^\s@]+\.[^\s@]+\b/.test(text)) return null
  if (/(?:\+?\d[\s().-]*){7,}\d/.test(text)) return null
  return text
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

function normalizeCursor(value) {
  if (value === undefined || value === null || value === '') return null
  if (typeof value !== 'string') return null
  const text = value.trim()
  if (!text || text.length > 512) return null
  return /^[A-Za-z0-9._~-]+$/.test(text) ? text : null
}

function normalizeSurface(value) {
  if (typeof value !== 'string') return null
  const surface = value.trim().toLowerCase()
  return CARD_SURFACES.has(surface) ? surface : null
}

function normalizePlacement(value) {
  if (typeof value !== 'string') return null
  const match = /^after_([0-9]{1,3})$/.exec(value.trim())
  if (!match) return null
  const offset = Number(match[1])
  return Number.isSafeInteger(offset) && offset <= 200 ? `after_${offset}` : null
}

function normalizeLimit(value) {
  const parsed = Number(value)
  return Number.isInteger(parsed) && parsed >= 1 && parsed <= MAX_INPUT_LIMIT ? parsed : null
}

function normalizeAllowedTypes(value) {
  if (!Array.isArray(value)) return []
  const allowed = new Set()
  for (const rawType of value) {
    if (typeof rawType !== 'string') continue
    const type = rawType.trim().toLowerCase()
    if (SUPPORTED_SOCIALIZATION_CARD_TYPES.includes(type)) allowed.add(type)
  }
  return SUPPORTED_SOCIALIZATION_CARD_TYPES.filter((type) => allowed.has(type))
}

function normalizeViewerIdentity(identity) {
  if (!isPlainObject(identity)) return null
  const displayUserId = normalizedOpaqueId(identity.displayUserId)
  const values = identity.ids instanceof Set
    ? Array.from(identity.ids)
    : Array.isArray(identity.ids)
      ? identity.ids
      : []
  const ids = new Set(values.map((value) => normalizedOpaqueId(value)).filter(Boolean))
  if (!displayUserId || ids.size === 0) return null
  ids.add(displayUserId)
  return { displayUserId, ids }
}

function hasExactKeys(value, keys) {
  return isPlainObject(value) && Object.keys(value).every((key) => keys.has(key))
}

function normalizePublicCandidate(candidate, { now = Date.now() } = {}) {
  if (!hasExactKeys(candidate, RAW_CANDIDATE_KEYS)) return null

  const type = typeof candidate.type === 'string' ? candidate.type.trim().toLowerCase() : ''
  if (!SUPPORTED_SOCIALIZATION_CARD_TYPES.includes(type)) return null

  const targetId = publicOpaqueId(candidate.targetId)
  const subjectId = publicOpaqueId(candidate.subjectId)
  const title = cleanPublicText(candidate.title, 96)
  if (!targetId || !subjectId || !title) return null
  // A person card can only point to the same public account evaluated by the
  // policy adapter. That makes self/block checks unambiguous.
  if (type === 'person' && normalizedOpaqueId(targetId) !== normalizedOpaqueId(subjectId)) return null

  const hasSubtitle = Object.prototype.hasOwnProperty.call(candidate, 'subtitle')
  const subtitle = hasSubtitle ? cleanPublicText(candidate.subtitle, 180) : null
  if (hasSubtitle && candidate.subtitle !== null && subtitle === null) return null

  const hasImage = Object.prototype.hasOwnProperty.call(candidate, 'imageUrl')
  const imageUrl = hasImage ? normalizeImageUrl(candidate.imageUrl) : null
  if (hasImage && candidate.imageUrl !== null && candidate.imageUrl !== '' && imageUrl === null) return null

  const hasExpiry = Object.prototype.hasOwnProperty.call(candidate, 'expiresAt')
  const expiresAt = hasExpiry ? normalizeExpiry(candidate.expiresAt, now) : null
  if (hasExpiry && candidate.expiresAt !== null && candidate.expiresAt !== '' && expiresAt === null) return null

  return {
    type,
    targetId,
    subjectId,
    title,
    ...(subtitle ? { subtitle } : {}),
    ...(imageUrl ? { imageUrl } : {}),
    ...(expiresAt ? { expiresAt } : {}),
  }
}

function cardPayloadFromCandidate(candidate) {
  if (!candidate) return null
  return {
    type: candidate.type,
    targetId: candidate.targetId,
    title: candidate.title,
    ...(candidate.subtitle ? { subtitle: candidate.subtitle } : {}),
    ...(candidate.imageUrl ? { imageUrl: candidate.imageUrl } : {}),
    ...(candidate.expiresAt ? { expiresAt: candidate.expiresAt } : {}),
  }
}

function normalizedSafetyDecision(value) {
  if (!hasExactKeys(value, SAFETY_DECISION_KEYS)) return null
  for (const key of SAFETY_DECISION_KEYS) {
    if (typeof value[key] !== 'boolean') return null
  }
  return {
    isPublic: value.isPublic,
    isDeleted: value.isDeleted,
    isHidden: value.isHidden,
    isSuspended: value.isSuspended,
    viewerBlockedCandidate: value.viewerBlockedCandidate,
    candidateBlockedViewer: value.candidateBlockedViewer,
  }
}

function safetyAllowsCard(value) {
  const decision = normalizedSafetyDecision(value)
  return Boolean(
    decision &&
    decision.isPublic === true &&
    decision.isDeleted === false &&
    decision.isHidden === false &&
    decision.isSuspended === false &&
    decision.viewerBlockedCandidate === false &&
    decision.candidateBlockedViewer === false,
  )
}

function isSelfCandidate(viewerIdentity, candidate) {
  const targetId = normalizedOpaqueId(candidate?.targetId)
  const subjectId = normalizedOpaqueId(candidate?.subjectId)
  return Boolean(
    viewerIdentity?.ids instanceof Set &&
    (viewerIdentity.ids.has(targetId) || viewerIdentity.ids.has(subjectId)),
  )
}

function normalizeSourceResult(value) {
  if (!isPlainObject(value)) return null
  const allowed = new Set(['items', 'nextCursor'])
  if (Object.keys(value).some((key) => !allowed.has(key))) return null
  if (!Array.isArray(value.items) || value.items.length > MAX_SOURCE_CANDIDATES) return null
  const hasCursor = Object.prototype.hasOwnProperty.call(value, 'nextCursor')
  const nextCursor = hasCursor ? normalizeCursor(value.nextCursor) : null
  if (hasCursor && value.nextCursor !== null && value.nextCursor !== '' && nextCursor === null) return null
  return { items: value.items, nextCursor }
}

function safeRequest(input) {
  if (!isPlainObject(input)) return null
  const identity = normalizeViewerIdentity(input.identity)
  const surface = normalizeSurface(input.surface)
  const placement = normalizePlacement(input.placement)
  const limit = normalizeLimit(input.limit)
  const cursor = input.cursor === undefined || input.cursor === null || input.cursor === ''
    ? null
    : normalizeCursor(input.cursor)
  const allowedTypes = normalizeAllowedTypes(input.allowedTypes)
  if (!identity || !surface || !placement || limit === null || (input.cursor && !cursor)) return null
  return { identity, surface, placement, limit, cursor, allowedTypes }
}

/**
 * Creates the loader passed later to `registerSocializationCardRoutes`.
 *
 * `loadCandidates` receives only opaque route context and must return:
 *   { items: [public candidate], nextCursor?: opaque-string }
 *
 * `evaluateCandidateSafety` receives only canonical opaque IDs and must return
 * every boolean in SAFETY_DECISION_KEYS from authoritative data. Missing or
 * malformed values are denied. Neither callback receives nor should query
 * direct messages, location, age, gender, or private profile fields.
 */
function createSocializationCardCandidateProvider({
  loadCandidates,
  evaluateCandidateSafety,
  now = () => Date.now(),
} = {}) {
  if (typeof loadCandidates !== 'function') throw new TypeError('loadCandidates is required')
  if (typeof evaluateCandidateSafety !== 'function') throw new TypeError('evaluateCandidateSafety is required')
  if (typeof now !== 'function') throw new TypeError('now must be a function')

  return async function loadSafeSocializationCardCandidates(input) {
    const request = safeRequest(input)
    if (!request || request.allowedTypes.length === 0) return { items: [], nextCursor: null }

    let source
    try {
      source = normalizeSourceResult(await loadCandidates({
        identity: request.identity,
        surface: request.surface,
        placement: request.placement,
        limit: request.limit,
        cursor: request.cursor,
        allowedTypes: request.allowedTypes,
      }))
    } catch {
      return { items: [], nextCursor: null }
    }
    if (!source) return { items: [], nextCursor: null }

    const items = []
    const seen = new Set()
    const timestamp = now()
    for (const rawCandidate of source.items) {
      const candidate = normalizePublicCandidate(rawCandidate, { now: timestamp })
      if (!candidate || !request.allowedTypes.includes(candidate.type) || isSelfCandidate(request.identity, candidate)) continue

      let decision
      try {
        decision = await evaluateCandidateSafety({
          viewerIdentity: request.identity,
          candidate: {
            type: candidate.type,
            targetId: candidate.targetId,
            subjectId: candidate.subjectId,
          },
        })
      } catch {
        continue
      }
      if (!safetyAllowsCard(decision)) continue

      const id = `${candidate.type}:${normalizedOpaqueId(candidate.targetId)}`
      if (seen.has(id)) continue
      seen.add(id)
      items.push(cardPayloadFromCandidate(candidate))
      if (items.length >= request.limit) break
    }

    return { items, nextCursor: source.nextCursor }
  }
}

module.exports = {
  CARD_SURFACES,
  MAX_INPUT_LIMIT,
  MAX_SOURCE_CANDIDATES,
  RAW_CANDIDATE_KEYS,
  SAFETY_DECISION_KEYS,
  SOCIALIZATION_CARD_CANDIDATE_CONTRACT,
  SUPPORTED_SOCIALIZATION_CARD_TYPES,
  cardPayloadFromCandidate,
  cleanPublicText,
  createSocializationCardCandidateProvider,
  isSelfCandidate,
  normalizeAllowedTypes,
  normalizeCursor,
  normalizePublicCandidate,
  normalizeSourceResult,
  normalizeViewerIdentity,
  normalizedSafetyDecision,
  safetyAllowsCard,
  safeRequest,
}
