const assert = require('assert/strict')
const fs = require('fs')
const path = require('path')
const {
  ALGORITHM_FLAGS,
  algorithmCapabilitiesResponse,
  algorithmFeatureFlags,
  defaultDataPermissions,
  defaultFeedMix,
  ensureAlgorithmTables,
  normalizeContentClassification,
  normalizeDataPermissions,
  normalizeFeedMix,
  normalizePreferenceInput,
  normalizeProfileCreate,
  normalizeTemporaryPreferenceInput,
  preferenceIdentityKey,
  registerAlgorithmRoutes,
  upsertTrustedContentClassification,
} = require('../src/algorithm-routes')

function withFlags(values, callback) {
  const previous = new Map(Object.values(ALGORITHM_FLAGS).map((name) => [name, process.env[name]]))
  try {
    for (const name of Object.values(ALGORITHM_FLAGS)) delete process.env[name]
    for (const [name, value] of Object.entries(values)) process.env[name] = value
    return callback()
  } finally {
    for (const [name, value] of previous) {
      if (value === undefined) delete process.env[name]
      else process.env[name] = value
    }
  }
}

function testAlgorithmControlsAreAlwaysAvailable() {
  withFlags({}, () => {
    const flags = algorithmFeatureFlags()
    assert.equal(flags.core, true)
    assert.equal(flags.classification, true)
    assert.equal(flags.preferences, true)
    assert.equal(flags.feedMix, true)
    assert.equal(flags.dataPermissions, true)
    assert.equal(flags.explanations, true)
    assert.equal(flags.socialDiscovery, false)
    assert.equal(flags.socialRooms, false)
  })
  withFlags({
    SOCIAL_DISCOVERY_ENABLED: 'true',
    SOCIAL_ROOMS_ENABLED: 'true',
  }, () => {
    const flags = algorithmFeatureFlags()
    assert.equal(flags.core, true)
    assert.equal(flags.preferences, true)
    assert.equal(flags.feedMix, true)
    assert.equal(flags.dataPermissions, true)
    assert.equal(flags.classification, true)
    assert.equal(flags.socialDiscovery, true)
    assert.equal(flags.socialRooms, true)
  })
}

function testCapabilitiesNeverAdvertisePartialRollout() {
  withFlags({
    SOCIAL_DISCOVERY_ENABLED: 'true',
    SOCIAL_ROOMS_ENABLED: 'true',
  }, () => {
    const response = algorithmCapabilitiesResponse()
    assert.equal(response.enabled, true)
    assert.equal(response.capabilities.classification, true)
    assert.equal(response.capabilities.preferences, true)
    assert.equal(response.capabilities.feedMix, true)
    assert.equal(response.capabilities.dataPermissions, true)
    assert.equal(response.capabilities.explanations, true)
    assert.equal(response.capabilities.socialDiscovery, true)
    assert.equal(response.capabilities.socialRooms, true)
  })
}

function testProfileCreateRejectsInvalidNameWithoutThrowing() {
  assert.equal(normalizeProfileCreate({ name: 42 }).error, 'ALGORITHM_PROFILE_INVALID')
  assert.equal(normalizeProfileCreate({ name: '' }).error, 'ALGORITHM_PROFILE_INVALID')
  const normalized = normalizeProfileCreate({ name: 'Para videos' })
  assert.equal(normalized.value.slug, 'para-videos')
}

function testExplicitDimensionsRemainSeparate() {
  const creator = normalizePreferenceInput({
    surface: 'video',
    dimension: 'creator',
    subjectKey: 'carlos',
    subjectLabel: 'Carlos',
    score: 90,
  })
  const dolphins = normalizePreferenceInput({
    surface: 'video',
    dimension: 'topic',
    subjectKey: 'delfines',
    subjectLabel: 'Delfines',
    score: -100,
  })
  assert.ok(creator.value)
  assert.ok(dolphins.value)
  assert.equal(creator.value.dimension, 'creator')
  assert.equal(dolphins.value.dimension, 'topic')
  assert.notEqual(preferenceIdentityKey(creator.value), preferenceIdentityKey(dolphins.value))
  assert.equal(creator.value.score, 90)
  assert.equal(dolphins.value.score, -100)
  assert.equal(dolphins.value.level, 'blocked')
  assert.equal(normalizePreferenceInput({ ...creator.value, score: 101 }).error, 'PREFERENCE_SCORE_INVALID')
  assert.equal(normalizePreferenceInput({ ...creator.value, score: '90' }).error, 'PREFERENCE_SCORE_INVALID')
  assert.equal(normalizePreferenceInput({ ...creator.value, subjectLabel: { invalid: true } }).error, 'PREFERENCE_INVALID')
}

function testFeedMixValidation() {
  const video = defaultFeedMix('video')
  assert.equal(Object.values(video).reduce((sum, value) => sum + value, 0), 100)
  const changed = normalizeFeedMix({ following: 40, interests: 35, trends: 5, exploration: 20 }, {
    ...video,
    community_weight: 0,
    new_content_weight: 0,
  })
  assert.ok(changed.value)
  assert.equal(changed.value.following_weight, 40)
  assert.equal(changed.value.interest_weight, 35)
  assert.equal(normalizeFeedMix({ following: 99 }, video).error, 'FEED_MIX_TOTAL_INVALID')
  assert.equal(normalizeFeedMix({ privateMessages: 0 }, video).error, 'FEED_MIX_INVALID')
}

function testDataPermissionsAreAllowlisted() {
  const defaults = defaultDataPermissions()
  assert.deepEqual(Object.values(defaults), new Array(Object.keys(defaults).length).fill(false))
  const accepted = normalizeDataPermissions({ likes: true, watchTime: true }, defaults)
  assert.ok(accepted.value)
  assert.equal(accepted.value.use_likes, true)
  assert.equal(accepted.value.use_watch_time, true)
  assert.equal(accepted.value.use_follows, false)
  assert.equal(normalizeDataPermissions({ privateMessages: true }, defaults).error, 'DATA_PERMISSIONS_INVALID')
  assert.equal(normalizeDataPermissions({ location: true }, defaults).error, 'DATA_PERMISSIONS_INVALID')
  assert.equal(normalizeDataPermissions({ age: true }, defaults).error, 'DATA_PERMISSIONS_INVALID')
  assert.equal(normalizeDataPermissions({ gender: true }, defaults).error, 'DATA_PERMISSIONS_INVALID')
}

function testTemporaryPreferenceValidation() {
  const now = new Date('2026-08-07T12:00:00.000Z')
  const valid = normalizeTemporaryPreferenceInput({
    surface: 'video',
    dimension: 'topic',
    subjectKey: 'java',
    score: 100,
    expiresAt: '2026-08-07T13:00:00.000Z',
  }, now)
  assert.ok(valid.value)
  assert.equal(valid.value.startsAt, now.toISOString())
  assert.equal(normalizeTemporaryPreferenceInput({
    surface: 'video', dimension: 'topic', subjectKey: 'java', score: 100,
    expiresAt: '2026-08-07T11:00:00.000Z',
  }, now).error, 'TEMPORARY_PREFERENCE_EXPIRY_INVALID')
  assert.equal(normalizeTemporaryPreferenceInput({
    surface: 'video', dimension: 'topic', subjectKey: 'java', score: 100,
    expiresAt: '2027-01-01T00:00:00.000Z',
  }, now).error, 'TEMPORARY_PREFERENCE_EXPIRY_INVALID')
}

function testTrustedClassificationValidation() {
  const classification = normalizeContentClassification({
    contentKind: 'video',
    contentId: 'video-123',
    creatorId: 'carlos',
    languageCode: 'es',
    formatKey: 'tutorial',
    topics: [
      { topicKey: 'programacion', confidence: 0.96 },
      { topicKey: 'java', subtopicKey: 'android', confidence: 0.91 },
    ],
  })
  assert.ok(classification.value)
  assert.equal(classification.value.topics.length, 2)
  assert.equal(normalizeContentClassification({
    contentKind: 'video', contentId: 'video-123', topics: [{ topicKey: 'java', confidence: 1.1 }],
  }).error, 'CLASSIFICATION_TOPICS_INVALID')
}

async function testTrustedClassificationIsAvailableWithoutRolloutFlags() {
  let queries = 0
  const classificationId = '44444444-4444-4444-8444-444444444444'
  const result = await upsertTrustedContentClassification({
    query: async () => {
      queries += 1
      return { rows: [{ id: classificationId }] }
    },
  }, {
    contentKind: 'video',
    contentId: 'video-123',
    creatorId: 'creator-123',
    safetyState: 'allowed',
    topics: [{ topicKey: 'juegos', confidence: 0.95 }],
  })
  assert.deepEqual(result, { id: classificationId })
  assert.equal(queries, 2)
}

async function testFallbackSchema() {
  const statements = []
  const pool = {
    async query(statement) {
      statements.push(String(statement).replace(/\s+/g, ' ').trim())
      return { rows: [] }
    },
  }
  await ensureAlgorithmTables(pool)
  const schema = statements.join('\n')
  assert.match(schema, /CREATE TABLE IF NOT EXISTS fallback_user_algorithm_profiles/)
  assert.match(schema, /CREATE TABLE IF NOT EXISTS fallback_user_algorithm_preferences/)
  assert.match(schema, /CREATE TABLE IF NOT EXISTS fallback_user_algorithm_feed_mixes/)
  assert.match(schema, /CREATE TABLE IF NOT EXISTS fallback_user_algorithm_data_permissions/)
  assert.match(schema, /CREATE TABLE IF NOT EXISTS fallback_user_algorithm_temporary_preferences/)
  assert.match(schema, /CREATE TABLE IF NOT EXISTS fallback_algorithm_content_classifications/)
  assert.match(schema, /CREATE TABLE IF NOT EXISTS fallback_algorithm_content_topics/)
  assert.match(schema, /score SMALLINT NOT NULL CHECK \(score BETWEEN -100 AND 100\)/)
  assert.match(schema, /following_weight \+ interest_weight \+ community_weight \+ new_content_weight \+ trend_weight \+ exploration_weight = 100/)
  assert.doesNotMatch(schema, /fallback_messages/i)
  assert.doesNotMatch(schema, /latitude|longitude|birth_date/i)
}

function testRouteRegistration() {
  const routes = []
  const app = {}
  for (const method of ['get', 'post', 'put', 'patch', 'delete']) {
    app[method] = (route, ...handlers) => routes.push({ method, route, handlers })
  }
  registerAlgorithmRoutes(app, {
    pool: {},
    authRequired: (_req, _res, next) => next(),
    getCurrentUser: async () => null,
    userIdentity: () => null,
  })
  const names = routes.map((route) => `${route.method.toUpperCase()} ${route.route}`)
  assert.ok(names.includes('GET /v1/data/algorithm/capabilities'))
  assert.ok(names.includes('GET /v1/data/algorithm/config'))
  assert.ok(names.includes('PUT /v1/data/algorithm/profiles/:profileId/preferences'))
  assert.ok(names.includes('PUT /v1/data/algorithm/profiles/:profileId/feed-mix/:surface'))
  assert.ok(names.includes('PUT /v1/data/algorithm/profiles/:profileId/data-permissions'))
  assert.ok(names.includes('POST /v1/data/algorithm/profiles/:profileId/temporary-preferences'))
}

function testDefaultProfileSwitchInvalidatesThePreviousProfileVersion() {
  const source = fs.readFileSync(path.join(__dirname, '../src/algorithm-routes.js'), 'utf8')
  assert.match(
    source,
    /SET is_default = FALSE, revision = revision \+ 1, updated_at = NOW\(\)/,
  )
}

function testMigrationContract() {
  const repositoryRoot = path.resolve(__dirname, '../../../..')
  const migration = fs.readFileSync(path.join(repositoryRoot, 'sql/migrations/20260807_user_algorithm_v1.sql'), 'utf8')
  const fallbackMigration = fs.readFileSync(path.join(repositoryRoot, 'infrastructure/local-fallback/db/migrations/005_user_algorithm_v1.sql'), 'utf8')
  const config = fs.readFileSync(path.join(repositoryRoot, 'infrastructure/local-fallback/config.example'), 'utf8')
  assert.match(migration, /CREATE TABLE IF NOT EXISTS public\.user_algorithm_profiles/)
  assert.match(migration, /CREATE TABLE IF NOT EXISTS public\.user_algorithm_preferences/)
  assert.match(migration, /CREATE TABLE IF NOT EXISTS public\.user_algorithm_feed_mix/)
  assert.match(migration, /CREATE TABLE IF NOT EXISTS public\.user_algorithm_data_permissions/)
  assert.match(migration, /CREATE TABLE IF NOT EXISTS public\.user_algorithm_temporary_preferences/)
  assert.match(migration, /CREATE TABLE IF NOT EXISTS public\.algorithm_content_classifications/)
  assert.match(migration, /ENABLE ROW LEVEL SECURITY/)
  assert.match(migration, /FROM PUBLIC, anon, authenticated/)
  assert.doesNotMatch(migration, /GRANT SELECT, INSERT, UPDATE, DELETE[\s\S]*?TO authenticated/)
  assert.doesNotMatch(config, /USER_ALGORITHM_ENABLED=/)
  assert.doesNotMatch(config, /USER_ALGORITHM_PREFERENCES_ENABLED=/)
  assert.doesNotMatch(config, /USER_ALGORITHM_FEED_MIX_ENABLED=/)
  assert.doesNotMatch(config, /USER_ALGORITHM_DATA_PERMISSIONS_ENABLED=/)
  assert.match(config, /ALGORITHM_RECOMMENDATION_CURSOR_SECRET=/)
  assert.match(fallbackMigration, /CREATE TABLE IF NOT EXISTS fallback_user_algorithm_profiles/)
  assert.match(fallbackMigration, /CREATE TABLE IF NOT EXISTS fallback_user_algorithm_preferences/)
  assert.doesNotMatch(fallbackMigration, /fallback_messages/i)
  assert.doesNotMatch(fallbackMigration, /latitude|longitude|birth_date/i)
}

Promise.resolve()
  .then(testAlgorithmControlsAreAlwaysAvailable)
  .then(testCapabilitiesNeverAdvertisePartialRollout)
  .then(testProfileCreateRejectsInvalidNameWithoutThrowing)
  .then(testExplicitDimensionsRemainSeparate)
  .then(testFeedMixValidation)
  .then(testDataPermissionsAreAllowlisted)
  .then(testTemporaryPreferenceValidation)
  .then(testTrustedClassificationValidation)
  .then(testTrustedClassificationIsAvailableWithoutRolloutFlags)
  .then(testFallbackSchema)
  .then(testRouteRegistration)
  .then(testDefaultProfileSwitchInvalidatesThePreviousProfileVersion)
  .then(testMigrationContract)
  .then(() => console.log('algorithm routes tests passed'))
  .catch((error) => {
    console.error(error)
    process.exitCode = 1
  })
