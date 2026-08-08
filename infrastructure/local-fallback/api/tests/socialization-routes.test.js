const assert = require('assert/strict')
const fs = require('fs')
const path = require('path')
const {
  SOCIALIZATION_FLAGS,
  booleanPreferencePatch,
  capabilitiesResponse,
  createSocializationRateLimiters,
  defaultPreferences,
  ensureSocializationTables,
  expectedRevision,
  mutationBody,
  registerSocializationRoutes,
  socializationFeatureFlags,
  updateOwnedSocializationPreferences,
} = require('../src/socialization-routes')

function withFlags(values, callback) {
  const previous = new Map(Object.values(SOCIALIZATION_FLAGS).map((name) => [name, process.env[name]]))
  try {
    for (const name of Object.values(SOCIALIZATION_FLAGS)) delete process.env[name]
    for (const [name, value] of Object.entries(values)) process.env[name] = value
    return callback()
  } finally {
    for (const [name, value] of previous) {
      if (value === undefined) delete process.env[name]
      else process.env[name] = value
    }
  }
}

function testFlagsFailClosed() {
  withFlags({}, () => {
    const flags = socializationFeatureFlags()
    assert.deepEqual(Object.values(flags), [false, false, false, false, false, false])
  })
  withFlags({
    SOCIALIZATION_ENABLED: 'true',
    SOCIALIZATION_DISCOVERY_ENABLED: 'yes',
    SOCIALIZATION_CONNECTION_REQUESTS_ENABLED: '1',
  }, () => {
    const flags = socializationFeatureFlags()
    assert.equal(flags.core, true)
    assert.equal(flags.discovery, true)
    assert.equal(flags.connectionRequests, true)
    assert.equal(flags.socialRooms, false)
  })
}

function testExplicitOptInAndSensitiveInputRejection() {
  const defaults = defaultPreferences()
  const patch = booleanPreferencePatch({ discoveryOptIn: true, connectionRequests: true }, defaults)
  assert.ok(patch.value)
  assert.equal(patch.value.discovery_opt_in, true)
  assert.equal(patch.value.connection_requests_opt_in, true)
  assert.equal(patch.value.social_rooms_opt_in, false)
  assert.equal(booleanPreferencePatch({ discoveryOptIn: 'true' }, defaults).error, 'SOCIALIZATION_PREFERENCES_INVALID')
  assert.equal(booleanPreferencePatch({ location: true }, defaults).error, 'SOCIALIZATION_PREFERENCES_INVALID')
  assert.equal(booleanPreferencePatch({ privateMessages: true }, defaults).error, 'SOCIALIZATION_PREFERENCES_INVALID')
  assert.equal(booleanPreferencePatch({ age: true }, defaults).error, 'SOCIALIZATION_PREFERENCES_INVALID')
  assert.equal(booleanPreferencePatch({ gender: true }, defaults).error, 'SOCIALIZATION_PREFERENCES_INVALID')
}

function testCapabilitiesNeedBothFlagAndConsent() {
  const preferences = {
    ...defaultPreferences(),
    discovery_opt_in: true,
    connection_requests_opt_in: true,
    revision: 3,
  }
  withFlags({}, () => {
    const response = capabilitiesResponse(preferences)
    assert.equal(response.enabled, false)
    assert.equal(response.features.discovery, false)
    assert.equal(response.features.connectionRequests, false)
    assert.equal(response.safeguards.privateMessagesUsed, false)
    assert.equal(response.safeguards.preciseLocationUsed, false)
    assert.equal(response.safeguards.randomChatEnabled, false)
  })
  withFlags({
    SOCIALIZATION_ENABLED: 'true',
    SOCIALIZATION_DISCOVERY_ENABLED: 'true',
    SOCIALIZATION_CONNECTION_REQUESTS_ENABLED: 'true',
  }, () => {
    const response = capabilitiesResponse(preferences)
    assert.equal(response.features.discovery, true)
    assert.equal(response.features.connectionRequests, true)
    assert.equal(response.features.communityDiscovery, false)
  })
}

function testVersionedMutationShape() {
  assert.equal(expectedRevision({ expectedVersion: 0 }), 0)
  assert.equal(expectedRevision({ expectedVersion: -1 }), null)
  assert.equal(expectedRevision({ expectedVersion: '0' }), null)
  assert.equal(mutationBody({ discoveryOptIn: true }).error, 'SOCIALIZATION_VERSION_REQUIRED')
  assert.equal(mutationBody({ expectedVersion: 0, preferences: { discoveryOptIn: true }, extra: true }).error, 'SOCIALIZATION_PREFERENCES_INVALID')
  const mutation = mutationBody({ expectedVersion: 0, preferences: { discoveryOptIn: true } })
  assert.deepEqual(mutation.value, { expectedVersion: 0, patch: { discoveryOptIn: true } })
}

function testRateLimit() {
  const limiters = createSocializationRateLimiters()
  let last
  for (let index = 0; index < 21; index += 1) last = limiters.preferencesWrite('one-user')
  assert.equal(last.allowed, false)
  assert.equal(last.retryAfterSeconds > 0, true)
}

async function testOwnedVersionedWrite() {
  let row = null
  const queries = []
  const client = {
    async query(statement, values = []) {
      const sql = String(statement).replace(/\s+/g, ' ').trim()
      queries.push({ sql, values })
      if (sql === 'BEGIN' || sql === 'COMMIT' || sql === 'ROLLBACK') return { rows: [] }
      if (sql.startsWith('SELECT pg_advisory_xact_lock')) return { rows: [] }
      if (sql.startsWith('SELECT user_id,') && sql.includes('FOR UPDATE')) return { rows: row ? [row] : [] }
      if (sql.startsWith('INSERT INTO fallback_user_socialization_preferences')) {
        row = {
          user_id: values[0],
          discovery_opt_in: values[1],
          connection_requests_opt_in: values[2],
          community_discovery_opt_in: values[3],
          icebreakers_opt_in: values[4],
          social_rooms_opt_in: values[5],
          revision: 1,
          updated_at: '2026-08-07T00:00:00.000Z',
        }
        return { rows: [row] }
      }
      if (sql.startsWith('UPDATE fallback_user_socialization_preferences')) {
        row = {
          ...row,
          discovery_opt_in: values[1],
          connection_requests_opt_in: values[2],
          community_discovery_opt_in: values[3],
          icebreakers_opt_in: values[4],
          social_rooms_opt_in: values[5],
          revision: Number(row.revision) + 1,
          updated_at: '2026-08-07T00:01:00.000Z',
        }
        return { rows: [row] }
      }
      throw new Error(`Unexpected query: ${sql}`)
    },
    release() {},
  }
  const pool = { connect: async () => client }
  const identity = { ids: new Set(['local-user', 'canonical-user']), displayUserId: 'canonical-user' }
  const created = await updateOwnedSocializationPreferences(pool, {
    identity,
    expectedVersion: 0,
    patch: { discoveryOptIn: true },
  })
  assert.equal(created.preferences.user_id, 'canonical-user')
  assert.equal(created.preferences.revision, 1)
  const advisoryLock = queries.find((query) => query.sql.startsWith('SELECT pg_advisory_xact_lock'))
  assert.ok(advisoryLock)
  assert.deepEqual(advisoryLock.values, ['canonical-user'])
  const select = queries.find((query) => query.sql.includes('FOR UPDATE'))
  assert.deepEqual(select.values[0], ['local-user', 'canonical-user'])
  const conflict = await updateOwnedSocializationPreferences(pool, {
    identity,
    expectedVersion: 0,
    patch: { icebreakers: true },
  })
  assert.deepEqual(conflict, { error: 'SOCIALIZATION_PREFERENCES_CONFLICT', currentRevision: 1 })
  const updated = await updateOwnedSocializationPreferences(pool, {
    identity,
    expectedVersion: 1,
    patch: { icebreakers: true },
  })
  assert.equal(updated.preferences.revision, 2)
  assert.equal(updated.preferences.discovery_opt_in, true)
  assert.equal(updated.preferences.icebreakers_opt_in, true)
}

async function testFallbackSchema() {
  const statements = []
  const pool = {
    async query(statement) {
      statements.push(String(statement).replace(/\s+/g, ' ').trim())
      return { rows: [] }
    },
  }
  await ensureSocializationTables(pool)
  const schema = statements.join('\n')
  assert.match(schema, /CREATE TABLE IF NOT EXISTS fallback_user_socialization_preferences/)
  assert.match(schema, /revision BIGINT NOT NULL DEFAULT 1 CHECK \(revision > 0\)/)
  assert.doesNotMatch(schema, /random_chat|latitude|longitude|message_content/i)
}

function testRouteRegistration() {
  const routes = []
  const app = {}
  for (const method of ['get', 'put']) {
    app[method] = (route, ...handlers) => routes.push({ method, route, handlers })
  }
  registerSocializationRoutes(app, {
    pool: {},
    authRequired: (_req, _res, next) => next(),
    getCurrentUser: async () => null,
    userIdentity: () => null,
  })
  const names = routes.map((route) => `${route.method.toUpperCase()} ${route.route}`)
  assert.deepEqual(names, [
    'GET /v1/data/socialization/capabilities',
    'GET /v1/data/socialization/preferences',
    'PUT /v1/data/socialization/preferences',
  ])
}

function testMigrationAndConfigContract() {
  const root = path.resolve(__dirname, '../../../..')
  const migration = fs.readFileSync(path.join(root, 'sql/migrations/20260807_socialization_v1.sql'), 'utf8')
  const fallbackMigration = fs.readFileSync(
    path.join(root, 'infrastructure/local-fallback/db/migrations/006_socialization_v1.sql'),
    'utf8',
  )
  assert.match(migration, /CREATE TABLE IF NOT EXISTS public\.user_socialization_preferences/)
  assert.match(migration, /ENABLE ROW LEVEL SECURITY/)
  assert.match(migration, /REVOKE ALL ON TABLE public\.user_socialization_preferences/)
  assert.match(migration, /GRANT ALL ON TABLE public\.user_socialization_preferences TO service_role/)
  assert.doesNotMatch(migration, /latitude|longitude|birth_date|gender/i)
  assert.match(fallbackMigration, /CREATE TABLE IF NOT EXISTS fallback_user_socialization_preferences/)
  assert.doesNotMatch(fallbackMigration, /random_chat|latitude|longitude|message_content/i)
  const config = fs.readFileSync(path.join(root, 'infrastructure/local-fallback/config.example'), 'utf8')
  assert.match(config, /SOCIALIZATION_CARDS_ENABLED=false/)
}

Promise.resolve()
  .then(testFlagsFailClosed)
  .then(testExplicitOptInAndSensitiveInputRejection)
  .then(testCapabilitiesNeedBothFlagAndConsent)
  .then(testVersionedMutationShape)
  .then(testRateLimit)
  .then(testOwnedVersionedWrite)
  .then(testFallbackSchema)
  .then(testRouteRegistration)
  .then(testMigrationAndConfigContract)
  .then(() => console.log('socialization routes tests passed'))
  .catch((error) => {
    console.error(error)
    process.exitCode = 1
  })
