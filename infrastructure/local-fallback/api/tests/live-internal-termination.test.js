const assert = require('assert')
const { registerLiveRoutes } = require('../src/live-routes')

const liveId = '11111111-1111-4111-8111-111111111111'

function responseCapture() {
  return {
    statusCode: 200,
    body: null,
    headers: {},
    status(value) { this.statusCode = value; return this },
    setHeader(name, value) { this.headers[String(name).toLowerCase()] = value },
    json(value) { this.body = value; return this },
  }
}

function routeFixture(pool) {
  let handler = null
  const app = {
    get() {}, patch() {}, put() {}, delete() {},
    post(path, ...handlers) {
      if (path === '/v1/internal/live/:liveId/terminate') handler = handlers.at(-1)
    },
  }
  registerLiveRoutes(app, {
    pool,
    authRequired: (_req, _res, next) => next(),
    getCurrentUser: async () => null,
    userIdentity: () => ({ ids: new Set(), values: [], displayUserId: '' }),
  })
  assert.ok(handler)
  return handler
}

async function withInternalToken(value, action) {
  const previous = process.env.LIVE_INTERNAL_EVENTS_TOKEN
  process.env.LIVE_INTERNAL_EVENTS_TOKEN = value
  try {
    await action()
  } finally {
    if (previous === undefined) delete process.env.LIVE_INTERNAL_EVENTS_TOKEN
    else process.env.LIVE_INTERNAL_EVENTS_TOKEN = previous
  }
}

async function testRejectsMissingServiceSecretWithoutDatabaseAccess() {
  await withInternalToken('service-secret', async () => {
    let queries = 0
    const handler = routeFixture({ query: async () => { queries += 1; return { rows: [] } } })
    const response = responseCapture()
    await handler({ headers: {}, params: { liveId }, body: {} }, response, (error) => { throw error })
    assert.strictEqual(response.statusCode, 404)
    assert.deepStrictEqual(response.body, { error: 'NOT_FOUND' })
    assert.strictEqual(queries, 0)
  })
}

async function testTerminatesIdempotentlyWithInternalSecret() {
  await withInternalToken('service-secret', async () => {
    const statements = []
    const pool = {
      async query(statement, values) {
        statements.push({ statement: String(statement), values })
        return {
          rows: [{
            id: liveId,
            host_user_id: 'host-1',
            is_active: false,
            viewer_count: 0,
            total_reactions: 17,
            ended_at: '2026-08-25T00:00:00.000Z',
            was_active: false,
          }],
        }
      },
    }
    const handler = routeFixture(pool)
    const response = responseCapture()
    await handler({
      headers: { 'x-live-internal-token': 'service-secret' },
      params: { liveId },
      body: { reason: 'host-lease-expired', totalReactions: 17 },
    }, response, (error) => { throw error })
    assert.strictEqual(response.statusCode, 200)
    assert.strictEqual(response.body.ok, true)
    assert.strictEqual(response.body.transitioned, false)
    assert.strictEqual(response.body.stream.is_active, false)
    assert.strictEqual('was_active' in response.body.stream, false)
    assert.strictEqual(response.headers['cache-control'], 'no-store')
    assert.match(statements[0].statement, /SET is_active = FALSE/)
    assert.match(statements[0].statement, /is_active AS was_active/)
    assert.match(statements[0].statement, /GREATEST\(stream\.total_reactions, \$2\)/)
    assert.deepStrictEqual(statements[0].values, [liveId, 17])
  })
}

Promise.resolve()
  .then(testRejectsMissingServiceSecretWithoutDatabaseAccess)
  .then(testTerminatesIdempotentlyWithInternalSecret)
  .then(() => console.log('live internal termination tests passed'))
  .catch((error) => {
    console.error(error)
    process.exitCode = 1
  })
