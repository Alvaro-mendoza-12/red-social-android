const assert = require('assert/strict')
const fs = require('fs')
const path = require('path')
const {
  CREATOR_ANALYTICS_FLAG,
  CREATOR_ANALYTICS_HASH_SECRET,
  CREATOR_ANALYTICS_MIN_COHORT,
  creatorAnalyticsConfig,
  liveReportBody,
  loadCreatorAnalyticsContent,
  loadLiveAnalytics,
  loadVideoAnalytics,
  normalizeServerEvent,
  recordCreatorAnalyticsEvent,
  registerCreatorAnalyticsRoutes,
  videoReportBody,
} = require('../src/creator-analytics-routes')

const VIDEO_ID = '11111111-1111-4111-8111-111111111111'
const LIVE_ID = '22222222-2222-4222-8222-222222222222'
const SECRET = 'creator-analytics-test-secret-32-bytes-minimum'
const RANGE = {
  from: '2026-08-01T00:00:00.000Z',
  to: '2026-08-08T00:00:00.000Z',
}

function withEnvironment(values, callback) {
  const names = [CREATOR_ANALYTICS_FLAG, CREATOR_ANALYTICS_MIN_COHORT, CREATOR_ANALYTICS_HASH_SECRET]
  const previous = new Map(names.map((name) => [name, process.env[name]]))
  try {
    for (const name of names) delete process.env[name]
    for (const [name, value] of Object.entries(values)) process.env[name] = value
    return callback()
  } finally {
    for (const [name, value] of previous) {
      if (value === undefined) delete process.env[name]
      else process.env[name] = value
    }
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
      state.headers[String(name).toLowerCase()] = String(value)
    },
  }
}

function buildRoutes(overrides = {}) {
  const routes = []
  const app = {
    get(route, ...handlers) {
      routes.push({ route, handlers })
    },
  }
  const pool = { query: async () => ({ rows: [] }) }
  registerCreatorAnalyticsRoutes(app, {
    pool,
    authRequired: (_req, _res, next) => next(),
    getCurrentUser: async () => ({ id: 'creator-1' }),
    userIdentity: () => ({ displayUserId: 'creator-1', ids: new Set(['creator-1']) }),
    config: () => ({ enabled: true, minimumCohort: 10, hashSecret: SECRET }),
    resolveOwner: async () => 'creator-1',
    loadVideo: async (_pool, input) => ({ ok: true, contentType: 'video', ...input }),
    loadLive: async (_pool, input) => ({ ok: true, contentType: 'live', ...input }),
    loadContent: async (_pool, _identity, input) => ({ ok: true, items: [], ...input }),
    now: () => Date.parse('2026-08-08T12:00:00.000Z'),
    ...overrides,
  })
  assert.deepEqual(routes.map((route) => route.route), [
    '/v1/data/creator/analytics/video/:contentId',
    '/v1/data/creator/analytics/live/:contentId',
    '/v1/data/creator/analytics/content',
  ])
  return routes
}

async function invoke(route, { contentId = VIDEO_ID, query = {} } = {}) {
  const response = createResponse()
  let forwarded = null
  await route.handlers[1]({ params: { contentId }, query }, response, (error) => { forwarded = error })
  return { ...response.state, forwarded }
}

function testFeatureFlagFailsClosedAndCohortIsBounded() {
  withEnvironment({}, () => {
    assert.deepEqual(creatorAnalyticsConfig(), {
      enabled: false,
      minimumCohort: 10,
      hashSecret: '',
    })
  })
  withEnvironment({
    [CREATOR_ANALYTICS_FLAG]: 'true',
    [CREATOR_ANALYTICS_MIN_COHORT]: '1',
    [CREATOR_ANALYTICS_HASH_SECRET]: SECRET,
  }, () => {
    assert.deepEqual(creatorAnalyticsConfig(), {
      enabled: true,
      minimumCohort: 5,
      hashSecret: SECRET,
    })
  })
}

function testEventValidationAndPseudonymization() {
  const normalized = normalizeServerEvent({
    contentId: VIDEO_ID,
    eventType: 'video_watch',
    idempotencyKey: 'view-event-private-id',
    actorId: 'viewer@example.test',
    sessionId: 'device-session-private-id',
    countryCode: 'pe',
    watchMs: 7_500,
    durationMs: 10_000,
    occurredAt: '2026-08-08T10:00:00.000Z',
  }, { hashSecret: SECRET, now: Date.parse('2026-08-08T10:01:00.000Z') })
  assert.equal(normalized.error, undefined)
  assert.equal(normalized.value.contentType, 'video')
  assert.equal(normalized.value.countryCode, 'PE')
  assert.match(normalized.value.actorHash, /^[0-9a-f]{64}$/)
  assert.match(normalized.value.sessionHash, /^[0-9a-f]{64}$/)
  assert.match(normalized.value.idempotencyHash, /^[0-9a-f]{64}$/)
  assert.doesNotMatch(JSON.stringify(normalized.value), /viewer@example|device-session|view-event-private/)

  assert.equal(normalizeServerEvent({
    contentId: VIDEO_ID,
    eventType: 'video_watch',
    idempotencyKey: 'missing-watch',
    actorId: 'viewer-1',
  }, { hashSecret: SECRET }).error, 'CREATOR_ANALYTICS_WATCH_INVALID')
  assert.equal(normalizeServerEvent({
    contentId: LIVE_ID,
    eventType: 'live_presence',
    idempotencyKey: 'missing-presence',
  }, { hashSecret: SECRET }).error, 'CREATOR_ANALYTICS_PRESENCE_INVALID')
  assert.equal(normalizeServerEvent({
    contentId: LIVE_ID,
    eventType: 'live_gift',
    idempotencyKey: 'free-gift',
    actorId: 'viewer-1',
    valueCoins: 0,
  }, { hashSecret: SECRET }).error, 'CREATOR_ANALYTICS_GIFT_VALUE_INVALID')
}

async function testIngestionIsOwnerDerivedAndIdempotent() {
  const statements = []
  let inserted = false
  const pool = {
    async query(statement, parameters) {
      statements.push({ statement: String(statement), parameters })
      if (/FROM fallback_videos/.test(statement)) return { rows: [{ owner_id: 'creator-1' }] }
      if (/INSERT INTO fallback_creator_analytics_events/.test(statement)) {
        if (inserted) return { rows: [] }
        inserted = true
        return { rows: [{ id: '33333333-3333-4333-8333-333333333333' }] }
      }
      throw new Error('unexpected query')
    },
  }
  const event = {
    contentId: VIDEO_ID,
    eventType: 'video_view',
    idempotencyKey: 'canonical-private-event-id',
    actorId: 'viewer-private-id',
    sessionId: 'session-private-id',
    countryCode: 'PE',
    occurredAt: '2026-08-08T10:00:00.000Z',
  }
  const options = {
    config: { enabled: true, minimumCohort: 10, hashSecret: SECRET },
    now: () => Date.parse('2026-08-08T10:01:00.000Z'),
  }
  assert.deepEqual(await recordCreatorAnalyticsEvent(pool, event, options), {
    accepted: true,
    duplicate: false,
    reason: null,
  })
  assert.deepEqual(await recordCreatorAnalyticsEvent(pool, event, options), {
    accepted: false,
    duplicate: true,
    reason: 'DUPLICATE',
  })
  const insertCalls = statements.filter(({ statement }) => /INSERT INTO fallback_creator_analytics_events/.test(statement))
  assert.equal(insertCalls.length, 2)
  assert.equal(insertCalls[0].parameters[1], 'video')
  assert.equal(insertCalls[0].parameters[0], insertCalls[1].parameters[0])
  const allParameters = JSON.stringify(statements.map(({ parameters }) => parameters))
  assert.doesNotMatch(allParameters, /canonical-private-event-id|viewer-private-id|session-private-id/)

  const disabled = await recordCreatorAnalyticsEvent(pool, event, {
    config: { enabled: false, minimumCohort: 10, hashSecret: '' },
  })
  assert.deepEqual(disabled, { accepted: false, duplicate: false, reason: 'FEATURE_DISABLED' })
}

function testVideoPrivacyAndMetricContract() {
  const report = videoReportBody({
    contentId: VIDEO_ID,
    range: RANGE,
    minimumCohort: 10,
    summary: {
      views: '40', unique_viewers: '14', watch_ms: '840000', average_watch_ms: '60000',
      retention_25: '92.50', retention_50: '80.00', retention_75: '65.00', retention_100: '40.00',
      likes: '8', comments: '3', shares: '2', saves: '4', follows: '1',
    },
    countries: [{ country_code: 'PE', unique_viewers: '12', views: '30', actor_hash: 'must-not-leak' }],
    timeline: [{ bucket: '2026-08-07T10:00:00.000Z', views: '15', unique_viewers: '11', interactions: '5' }],
  })
  assert.equal(report.totals.views, 40)
  assert.equal(report.totals.uniqueViewers, 14)
  assert.equal(report.totals.watchMinutes, 14)
  assert.equal(report.totals.averageWatchSeconds, 60)
  assert.deepEqual(report.totals.retention, {
    percent25: 92.5,
    percent50: 80,
    percent75: 65,
    percent100: 40,
  })
  assert.deepEqual(report.totals.interactions, { likes: 8, comments: 3, shares: 2, saves: 4, follows: 1 })
  assert.deepEqual(report.countries, [{ countryCode: 'PE', uniqueViewers: 12, views: 30 }])
  assert.doesNotMatch(JSON.stringify(report), /actor_hash|must-not-leak/)

  const suppressed = videoReportBody({
    contentId: VIDEO_ID,
    range: RANGE,
    minimumCohort: 10,
    summary: { views: 4, unique_viewers: 4, watch_ms: 1000, average_watch_ms: 250 },
  })
  assert.equal(suppressed.totals.views, 4)
  assert.equal(suppressed.totals.uniqueViewers, null)
  assert.equal(suppressed.totals.watchMinutes, null)
  assert.equal(suppressed.totals.averageWatchSeconds, null)
  assert.equal(suppressed.privacy.detailedMetricsAvailable, false)
}

function testLivePrivacyAndMetricContract() {
  const donorRows = Array.from({ length: 10 }, (_, index) => ({
    actor_hash: `private-${index}`,
    gifts: String(10 - index),
    value_coins: String((10 - index) * 70),
    donor_cohort: '12',
  }))
  const report = liveReportBody({
    contentId: LIVE_ID,
    range: RANGE,
    minimumCohort: 10,
    summary: {
      peak_concurrent: '30', unique_viewers: '20', average_concurrent: '14.5', viewer_watch_ms: '1800000',
      follows: '3', comments: '40', likes: '200', gifts: '55', gift_value_coins: '3850',
    },
    donors: donorRows,
    curve: [{ bucket: '2026-08-07T10:00:00.000Z', concurrent: '16' }],
  })
  assert.deepEqual(report.totals, {
    peakConcurrent: 30,
    uniqueViewers: 20,
    averageConcurrent: 14.5,
    viewerMinutes: 30,
    follows: 3,
    comments: 40,
    likes: 200,
    gifts: 55,
    giftValueCoins: 3850,
  })
  assert.deepEqual(report.topDonors[0], { rank: 1, gifts: 10, valueCoins: 700 })
  assert.doesNotMatch(JSON.stringify(report), /actor_hash|private-/)

  const suppressed = liveReportBody({
    contentId: LIVE_ID,
    range: RANGE,
    minimumCohort: 10,
    summary: { unique_viewers: 3, peak_concurrent: 3, gifts: 1, gift_value_coins: 70 },
    donors: [{ actor_hash: 'private', gifts: 1, value_coins: 70, donor_cohort: 1 }],
    curve: [{ bucket: '2026-08-07T10:00:00.000Z', concurrent: 3 }],
  })
  assert.equal(suppressed.totals.uniqueViewers, null)
  assert.equal(suppressed.totals.giftValueCoins, 70)
  assert.deepEqual(suppressed.topDonors, [])
  assert.deepEqual(suppressed.viewerCurve, [])
}

async function testQueriesEnforceCohortsAndExposeAllMetrics() {
  const statements = []
  const pool = {
    async query(statement, parameters) {
      const sql = String(statement)
      statements.push({ sql, parameters })
      if (sql.includes('video_canonical_summary')) return { rows: [{ views: 7, unique_viewers: 6, likes: 2, comments: 1, shares: 1, saves: 1 }] }
      if (sql.includes('video_summary')) return { rows: [{ views: 0, unique_viewers: 0 }] }
      if (sql.includes('video_countries') || sql.includes('video_timeline')) return { rows: [] }
      if (sql.includes('live_canonical_summary')) return { rows: [{ peak_concurrent: 9, unique_viewers: 7, comments: 4, likes: 20, gifts: 3, gift_value_coins: 210 }] }
      if (sql.includes('live_summary')) return { rows: [{ unique_viewers: 0 }] }
      if (sql.includes('live_top_donors') || sql.includes('live_viewer_curve')) return { rows: [] }
      if (sql.includes('live_canonical_top_donors')) return { rows: [] }
      throw new Error('unexpected analytics query')
    },
  }
  await loadVideoAnalytics(pool, { contentId: VIDEO_ID, range: RANGE, minimumCohort: 10 })
  await loadLiveAnalytics(pool, { contentId: LIVE_ID, range: RANGE, minimumCohort: 10 })
  const sql = statements.map((item) => item.sql).join('\n')
  assert.match(sql, /retention_25/)
  assert.match(sql, /retention_50/)
  assert.match(sql, /retention_75/)
  assert.match(sql, /retention_100/)
  assert.match(sql, /video_like/)
  assert.match(sql, /video_follow/)
  assert.match(sql, /country_code/)
  assert.match(sql, /peak_concurrent/)
  assert.match(sql, /average_concurrent/)
  assert.match(sql, /live_gift/)
  assert.match(sql, /live_top_donors/)
  assert.match(sql, /live_viewer_curve/)
  assert.match(sql, /video_canonical_summary/)
  assert.match(sql, /fallback_video_views/)
  assert.match(sql, /fallback_video_likes/)
  assert.match(sql, /fallback_video_comments/)
  assert.match(sql, /fallback_shares/)
  assert.match(sql, /live_canonical_summary/)
  assert.match(sql, /fallback_live_participants/)
  assert.match(sql, /fallback_compat_rows/)
  assert.match(sql, /HAVING COUNT\(DISTINCT actor_hash\) >= \$4/)
  assert.match(sql, /HAVING MAX\(concurrent_count\) >= \$4/)
}

async function testOwnedContentCatalogUsesAuthenticatedAliasesOnly() {
  const statements = []
  const pool = {
    async query(statement, parameters) {
      const sql = String(statement)
      statements.push({ sql, parameters })
      if (sql.includes('owned_videos')) {
        return {
          rows: [{
            content_id: VIDEO_ID,
            title: 'Video propio',
            duration_seconds: 42,
            is_public: true,
            created_at: '2026-08-08T10:00:00.000Z',
          }],
        }
      }
      if (sql.includes('owned_lives')) {
        return {
          rows: [{
            content_id: LIVE_ID,
            title: 'Directo propio',
            source: 'audio',
            is_active: false,
            ended_at: '2026-08-08T09:30:00.000Z',
            started_at: '2026-08-08T09:00:00.000Z',
          }],
        }
      }
      throw new Error('unexpected catalog query')
    },
  }
  const report = await loadCreatorAnalyticsContent(pool, {
    ids: new Set(['CREATOR-LOCAL', 'CREATOR-PUBLIC']),
    displayUserId: 'creator-public',
  })
  assert.deepEqual(statements[0].parameters[0], ['creator-local', 'creator-public'])
  assert.deepEqual(statements[1].parameters[0], ['creator-local', 'creator-public'])
  assert.equal(report.items.length, 2)
  assert.deepEqual(report.items.map((item) => item.contentType), ['video', 'live'])
  assert.equal(report.items[0].status, 'published')
  assert.equal(report.items[1].status, 'ended')
  assert.doesNotMatch(JSON.stringify(report), /creator-local|creator-public/)
}

async function testOwnerOnlyRoutesAndDisabledGate() {
  let loaderCalls = 0
  const disabledRoutes = buildRoutes({
    config: () => ({ enabled: false, minimumCohort: 10, hashSecret: '' }),
    resolveOwner: async () => { throw new Error('must not resolve while disabled') },
  })
  const disabled = await invoke(disabledRoutes[0])
  assert.equal(disabled.statusCode, 404)
  assert.deepEqual(disabled.body, { error: 'CREATOR_ANALYTICS_DISABLED' })

  const deniedRoutes = buildRoutes({
    resolveOwner: async () => 'another-creator',
    loadVideo: async () => {
      loaderCalls += 1
      return {}
    },
  })
  const denied = await invoke(deniedRoutes[0])
  assert.equal(denied.statusCode, 403)
  assert.deepEqual(denied.body, { error: 'CREATOR_ANALYTICS_OWNER_REQUIRED' })
  assert.equal(loaderCalls, 0)

  const routes = buildRoutes()
  const success = await invoke(routes[0], {
    query: { from: '2026-08-01T00:00:00.000Z', to: '2026-08-08T00:00:00.000Z' },
  })
  assert.equal(success.statusCode, 200)
  assert.equal(success.body.ok, true)
  assert.equal(success.body.contentType, 'video')
  assert.equal(success.body.minimumCohort, 10)
  assert.equal(success.headers['cache-control'], 'private, no-store')

  const content = await invoke(routes[2])
  assert.equal(content.statusCode, 200)
  assert.equal(content.body.ok, true)
  assert.deepEqual(content.body.items, [])
  assert.equal(content.headers['cache-control'], 'private, no-store')
}

function testMigrationIsIdempotentAndPrivacyPreserving() {
  const migration = fs.readFileSync(
    path.join(__dirname, '../../db/migrations/008_creator_analytics_v1.sql'),
    'utf8',
  )
  assert.match(migration, /CREATE TABLE IF NOT EXISTS fallback_creator_analytics_events/)
  assert.match(migration, /idempotency_hash CHAR\(64\) NOT NULL UNIQUE/)
  assert.match(migration, /actor_hash CHAR\(64\)/)
  assert.match(migration, /session_hash CHAR\(64\)/)
  assert.doesNotMatch(migration, /actor_id|viewer_id|donor_id|owner_id|purchase_token/i)
  assert.match(migration, /CREATE INDEX IF NOT EXISTS idx_creator_analytics_content_time/)
  assert.match(migration, /video_watch/)
  assert.match(migration, /live_presence/)
  assert.match(migration, /live_gift/)
}

Promise.resolve()
  .then(testFeatureFlagFailsClosedAndCohortIsBounded)
  .then(testEventValidationAndPseudonymization)
  .then(testIngestionIsOwnerDerivedAndIdempotent)
  .then(testVideoPrivacyAndMetricContract)
  .then(testLivePrivacyAndMetricContract)
  .then(testQueriesEnforceCohortsAndExposeAllMetrics)
  .then(testOwnedContentCatalogUsesAuthenticatedAliasesOnly)
  .then(testOwnerOnlyRoutesAndDisabledGate)
  .then(testMigrationIsIdempotentAndPrivacyPreserving)
  .then(() => console.log('creator analytics routes tests passed'))
  .catch((error) => {
    console.error(error)
    process.exitCode = 1
  })
