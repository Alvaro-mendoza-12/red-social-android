'use strict'

const { registerSocializationCardRoutes } = require('./socialization-cards-routes')
const { createSocializationCardCandidateProvider } = require('./socialization-safe-candidate-provider')
const { createSocializationLocalFallbackSource } = require('./socialization-local-fallback-source')
const { loadSocializationPreferences } = require('./socialization-routes')

function createSocializationCardsLoader(pool, {
  createSource = createSocializationLocalFallbackSource,
  createProvider = createSocializationCardCandidateProvider,
} = {}) {
  const source = createSource({ pool })
  return createProvider({
    loadCandidates: source.loadCandidates,
    evaluateCandidateSafety: source.evaluateCandidateSafety,
  })
}

function registerSocializationCards(app, {
  pool,
  authRequired,
  getCurrentUser,
  userIdentity,
  registerRoutes = registerSocializationCardRoutes,
  createLoader = createSocializationCardsLoader,
} = {}) {
  if (!pool || typeof pool.query !== 'function') throw new TypeError('pool.query is required')
  return registerRoutes(app, {
    pool,
    authRequired,
    getCurrentUser,
    userIdentity,
    loadPreferences: ({ pool: database, identity }) => loadSocializationPreferences(database, identity),
    loadCandidates: createLoader(pool),
  })
}

module.exports = {
  createSocializationCardsLoader,
  registerSocializationCards,
}
