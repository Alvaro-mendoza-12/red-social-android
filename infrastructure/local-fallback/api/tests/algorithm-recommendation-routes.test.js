const assert = require('assert/strict')
const {
  decodeOpaqueCursor,
  registerAlgorithmRecommendationRoutes,
} = require('../src/algorithm-recommendation-routes')

const CURSOR_SECRET = 'test-only-authoritative-recommendation-cursor-secret-12345'
const NOW = 1_700_000_000_000

function candidate(id, overrides = {}) {
  return {
    id,
    item: { id, title: `public-${id}` },
    creatorId: `creator-${id}`,
    topics: ['nature'],
    subtopics: ['wildlife'],
    feedSignals: { following: 0, interest: 0, community: 0, new_content: 0, trend: 0, exploration: 0 },
    ...overrides,
  }
}

function config() {
  return {
    preferences: [],
    temporaryPreferences: [],
    feedMixes: [{
      surface: 'video',
      following: 30,
      interests: 40,
      communities: 0,
      newContent: 10,
      trends: 10,
      exploration: 10,
    }],
    dataPermissions: { likes: false },
  }
}

function response() {
  return {
    statusCode: 200,
    headers: {},
    body: null,
    status(code) {
      this.statusCode = code
      return this
    },
    setHeader(name, value) {
      this.headers[name.toLowerCase()] = value
    },
    json(value) {
      this.body = value
      return this
    },
  }
}

function createHarness(overrides = {}) {
  const routes = []
  const app = {
    get(route, ...handlers) {
      routes.push({ route, handler: handlers.at(-1) })
    },
  }
  let currentUser = { id: 'owner-1' }
  const calls = []
  const defaults = {
    pool: {},
    authRequired: (_req, _res, next) => next(),
    getCurrentUser: async () => currentUser,
    userIdentity: (user) => ({ localUserId: user.id }),
    loadCandidates: async (input) => {
      calls.push(input)
      return {
        snapshotId: 'snapshot-1',
        providerState: { asOf: 'fixed' },
        candidates: [
          candidate('dolphin', { creatorId: 'creator-alice', topics: ['dolphins'], isFollowedCreator: true }),
          candidate('forest', { creatorId: 'creator-alice', topics: ['forest'], isFollowedCreator: true }),
          candidate('space', { creatorId: 'creator-space', topics: ['space'] }),
        ],
      }
    },
    algorithmFeatureFlags: () => ({ core: true, explanations: true }),
    findOwnedProfile: async (_pool, ownerId, profileId) => {
      if (ownerId !== 'owner-1') return null
      if (profileId && profileId !== 'profile-1') return null
      return { id: 'profile-1', revision: 3, is_enabled: true }
    },
    loadAlgorithmConfiguration: async () => ({
      ...config(),
      preferences: [
        { surface: 'video', dimension: 'creator', subjectKey: 'creator-alice', score: 90 },
        { surface: 'video', dimension: 'topic', subjectKey: 'dolphins', score: -100 },
      ],
    }),
    cursorSecret: CURSOR_SECRET,
    now: () => NOW,
  }
  registerAlgorithmRecommendationRoutes(app, { ...defaults, ...overrides })
  const route = routes.find((entry) => entry.route === '/v1/data/recommendations')
  assert.ok(route, 'recommendations route must be registered')
  return {
    handler: route.handler,
    calls,
    setCurrentUser(value) { currentUser = value },
  }
}

async function invoke(harness, query = {}) {
  const res = response()
  let passedError = null
  await harness.handler({ query }, res, (error) => { passedError = error })
  assert.equal(passedError, null)
  return res
}

async function testFailClosedBeforeCandidateLoading() {
  let loaded = false
  const harness = createHarness({
    algorithmFeatureFlags: () => ({ core: false, explanations: true }),
    loadCandidates: async () => {
      loaded = true
      return { snapshotId: 'unexpected', candidates: [] }
    },
  })
  const res = await invoke(harness, { surface: 'video' })
  assert.equal(res.statusCode, 404)
  assert.deepEqual(res.body, { error: 'USER_ALGORITHM_DISABLED' })
  assert.equal(loaded, false)
}

async function testAuthenticatedOwnedProfileAndAuthoritativeRanking() {
  const harness = createHarness()
  const res = await invoke(harness, { surface: 'video', limit: '1', userId: 'other-user' })
  assert.equal(res.statusCode, 200)
  assert.deepEqual(res.body.items, [{ id: 'forest', title: 'public-forest' }])
  assert.equal(res.body.algorithm.authoritative, true)
  assert.equal(res.body.algorithm.explanationsEnabled, true)
  assert.equal(res.body.explanations.length, 1)
  assert.equal(res.body.explanations[0].candidateId, 'forest')
  assert.equal(res.headers['cache-control'], 'private, no-store')
  assert.equal(harness.calls[0].owner.userId, 'owner-1')
  assert.equal(harness.calls[0].cursor, null)
  assert.ok(res.body.page.nextCursor)
  assert.equal(res.body.page.nextCursor.includes('snapshot-1'), false, 'cursor must be opaque')
}

async function testOpaqueCursorBindsOwnerSurfaceProfileRevisionAndSnapshot() {
  const harness = createHarness()
  const first = await invoke(harness, { surface: 'video', limit: 1 })
  const cursor = first.body.page.nextCursor
  const decoded = decodeOpaqueCursor(cursor, CURSOR_SECRET, NOW)
  assert.equal(decoded.ownerId, 'owner-1')
  assert.equal(decoded.surface, 'video')
  assert.equal(decoded.position, 1)
  assert.deepEqual(decoded.providerState, { asOf: 'fixed' })

  const next = await invoke(harness, { surface: 'video', limit: 1, cursor })
  assert.equal(next.statusCode, 200)
  assert.deepEqual(next.body.items, [{ id: 'space', title: 'public-space' }])
  assert.equal(harness.calls[1].cursor.snapshotId, 'snapshot-1')
  assert.deepEqual(harness.calls[1].cursor.providerState, { asOf: 'fixed' })

  harness.setCurrentUser({ id: 'another-user' })
  const wrongOwner = await invoke(harness, { surface: 'video', cursor })
  assert.equal(wrongOwner.statusCode, 400)
  assert.deepEqual(wrongOwner.body, { error: 'RECOMMENDATION_CURSOR_INVALID' })
}

async function testExplanationsAreStrictlyGated() {
  const harness = createHarness({
    algorithmFeatureFlags: () => ({ core: true, explanations: false }),
  })
  const res = await invoke(harness, { surface: 'video' })
  assert.equal(res.statusCode, 200)
  assert.equal(res.body.algorithm.explanationsEnabled, false)
  assert.equal(Object.prototype.hasOwnProperty.call(res.body, 'explanations'), false)
}

async function testCursorFailsClosedWhenTheProviderSnapshotChanges() {
  const harness = createHarness({
    loadCandidates: async (input) => ({
      snapshotId: input.cursor ? 'snapshot-rotated' : 'snapshot-1',
      providerState: { asOf: 'fixed' },
      candidates: [
        candidate('dolphin', { creatorId: 'creator-alice', topics: ['dolphins'] }),
        candidate('forest', { creatorId: 'creator-alice', topics: ['forest'] }),
        candidate('space', { creatorId: 'creator-space', topics: ['space'] }),
      ],
    }),
  })
  const first = await invoke(harness, { surface: 'video', limit: 1 })
  const stale = await invoke(harness, { surface: 'video', limit: 1, cursor: first.body.page.nextCursor })
  assert.equal(stale.statusCode, 409)
  assert.deepEqual(stale.body, { error: 'RECOMMENDATION_CURSOR_STALE' })
}

async function testProfileOwnershipAndOptInAreEnforced() {
  let loaded = false
  const notOwned = createHarness({
    findOwnedProfile: async () => null,
    loadCandidates: async () => {
      loaded = true
      return { snapshotId: 'never', candidates: [] }
    },
  })
  const first = await invoke(notOwned, { surface: 'video', profileId: 'not-owned' })
  assert.equal(first.statusCode, 404)
  assert.deepEqual(first.body, { error: 'ALGORITHM_PROFILE_NOT_FOUND' })
  assert.equal(loaded, false)

  const disabled = createHarness({
    findOwnedProfile: async () => ({ id: 'profile-1', revision: 3, is_enabled: false }),
  })
  const second = await invoke(disabled, { surface: 'video' })
  assert.equal(second.statusCode, 409)
  assert.deepEqual(second.body, { error: 'ALGORITHM_PROFILE_DISABLED' })
}

async function testInvalidSurfaceAndInvalidCandidateBatchFailSafely() {
  const harness = createHarness()
  const invalidSurface = await invoke(harness, { surface: 'live' })
  assert.equal(invalidSurface.statusCode, 400)
  assert.deepEqual(invalidSurface.body, { error: 'RECOMMENDATION_SURFACE_INVALID' })

  const invalidBatch = createHarness({
    loadCandidates: async () => ({ snapshotId: 'snapshot-1', candidates: [{ id: 'leak-without-public-item' }] }),
  })
  const res = await invoke(invalidBatch, { surface: 'video' })
  assert.equal(res.statusCode, 502)
  assert.deepEqual(res.body, { error: 'RECOMMENDATION_CANDIDATE_INVALID' })
}

Promise.resolve()
  .then(testFailClosedBeforeCandidateLoading)
  .then(testAuthenticatedOwnedProfileAndAuthoritativeRanking)
  .then(testOpaqueCursorBindsOwnerSurfaceProfileRevisionAndSnapshot)
  .then(testExplanationsAreStrictlyGated)
  .then(testCursorFailsClosedWhenTheProviderSnapshotChanges)
  .then(testProfileOwnershipAndOptInAreEnforced)
  .then(testInvalidSurfaceAndInvalidCandidateBatchFailSafely)
  .then(() => console.log('algorithm recommendation route tests passed'))
  .catch((error) => {
    console.error(error)
    process.exitCode = 1
  })
