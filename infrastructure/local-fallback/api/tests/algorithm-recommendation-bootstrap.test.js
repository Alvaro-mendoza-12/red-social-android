'use strict'

const assert = require('assert/strict')
const {
  createAuthoritativeRecommendationLoader,
  recommendationFeatureFlags,
  registerAuthoritativeRecommendationRoutes,
} = require('../src/algorithm-recommendation-bootstrap')

function fakePool() {
  return { query: async () => ({ rows: [] }) }
}

function testFeatureFlagsFailClosedUnlessClassificationIsEnabled() {
  assert.equal(recommendationFeatureFlags(() => ({ core: true, classification: false })).core, false)
  assert.equal(recommendationFeatureFlags(() => ({ core: false, classification: true })).core, false)
  const flags = recommendationFeatureFlags(() => ({ core: true, classification: true, explanations: true }))
  assert.equal(flags.core, true)
  assert.equal(flags.explanations, true)
}

function testLoaderComposesOnlyTheSafeProviderBoundary() {
  const calls = []
  const loader = createAuthoritativeRecommendationLoader(fakePool(), {
    createLocalSource: ({ pool }) => {
      calls.push(['source', pool])
      return {
        loadRawCandidates: async () => ({ snapshotId: 's', candidates: [] }),
        loadBlockEdges: async () => [],
      }
    },
    createCandidateProvider: (dependencies) => {
      calls.push(['provider', dependencies])
      return async () => ({ snapshotId: 's', candidates: [] })
    },
  })
  assert.equal(typeof loader, 'function')
  assert.equal(calls[0][0], 'source')
  assert.equal(calls[1][0], 'provider')
  assert.equal(typeof calls[1][1].loadRawCandidates, 'function')
  assert.equal(typeof calls[1][1].loadBlockEdges, 'function')
}

function testRegistrationUsesAuthenticatedDependenciesAndGatedFlags() {
  const calls = []
  const pool = fakePool()
  registerAuthoritativeRecommendationRoutes({}, {
    pool,
    authRequired: () => {},
    getCurrentUser: async () => ({ id: 'user' }),
    userIdentity: () => ({ localUserId: 'user' }),
    createLoader: (receivedPool) => {
      assert.equal(receivedPool, pool)
      return async () => ({ snapshotId: 's', candidates: [] })
    },
    algorithmFeatureFlags: () => ({ core: true, classification: true }),
    registerRoutes: (_app, options) => calls.push(options),
  })
  assert.equal(calls.length, 1)
  assert.equal(calls[0].pool, pool)
  assert.equal(calls[0].algorithmFeatureFlags().core, true)
  assert.equal(typeof calls[0].authRequired, 'function')
  assert.equal(typeof calls[0].getCurrentUser, 'function')
  assert.equal(typeof calls[0].userIdentity, 'function')
  assert.equal(typeof calls[0].loadCandidates, 'function')
}

Promise.resolve()
  .then(testFeatureFlagsFailClosedUnlessClassificationIsEnabled)
  .then(testLoaderComposesOnlyTheSafeProviderBoundary)
  .then(testRegistrationUsesAuthenticatedDependenciesAndGatedFlags)
  .then(() => console.log('algorithm recommendation bootstrap tests passed'))
  .catch((error) => {
    console.error(error)
    process.exitCode = 1
  })
