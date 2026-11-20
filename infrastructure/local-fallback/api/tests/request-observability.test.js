const assert = require('assert/strict')
const express = require('express')
const {
  createApiMetricsCollector,
  createRequestObservabilityMiddleware,
  parseOrCreateTraceparent,
  routeName,
  safeErrorLogDetails,
} = require('../src/request-observability')

async function run() {
  const warnings = []
  const privateDiagnostic = 'storage path /private/users/42/token-secret failed'
  const productionLog = safeErrorLogDetails(new Error(privateDiagnostic), {
    production: true,
    fallbackCode: 'INTERNAL_ERROR',
  })
  assert.equal(productionLog.code, 'INTERNAL_ERROR')
  assert.match(productionLog.messageRef, /^[a-f0-9]{24}$/)
  assert.equal(Object.hasOwn(productionLog, 'message'), false)
  assert.equal(JSON.stringify(productionLog).includes(privateDiagnostic), false)

  const developmentLog = safeErrorLogDetails(new Error(privateDiagnostic), { production: false })
  assert.equal(developmentLog.message, privateDiagnostic)
  assert.equal(developmentLog.messageRef, productionLog.messageRef)

  const metrics = createApiMetricsCollector({
    pool: { totalCount: 4, idleCount: 3, waitingCount: 0 },
    redisRateLimiter: { isReady: () => true },
  })
  const app = express()
  app.use((req, _res, next) => {
    req.requestId = 'request-observability-test'
    req.correlationId = 'request-observability-test'
    next()
  })
  app.use(createRequestObservabilityMiddleware({
    slowRequestMs: 100,
    logger: { warn: (...args) => warnings.push(args) },
    metrics,
  }))
  app.get('/items/:id', async (_req, res) => {
    await new Promise((resolve) => setTimeout(resolve, 120))
    res.json({ ok: true })
  })
  app.get('/timed', (req, res) => {
    req._serverTimings = [{ name: 'access', durMs: 1.5 }, { name: 'persist', durMs: 4.2 }]
    res.json({ ok: true })
  })
  const server = await new Promise((resolve) => {
    const listening = app.listen(0, '127.0.0.1', () => resolve(listening))
  })
  try {
    const address = server.address()
    const response = await fetch(`http://127.0.0.1:${address.port}/items/private-value?secret=hidden`)
    assert.equal(response.status, 200)
    assert.match(response.headers.get('server-timing'), /^app;dur=\d+\.\d$/)
    assert.match(response.headers.get('x-response-time'), /^\d+\.\dms$/)
    assert.equal(warnings.length, 1)
    assert.equal(warnings[0][1].route, '/items/:id')
    assert.equal(JSON.stringify(warnings).includes('private-value'), false)
    assert.equal(JSON.stringify(warnings).includes('hidden'), false)
    assert.equal(routeName({ baseUrl: '', route: { path: '/users/:id' } }), '/users/:id')
    assert.equal(routeName({ observabilityRoute: '/rest/v1/rpc/delete_own_account', route: { path: /^\/rpc\/(.*)$/ } }),
      '/rest/v1/rpc/delete_own_account')
    assert.equal(routeName({ observabilityRoute: '/rest/v1/rpc/:operation', route: { path: /^\/rpc\/(.*)$/ } }),
      '/rest/v1/rpc/:operation')
    assert.equal(routeName({ observabilityRoute: '/users/private-value?secret=hidden' }), 'unmatched')
    const timedRes = await fetch(`http://127.0.0.1:${address.port}/timed`)
    assert.equal(timedRes.status, 200)
    assert.match(timedRes.headers.get('server-timing'), /^app;dur=\d+\.\d, access;dur=1\.5, persist;dur=4\.2$/)
    metrics.recordLoadShed()
    metrics.recordRateLimitRedisHit()
    metrics.recordRateLimitPgFallback()
    const snapshot = metrics.snapshot()
    assert.equal(snapshot.requestsTotal, 2)
    assert.equal(snapshot.status2xx, 2)
    assert.equal(snapshot.slowRequestsTotal, 1)
    assert.equal(snapshot.loadShedTotal, 1)
    assert.equal(snapshot.rateLimitRedisHits, 1)
    assert.equal(snapshot.rateLimitPgFallbacks, 1)
    assert.equal(snapshot.rateLimitRedisReady, true)
    assert.equal(snapshot.latencyP50Ms >= 100, true)
    assert.equal(snapshot.dbPool.total, 4)
    const promText = metrics.formatPrometheus()
    assert.match(promText, /novvin_api_requests_total 2/)
    assert.match(promText, /novvin_api_load_shed_total 1/)
    assert.match(promText, /novvin_api_ratelimit_redis_hits_total 1/)
    assert.match(promText, /novvin_api_ratelimit_pg_fallback_total 1/)
    assert.match(promText, /novvin_api_ratelimit_redis_ready 1/)
    assert.match(promText, /novvin_api_db_pool_connections\{state="total"\} 4/)

    const validTrace = parseOrCreateTraceparent('00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01')
    assert.equal(validTrace.traceId, '4bf92f3577b34da6a3ce929d0e0e4736')
    assert.equal(validTrace.parentSpanId, '00f067aa0ba902b7')
    assert.notEqual(validTrace.spanId, '00f067aa0ba902b7')
    assert.match(validTrace.traceparent, /^00-4bf92f3577b34da6a3ce929d0e0e4736-[0-9a-f]{16}-01$/)

    const rejectedZeroTrace = parseOrCreateTraceparent('00-00000000000000000000000000000000-0000000000000000-01')
    assert.notEqual(rejectedZeroTrace.traceId, '00000000000000000000000000000000')
    assert.equal(rejectedZeroTrace.parentSpanId, null)
  } finally {
    await new Promise((resolve, reject) => server.close((error) => (error ? reject(error) : resolve())))
  }
  console.log('request-observability tests passed')
}

run().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
