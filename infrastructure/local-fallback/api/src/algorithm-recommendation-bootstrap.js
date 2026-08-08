'use strict'

/**
 * Composition boundary for the optional server-authoritative recommendation
 * endpoint. Legacy `/feed` and `/videos` are intentionally not imported or
 * changed here: screens only opt into this endpoint after they have checked
 * capabilities and can retain the legacy/offline feed on any error.
 */

const { algorithmFeatureFlags: defaultAlgorithmFeatureFlags } = require('./algorithm-routes')
const { registerAlgorithmRecommendationRoutes: defaultRegisterRoutes } = require('./algorithm-recommendation-routes')
const { createAlgorithmCandidateProvider: defaultCreateCandidateProvider } = require('./algorithm-candidate-provider')
const { createAlgorithmLocalFallbackCandidateSource: defaultCreateLocalSource } = require('./algorithm-local-fallback-candidate-source')

function recommendationFeatureFlags(algorithmFeatureFlags = defaultAlgorithmFeatureFlags) {
  const flags = algorithmFeatureFlags() || {}
  return {
    ...flags,
    // Classification is mandatory for this endpoint. Content must remain
    // absent rather than be ranked from user-provided tags or unsafe metadata.
    core: flags.core === true && flags.classification === true,
  }
}

function createAuthoritativeRecommendationLoader(
  pool,
  {
    createLocalSource = defaultCreateLocalSource,
    createCandidateProvider = defaultCreateCandidateProvider,
  } = {},
) {
  const source = createLocalSource({ pool })
  return createCandidateProvider({
    loadRawCandidates: source.loadRawCandidates,
    loadBlockEdges: source.loadBlockEdges,
  })
}

function registerAuthoritativeRecommendationRoutes(
  app,
  {
    pool,
    authRequired,
    getCurrentUser,
    userIdentity,
    registerRoutes = defaultRegisterRoutes,
    algorithmFeatureFlags = defaultAlgorithmFeatureFlags,
    createLoader = createAuthoritativeRecommendationLoader,
  } = {},
) {
  if (!pool || typeof pool.query !== 'function') throw new TypeError('pool.query is required')
  if (typeof registerRoutes !== 'function' || typeof createLoader !== 'function') {
    throw new TypeError('recommendation route dependencies are required')
  }
  return registerRoutes(app, {
    pool,
    authRequired,
    getCurrentUser,
    userIdentity,
    loadCandidates: createLoader(pool),
    algorithmFeatureFlags: () => recommendationFeatureFlags(algorithmFeatureFlags),
  })
}

module.exports = {
  createAuthoritativeRecommendationLoader,
  recommendationFeatureFlags,
  registerAuthoritativeRecommendationRoutes,
}
