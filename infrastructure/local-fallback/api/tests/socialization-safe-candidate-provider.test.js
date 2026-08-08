const assert = require('assert/strict')
const fs = require('fs')
const path = require('path')
const {
  SOCIALIZATION_CARD_CANDIDATE_CONTRACT,
  createSocializationCardCandidateProvider,
  normalizePublicCandidate,
  safetyAllowsCard,
} = require('../src/socialization-safe-candidate-provider')
const { registerSocializationCardRoutes } = require('../src/socialization-cards-routes')

const NOW = Date.parse('2026-08-07T12:00:00.000Z')

function viewerIdentity() {
  return {
    displayUserId: 'viewer-1',
    ids: new Set(['viewer-1', 'legacy-viewer-1']),
  }
}

function request(overrides = {}) {
  return {
    identity: viewerIdentity(),
    surface: 'social',
    placement: 'after_6',
    limit: 8,
    cursor: 'cursor_1',
    allowedTypes: ['person', 'community', 'challenge', 'live'],
    ...overrides,
  }
}

function candidate(type, targetId, subjectId = `owner-${targetId}`) {
  return {
    type,
    targetId,
    subjectId: type === 'person' ? targetId : subjectId,
    title: `Etiqueta pública ${targetId}`,
    subtitle: 'Solo información pública aprobada',
    imageUrl: 'https://cdn.example.test/card.png',
    expiresAt: '2026-08-07T13:00:00.000Z',
  }
}

function allows() {
  return {
    isPublic: true,
    isDeleted: false,
    isHidden: false,
    isSuspended: false,
    viewerBlockedCandidate: false,
    candidateBlockedViewer: false,
  }
}

function testCandidateContractRejectsSensitiveInput() {
  const valid = normalizePublicCandidate(candidate('person', 'profile-42'), { now: NOW })
  assert.deepEqual(valid, {
    type: 'person',
    targetId: 'profile-42',
    subjectId: 'profile-42',
    title: 'Etiqueta pública profile-42',
    subtitle: 'Solo información pública aprobada',
    imageUrl: 'https://cdn.example.test/card.png',
    expiresAt: '2026-08-07T13:00:00.000Z',
  })

  for (const key of ['email', 'phone', 'location', 'latitude', 'gender', 'age', 'directMessage']) {
    assert.equal(normalizePublicCandidate({ ...candidate('person', 'profile-42'), [key]: 'private' }, { now: NOW }), null)
  }
  assert.equal(normalizePublicCandidate({ ...candidate('person', 'profile-42'), title: 'correo test@example.test' }, { now: NOW }), null)
  assert.equal(normalizePublicCandidate({ ...candidate('person', 'profile-42'), subtitle: '+51 999 123 456' }, { now: NOW }), null)
  assert.equal(normalizePublicCandidate({ ...candidate('person', 'profile-42'), subjectId: 'somebody-else' }, { now: NOW }), null)
  assert.ok(SOCIALIZATION_CARD_CANDIDATE_CONTRACT.forbiddenData.includes('direct messages'))
}

function testSafetyDecisionFailsClosed() {
  assert.equal(safetyAllowsCard(allows()), true)
  for (const [key, value] of [
    ['isPublic', false],
    ['isDeleted', true],
    ['isHidden', true],
    ['isSuspended', true],
    ['viewerBlockedCandidate', true],
    ['candidateBlockedViewer', true],
  ]) {
    assert.equal(safetyAllowsCard({ ...allows(), [key]: value }), false, key)
  }
  assert.equal(safetyAllowsCard({ isPublic: true }), false)
  assert.equal(safetyAllowsCard({ ...allows(), unknown: false }), false)
}

async function testAllCandidateTypesAreFilteredByAuthoritativeSafety() {
  const evaluations = []
  const provider = createSocializationCardCandidateProvider({
    loadCandidates: async () => ({
      items: [
        candidate('person', 'profile-2'),
        candidate('community', 'community-2', 'owner-community-2'),
        candidate('challenge', 'challenge-2', 'owner-challenge-2'),
        candidate('live', 'live-2', 'owner-live-2'),
      ],
      nextCursor: 'cursor_2',
    }),
    evaluateCandidateSafety: async ({ candidate: checked }) => {
      evaluations.push(checked)
      return allows()
    },
    now: () => NOW,
  })

  const result = await provider(request())
  assert.equal(result.nextCursor, 'cursor_2')
  assert.deepEqual(result.items.map((item) => item.type), ['person', 'community', 'challenge', 'live'])
  assert.equal(evaluations.length, 4)
  assert.deepEqual(evaluations[0], {
    type: 'person', targetId: 'profile-2', subjectId: 'profile-2',
  })
  assert.deepEqual(Object.keys(result.items[0]).sort(), [
    'expiresAt', 'imageUrl', 'subtitle', 'targetId', 'title', 'type',
  ])
  assert.equal(JSON.stringify(result.items).includes('subjectId'), false)
}

async function testSelfAndUnsafeCandidatesNeverEscape() {
  const states = new Map([
    ['profile-2', allows()],
    ['community-hidden', { ...allows(), isHidden: true }],
    ['challenge-deleted', { ...allows(), isDeleted: true }],
    ['live-suspended', { ...allows(), isSuspended: true }],
    ['profile-blocked-by-viewer', { ...allows(), viewerBlockedCandidate: true }],
    ['profile-blocked-viewer', { ...allows(), candidateBlockedViewer: true }],
  ])
  const provider = createSocializationCardCandidateProvider({
    loadCandidates: async () => ({
      items: [
        candidate('person', 'viewer-1'),
        candidate('live', 'live-self-owner', 'legacy-viewer-1'),
        candidate('person', 'profile-2'),
        candidate('community', 'community-hidden'),
        candidate('challenge', 'challenge-deleted'),
        candidate('live', 'live-suspended'),
        candidate('person', 'profile-blocked-by-viewer'),
        candidate('person', 'profile-blocked-viewer'),
      ],
      nextCursor: 'cursor_2',
    }),
    evaluateCandidateSafety: async ({ candidate: checked }) => states.get(checked.targetId) || allows(),
    now: () => NOW,
  })

  const result = await provider(request())
  assert.deepEqual(result.items.map((item) => item.targetId), ['profile-2'])
}

async function testSourceAndEvaluatorFailuresFailClosed() {
  const unavailable = createSocializationCardCandidateProvider({
    loadCandidates: async () => { throw new Error('source unavailable') },
    evaluateCandidateSafety: async () => allows(),
  })
  assert.deepEqual(await unavailable(request()), { items: [], nextCursor: null })

  const evaluatorUnavailable = createSocializationCardCandidateProvider({
    loadCandidates: async () => ({ items: [candidate('person', 'profile-2')], nextCursor: 'cursor_2' }),
    evaluateCandidateSafety: async () => { throw new Error('policy unavailable') },
  })
  assert.deepEqual(await evaluatorUnavailable(request()), { items: [], nextCursor: 'cursor_2' })

  const malformedSource = createSocializationCardCandidateProvider({
    loadCandidates: async () => ({ items: [], rawLocation: 'must not be accepted' }),
    evaluateCandidateSafety: async () => allows(),
  })
  assert.deepEqual(await malformedSource(request()), { items: [], nextCursor: null })
}

async function testRequestTypeAndLimitAreEnforced() {
  let loaderCalls = 0
  const provider = createSocializationCardCandidateProvider({
    loadCandidates: async () => {
      loaderCalls += 1
      return { items: [candidate('person', 'profile-2')] }
    },
    evaluateCandidateSafety: async () => allows(),
  })
  assert.deepEqual(await provider(request({ allowedTypes: [] })), { items: [], nextCursor: null })
  assert.deepEqual(await provider(request({ cursor: { offset: 1 } })), { items: [], nextCursor: null })
  assert.deepEqual(await provider(request({ placement: 'before_2' })), { items: [], nextCursor: null })
  assert.equal(loaderCalls, 0)
}

async function testProviderMatchesTheCardRouteLoaderContract() {
  const provider = createSocializationCardCandidateProvider({
    loadCandidates: async () => ({ items: [candidate('live', 'live-2', 'host-2')] }),
    evaluateCandidateSafety: async () => allows(),
    now: () => NOW,
  })
  const routes = []
  registerSocializationCardRoutes({
    get(route, ...handlers) { routes.push({ route, handlers }) },
  }, {
    pool: {},
    authRequired: (_req, _res, next) => next(),
    getCurrentUser: async () => ({ id: 'viewer-1' }),
    userIdentity: () => viewerIdentity(),
    loadPreferences: async () => ({ discovery_opt_in: true, community_discovery_opt_in: true }),
    loadCandidates: provider,
    featureFlags: () => ({ core: true, cards: true, discovery: true, communityDiscovery: true }),
    rateLimiter: () => ({ allowed: true, limit: 60, remaining: 59, retryAfterSeconds: 1 }),
    now: () => NOW,
  })
  const handler = routes[0].handlers[1]
  const state = { statusCode: 200, body: null, headers: {} }
  const response = {
    status(code) { state.statusCode = code; return this },
    json(body) { state.body = body; return this },
    setHeader(name, value) { state.headers[String(name).toLowerCase()] = value },
  }
  let forwarded = null
  await handler({ query: { surface: 'live', placement: 'after_0', limit: '1' } }, response, (error) => { forwarded = error })
  assert.equal(forwarded, null)
  assert.equal(state.statusCode, 200)
  assert.deepEqual(state.body.items, [{
    id: 'live:live-2',
    type: 'live',
    title: 'Etiqueta pública live-2',
    subtitle: 'Solo información pública aprobada',
    imageUrl: 'https://cdn.example.test/card.png',
    expiresAt: '2026-08-07T13:00:00.000Z',
    reasonCode: 'ACTIVE_LIVE',
    action: { type: 'OPEN_LIVE', targetId: 'live-2' },
    dismissible: true,
  }])
}

function testProviderDoesNotDependOnUnsafeLegacySources() {
  const source = fs.readFileSync(path.join(__dirname, '../src/socialization-safe-candidate-provider.js'), 'utf8')
  assert.doesNotMatch(source, /require\(['"]\.\/(?:data-routes|proximity-routes|socialization-routes)['"]\)/)
  assert.doesNotMatch(source, /\bpool\.query\b/)
  assert.doesNotMatch(source, /fallback_messages|fallback_random_chat|fallback_proximity/i)
}

Promise.resolve()
  .then(testCandidateContractRejectsSensitiveInput)
  .then(testSafetyDecisionFailsClosed)
  .then(testAllCandidateTypesAreFilteredByAuthoritativeSafety)
  .then(testSelfAndUnsafeCandidatesNeverEscape)
  .then(testSourceAndEvaluatorFailuresFailClosed)
  .then(testRequestTypeAndLimitAreEnforced)
  .then(testProviderMatchesTheCardRouteLoaderContract)
  .then(testProviderDoesNotDependOnUnsafeLegacySources)
  .then(() => console.log('socialization safe candidate provider tests passed'))
  .catch((error) => {
    console.error(error)
    process.exitCode = 1
  })
