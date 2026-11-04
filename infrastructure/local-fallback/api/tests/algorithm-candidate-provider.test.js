'use strict'

const assert = require('assert/strict')
const {
  AlgorithmCandidateProviderError,
  createAlgorithmCandidateProvider,
  normalizedInferenceSignals,
  safetyVerdict,
} = require('../src/algorithm-candidate-provider')
const { registerAlgorithmRecommendationRoutes } = require('../src/algorithm-recommendation-routes')

const NOW = 1_700_000_000_000
const CURSOR_SECRET = 'test-only-safe-candidate-provider-cursor-secret-12345'

function safeRaw(id, surface, overrides = {}) {
  return {
    id,
    surface,
    creatorId: `creator-${id}`,
    safety: {
      contentPublic: true,
      contentEligible: true,
      creatorVisible: true,
      creatorHidden: false,
      creatorSuspended: false,
      ageEligible: true,
    },
    item: {
      id,
      title: `Public ${id}`,
      description: 'Visible para la audiencia pública',
    },
    topics: ['nature'],
    feedSignals: { interest: 1 },
    ...overrides,
  }
}

function owner() {
  return {
    userId: 'viewer-public',
    identity: {
      displayUserId: 'viewer-public',
      ids: new Set(['viewer-public', 'viewer-local']),
    },
  }
}

function provider({ rows, edges = [], inferredSignals } = {}) {
  const calls = { raw: [], blocks: [] }
  const loadCandidates = createAlgorithmCandidateProvider({
    loadRawCandidates: async (input) => {
      calls.raw.push(input)
      return {
        snapshotId: 'snapshot-public-1',
        providerState: { snapshot: 'public-1' },
        inferredSignals,
        candidates: rows,
      }
    },
    loadBlockEdges: async (input) => {
      calls.blocks.push(input)
      return edges
    },
  })
  return { loadCandidates, calls }
}

async function testPublicVideoAndSocialCandidatesHaveOnlySafeRankingFields() {
  const rows = [
    safeRaw('video-1', 'video', {
      item: {
        id: 'video-1',
        title: 'Colibrí',
        email: 'private@example.test',
        exact_location: { latitude: 1, longitude: 2 },
        userEmail: 'also-private@example.test',
        exactLocation: { latitude: 3, longitude: 4 },
        directMessages: ['never'],
        nested: { token: 'must-not-leak', publicLabel: 'ok' },
      },
      creatorAliases: ['creator-video-1', 'creator-external-1'],
      trendScore: 0.8,
    }),
    safeRaw('social-1', 'social', {
      item: { id: 'social-1', title: 'Publicación de aves' },
      formats: ['post'],
    }),
  ]
  const harness = provider({ rows })
  const video = await harness.loadCandidates({ owner: owner(), surface: 'video', limit: 20, maxCandidates: 200, now: NOW })
  assert.equal(video.snapshotId, 'snapshot-public-1')
  assert.deepEqual(video.providerState, { snapshot: 'public-1' })
  assert.equal(video.candidates.length, 1)
  assert.equal(video.candidates[0].id, 'video-1')
  assert.deepEqual(video.candidates[0].item, {
    id: 'video-1',
    title: 'Colibrí',
    nested: { publicLabel: 'ok' },
  })
  assert.equal(video.candidates[0].creatorId, 'creator-video-1')
  assert.equal(video.candidates[0].trendScore, 0.8)
  assert.deepEqual(harness.calls.raw[0].cursor, null)
  assert.deepEqual(harness.calls.blocks[0].viewerIds.sort(), ['viewer-local', 'viewer-public'])

  const social = await harness.loadCandidates({ owner: owner(), surface: 'social', limit: 20, maxCandidates: 200, now: NOW })
  assert.equal(social.candidates.length, 1)
  assert.equal(social.candidates[0].id, 'social-1')
  assert.equal(harness.calls.raw[1].surface, 'social')
}

async function testMandatoryVisibilitySelfStatusAndBidirectionalBlockFilters() {
  const rows = [
    safeRaw('valid', 'video'),
    safeRaw('private-content', 'video', { visibility: 'followers' }),
    safeRaw('self-alias', 'video', { creatorAliases: ['viewer-local'] }),
    safeRaw('hidden', 'video', { safety: { ...safeRaw('x', 'video').safety, creatorHidden: true } }),
    safeRaw('suspended', 'video', { safety: { ...safeRaw('x', 'video').safety, creatorSuspended: true } }),
    safeRaw('hidden-break', 'video', { creator: { break_mode: 'hidden' } }),
    safeRaw('suspension-window', 'video', { creator: { suspended_until: '2030-01-01T00:00:00.000Z' } }),
    safeRaw('blocked-by-viewer', 'video'),
    safeRaw('blocked-viewer', 'video'),
    safeRaw('unverified-envelope', 'video', { safety: { contentPublic: true } }),
  ]
  const harness = provider({
    rows,
    edges: [
      { blockerId: 'viewer-public', blockedId: 'creator-blocked-by-viewer' },
      { blockerId: 'creator-blocked-viewer', blockedId: 'viewer-local' },
    ],
  })
  const result = await harness.loadCandidates({ owner: owner(), surface: 'video', now: NOW })
  assert.deepEqual(result.candidates.map((candidate) => candidate.id), ['valid'])
  assert.equal(harness.calls.blocks.length, 1)
  assert.ok(harness.calls.blocks[0].creatorIds.includes('creator-blocked-by-viewer'))
  assert.ok(harness.calls.blocks[0].creatorIds.includes('creator-blocked-viewer'))
}

async function testSafetyIsFailClosedAndBlockStoreFailureStopsTheBatch() {
  assert.equal(safetyVerdict(safeRaw('good', 'video'), NOW).allowed, true)
  assert.equal(safetyVerdict({ id: 'missing-envelope' }).allowed, false)
  assert.equal(safetyVerdict(safeRaw('private', 'video', { is_public: false })).allowed, false)

  const loadCandidates = createAlgorithmCandidateProvider({
    loadRawCandidates: async () => ({ snapshotId: 'snapshot-1', candidates: [safeRaw('valid', 'video')] }),
    loadBlockEdges: async () => ({ rows: [] }),
  })
  await assert.rejects(
    () => loadCandidates({ owner: owner(), surface: 'video', now: NOW }),
    (error) => error instanceof AlgorithmCandidateProviderError && error.code === 'RECOMMENDATION_PROVIDER_BLOCKS_UNAVAILABLE',
  )
}

async function testInferenceInputExcludesForbiddenSourcesAndDimensions() {
  const normalized = normalizedInferenceSignals({
    sources: {
      likes: { topic: { birds: 0.6 }, gender: { ignored: 1 } },
      private_messages: { topic: { leaked: 1 } },
      precise_location: { topic: { leaked: 1 } },
      public_interactions: { creator: { 'creator-a': 0.2 } },
    },
  })
  assert.deepEqual(normalized, {
    sources: {
      likes: { topic: { birds: 0.6 } },
      public_interactions: { creator: { 'creator-a': 0.2 } },
    },
  })
}

function response() {
  return {
    statusCode: 200,
    body: null,
    status(code) { this.statusCode = code; return this },
    setHeader() {},
    json(value) { this.body = value; return this },
  }
}

async function testProviderCanFeedTheIsolatedRecommendationRoute() {
  const routes = []
  const app = { get(route, ...handlers) { routes.push({ route, handler: handlers.at(-1) }) } }
  const safeProvider = provider({
    rows: [safeRaw('published-video', 'video', { topics: ['birds'] })],
  }).loadCandidates
  registerAlgorithmRecommendationRoutes(app, {
    hydrateItems: async ({ items }) => items,
    pool: {},
    authRequired: (_req, _res, next) => next(),
    getCurrentUser: async () => ({ id: 'viewer-public' }),
    userIdentity: () => owner().identity,
    loadCandidates: safeProvider,
    algorithmFeatureFlags: () => ({ core: true, explanations: false }),
    findOwnedProfile: async () => ({ id: 'profile-1', revision: 1, is_enabled: true }),
    loadAlgorithmConfiguration: async () => ({
      preferences: [],
      temporaryPreferences: [],
      feedMixes: [],
      dataPermissions: {},
    }),
    cursorSecret: CURSOR_SECRET,
    now: () => NOW,
  })
  const route = routes.find((entry) => entry.route === '/v1/data/recommendations')
  assert.ok(route)
  const res = response()
  let nextError = null
  await route.handler({ query: { surface: 'video' } }, res, (error) => { nextError = error })
  assert.equal(nextError, null)
  assert.equal(res.statusCode, 200)
  assert.deepEqual(res.body.items, [{ id: 'published-video', title: 'Public published-video', description: 'Visible para la audiencia pública' }])
}

Promise.resolve()
  .then(testPublicVideoAndSocialCandidatesHaveOnlySafeRankingFields)
  .then(testMandatoryVisibilitySelfStatusAndBidirectionalBlockFilters)
  .then(testSafetyIsFailClosedAndBlockStoreFailureStopsTheBatch)
  .then(testInferenceInputExcludesForbiddenSourcesAndDimensions)
  .then(testProviderCanFeedTheIsolatedRecommendationRoute)
  .then(() => console.log('algorithm candidate provider tests passed'))
  .catch((error) => {
    console.error(error)
    process.exitCode = 1
  })
