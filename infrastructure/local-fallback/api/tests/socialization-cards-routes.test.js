const assert = require('assert/strict')
const fs = require('fs')
const path = require('path')
const {
  SOCIALIZATION_CARD_FLAGS,
  cardCapabilities,
  normalizeCandidate,
  normalizeLimit,
  normalizePlacement,
  normalizeSurface,
  registerSocializationCardRoutes,
  sanitizeCards,
  socializationCardFeatureFlags,
} = require('../src/socialization-cards-routes')

function withFlags(values, callback) {
  const previous = new Map(Object.values(SOCIALIZATION_CARD_FLAGS).map((name) => [name, process.env[name]]))
  try {
    for (const name of Object.values(SOCIALIZATION_CARD_FLAGS)) delete process.env[name]
    for (const [name, value] of Object.entries(values)) process.env[name] = value
    return callback()
  } finally {
    for (const [name, value] of previous) {
      if (value === undefined) delete process.env[name]
      else process.env[name] = value
    }
  }
}

function enabledFlags(overrides = {}) {
  return {
    core: true,
    cards: true,
    discovery: true,
    communityDiscovery: true,
    ...overrides,
  }
}

function enabledPreferences(overrides = {}) {
  return {
    discovery_opt_in: true,
    community_discovery_opt_in: true,
    ...overrides,
  }
}

function createResponse() {
  const state = { statusCode: 200, body: null, headers: {} }
  return {
    state,
    status(code) {
      state.statusCode = code
      return this
    },
    json(body) {
      state.body = body
      return this
    },
    setHeader(name, value) {
      state.headers[String(name).toLowerCase()] = value
    },
  }
}

function buildRoute(overrides = {}) {
  const routes = []
  const app = {
    get(route, ...handlers) {
      routes.push({ route, handlers })
    },
  }
  registerSocializationCardRoutes(app, {
    pool: {},
    authRequired: (_req, _res, next) => next(),
    getCurrentUser: async () => ({ id: 'viewer-1' }),
    userIdentity: () => ({ displayUserId: 'viewer-1', ids: new Set(['viewer-1']) }),
    loadPreferences: async () => enabledPreferences(),
    loadCandidates: async () => ({ items: [] }),
    featureFlags: () => enabledFlags(),
    rateLimiter: () => ({ allowed: true, limit: 60, remaining: 59, retryAfterSeconds: 1 }),
    now: () => Date.parse('2026-08-07T12:00:00.000Z'),
    ...overrides,
  })
  assert.equal(routes.length, 1)
  assert.equal(routes[0].route, '/v1/data/socialization/cards')
  assert.equal(routes[0].handlers.length, 2)
  return routes[0].handlers[1]
}

async function invoke(handler, query = {}) {
  const response = createResponse()
  let forwarded = null
  await handler({ query }, response, (error) => { forwarded = error })
  return { ...response.state, forwarded }
}

function testFlagsFailClosed() {
  withFlags({}, () => {
    assert.deepEqual(socializationCardFeatureFlags(), {
      core: false,
      cards: false,
      discovery: false,
      communityDiscovery: false,
    })
  })
  withFlags({
    SOCIALIZATION_ENABLED: 'true',
    SOCIALIZATION_CARDS_ENABLED: 'yes',
    SOCIALIZATION_DISCOVERY_ENABLED: '1',
  }, () => {
    const flags = socializationCardFeatureFlags()
    assert.equal(flags.core, true)
    assert.equal(flags.cards, true)
    assert.equal(flags.discovery, true)
    assert.equal(flags.communityDiscovery, false)
  })
}

function testRequestAndCandidateNormalization() {
  assert.equal(normalizeSurface('VIDEO'), 'video')
  assert.equal(normalizeSurface('unexpected'), null)
  assert.equal(normalizePlacement('after_12'), 'after_12')
  assert.equal(normalizePlacement('before_12'), null)
  assert.equal(normalizeLimit('4'), 4)
  assert.equal(normalizeLimit('5'), null)

  const capabilities = cardCapabilities(enabledPreferences(), enabledFlags())
  const card = normalizeCandidate({
    type: 'person',
    targetId: 'profile-42',
    title: '  Perfil público  ',
    subtitle: 'Una descripción pública',
    imageUrl: 'https://cdn.example.test/avatar.png',
  }, { capabilities, now: Date.parse('2026-08-07T12:00:00.000Z') })
  assert.deepEqual(card, {
    id: 'person:profile-42',
    type: 'person',
    title: 'Perfil público',
    subtitle: 'Una descripción pública',
    imageUrl: 'https://cdn.example.test/avatar.png',
    reasonCode: 'PUBLIC_PROFILE',
    action: { type: 'OPEN_PROFILE', targetId: 'profile-42' },
    dismissible: true,
  })
  assert.equal(normalizeCandidate({
    type: 'person', targetId: 'profile-42', title: 'Perfil', unreviewed: 'not allowed',
  }, { capabilities }), null)
  assert.equal(normalizeCandidate({
    type: 'person', targetId: 'profile-42', title: 'Perfil', imageUrl: 'http://example.test/a.png',
  }, { capabilities }), null)
}

function testConsentNeedsBothGateAndPreference() {
  assert.deepEqual(cardCapabilities(enabledPreferences(), {
    core: true,
    cards: false,
    discovery: true,
    communityDiscovery: true,
  }), { discovery: false, communityDiscovery: false })
  assert.deepEqual(cardCapabilities(enabledPreferences({ discovery_opt_in: false }), enabledFlags()), {
    discovery: false,
    communityDiscovery: true,
  })
}

async function testDisabledFeatureDoesNotLoadAnything() {
  let preferenceCalls = 0
  let candidateCalls = 0
  const handler = buildRoute({
    featureFlags: () => enabledFlags({ core: false }),
    loadPreferences: async () => {
      preferenceCalls += 1
      return enabledPreferences()
    },
    loadCandidates: async () => {
      candidateCalls += 1
      return { items: [] }
    },
  })
  const response = await invoke(handler)
  assert.equal(response.statusCode, 404)
  assert.deepEqual(response.body, { error: 'SOCIALIZATION_DISABLED' })
  assert.equal(preferenceCalls, 0)
  assert.equal(candidateCalls, 0)
}

async function testConsentDeniedDoesNotLoadCandidates() {
  let candidateCalls = 0
  const handler = buildRoute({
    loadPreferences: async () => enabledPreferences({
      discovery_opt_in: false,
      community_discovery_opt_in: false,
    }),
    loadCandidates: async () => {
      candidateCalls += 1
      return { items: [] }
    },
  })
  const response = await invoke(handler, { surface: 'social', placement: 'after_3', limit: '2' })
  assert.equal(response.statusCode, 200)
  assert.equal(response.body.enabled, false)
  assert.deepEqual(response.body.items, [])
  assert.equal(candidateCalls, 0)
  assert.equal(response.headers['cache-control'], 'private, no-store')
}

async function testCardsAreAllowlistedDeduplicatedAndCursorEncoded() {
  let loaderInput = null
  const cursorCodec = {
    async decode(token) {
      return token === 'page_1' ? { offset: 4 } : null
    },
    async encode(value) {
      return value?.offset === 9 ? 'page_2' : null
    },
  }
  const handler = buildRoute({
    cursorCodec,
    loadCandidates: async (input) => {
      loaderInput = input
      return {
        items: [
          { type: 'community', targetId: 'community-1', title: 'Comunidad pública' },
          { type: 'community', targetId: 'community-1', title: 'Duplicada' },
          { type: 'person', targetId: 'profile-2', title: 'Perfil público', subtitle: 'Visible' },
          { type: 'live', targetId: 'live-3', title: 'Directo activo', ignored: 'not allowed' },
        ],
        nextCursor: { offset: 9 },
      }
    },
  })
  const response = await invoke(handler, {
    surface: 'video',
    placement: 'after_12',
    limit: '2',
    cursor: 'page_1',
  })
  assert.equal(response.statusCode, 200)
  assert.equal(loaderInput.surface, 'video')
  assert.equal(loaderInput.placement, 'after_12')
  assert.equal(loaderInput.limit, 8)
  assert.deepEqual(loaderInput.cursor, { offset: 4 })
  assert.deepEqual(loaderInput.allowedTypes, ['person', 'community', 'challenge', 'live'])
  assert.equal(response.body.nextCursor, 'page_2')
  assert.equal(response.body.items.length, 2)
  assert.deepEqual(response.body.items.map((item) => item.id), ['community:community-1', 'person:profile-2'])
  assert.deepEqual(Object.keys(response.body.items[0]).sort(), [
    'action', 'dismissible', 'id', 'reasonCode', 'title', 'type',
  ])
  assert.deepEqual(response.body.safeguards, {
    consentRequired: true,
    serverFiltered: true,
    automaticContact: false,
  })
}

async function testTypeSpecificConsentAndInvalidCursor() {
  let candidateCalls = 0
  const handler = buildRoute({
    loadPreferences: async () => enabledPreferences({ community_discovery_opt_in: false }),
    loadCandidates: async () => {
      candidateCalls += 1
      return {
        items: [
          { type: 'community', targetId: 'community-1', title: 'No debe aparecer' },
          { type: 'person', targetId: 'profile-1', title: 'Sí puede aparecer' },
        ],
      }
    },
  })
  const response = await invoke(handler, { limit: '2' })
  assert.equal(response.statusCode, 200)
  assert.deepEqual(response.body.items.map((item) => item.id), ['person:profile-1'])
  assert.equal(candidateCalls, 1)

  const invalidCursorHandler = buildRoute({
    loadCandidates: async () => {
      throw new Error('loader must not run')
    },
  })
  const invalidCursor = await invoke(invalidCursorHandler, { cursor: 'page_1' })
  assert.equal(invalidCursor.statusCode, 400)
  assert.deepEqual(invalidCursor.body, { error: 'SOCIALIZATION_CURSOR_INVALID' })
}

async function testInvalidLoaderResultFailsClosed() {
  const handler = buildRoute({
    loadCandidates: async () => ({ items: [], extra: 'unapproved' }),
  })
  const response = await invoke(handler)
  assert.equal(response.statusCode, 503)
  assert.deepEqual(response.body, { error: 'SOCIALIZATION_CARDS_UNAVAILABLE' })
}

function testSanitizerDeduplicatesWithoutLeakingUnexpectedFields() {
  const cards = sanitizeCards([
    { type: 'challenge', targetId: 'challenge-1', title: 'Reto activo' },
    { type: 'challenge', targetId: 'challenge-1', title: 'Duplicado' },
    { type: 'challenge', targetId: 'challenge-2', title: 'No revisado', internal: true },
  ], {
    capabilities: { discovery: true, communityDiscovery: false },
    limit: 4,
  })
  assert.deepEqual(cards.map((card) => card.id), ['challenge:challenge-1'])
}

function testModuleOwnsNoLegacyDataSource() {
  const source = fs.readFileSync(path.join(__dirname, '../src/socialization-cards-routes.js'), 'utf8')
  assert.doesNotMatch(source, /require\(['"]\.\/(?:data-routes|proximity-routes)['"]\)/)
  assert.doesNotMatch(source, /\bpool\.query\b/)
  assert.doesNotMatch(source, /\bfallback_messages\b|\bfallback_random_chat\b|\bfallback_proximity\b/i)
}

Promise.resolve()
  .then(testFlagsFailClosed)
  .then(testRequestAndCandidateNormalization)
  .then(testConsentNeedsBothGateAndPreference)
  .then(testDisabledFeatureDoesNotLoadAnything)
  .then(testConsentDeniedDoesNotLoadCandidates)
  .then(testCardsAreAllowlistedDeduplicatedAndCursorEncoded)
  .then(testTypeSpecificConsentAndInvalidCursor)
  .then(testInvalidLoaderResultFailsClosed)
  .then(testSanitizerDeduplicatesWithoutLeakingUnexpectedFields)
  .then(testModuleOwnsNoLegacyDataSource)
  .then(() => console.log('socialization cards routes tests passed'))
  .catch((error) => {
    console.error(error)
    process.exitCode = 1
  })
