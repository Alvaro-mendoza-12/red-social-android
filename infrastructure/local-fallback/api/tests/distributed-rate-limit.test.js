const assert = require('assert/strict')
const {
  consumeDistributedFixedWindow,
  createDistributedRateLimitMiddleware,
  hashRateLimitKey,
  normalizedScope,
  pruneExpiredRateLimitWindows,
} = require('../src/distributed-rate-limit')

function responseRecorder() {
  return {
    body: null,
    headers: {},
    statusCode: 200,
    json(body) { this.body = body; return this },
    set(name, value) { this.headers[String(name).toLowerCase()] = String(value); return this },
    status(code) { this.statusCode = code; return this },
  }
}

async function run() {
  assert.equal(normalizedScope(' Auth:Login '), 'auth:login')
  assert.throws(() => normalizedScope('../login'), /invalid/)
  assert.equal(hashRateLimitKey('private-key').length, 64)
  assert.equal(hashRateLimitKey('private-key').includes('private-key'), false)

  const queries = []
  const pool = {
    async query(sql, params) {
      queries.push({ sql, params })
      return { rows: [{ request_count: '2', retry_after_seconds: 12 }], rowCount: 1 }
    },
  }
  const result = await consumeDistributedFixedWindow({
    pool,
    scope: 'auth:login',
    key: 'ip:198.51.100.8:credential:private@example.com',
    limit: 2,
    windowMs: 60_000,
  })
  assert.deepEqual(result, { allowed: true, limit: 2, remaining: 0, retryAfterSeconds: 12 })
  assert.equal(queries[0].params[0], 'auth:login')
  assert.equal(queries[0].params[1].length, 64)
  assert.equal(queries[0].params[1].includes('private@example.com'), false)
  assert.match(queries[0].sql, /ON CONFLICT \(scope, key_hash\) DO UPDATE/)
  assert.match(queries[0].sql, /LEAST\(fallback_rate_limit_windows\.request_count \+ 1/)

  const middleware = createDistributedRateLimitMiddleware({
    pool: {
      async query() {
        return { rows: [{ request_count: '3', retry_after_seconds: 9 }] }
      },
    },
    scope: 'auth:password-reset',
    limit: 2,
    windowMs: 60_000,
    code: 'TEST_RATE_LIMITED',
    key: () => 'secret-key',
    methods: ['POST'],
  })
  const response = responseRecorder()
  let nextValue = null
  await middleware({ method: 'POST' }, response, (value) => { nextValue = value || 'next' })
  assert.equal(nextValue, null)
  assert.equal(response.statusCode, 429)
  assert.deepEqual(response.body, { error: 'TEST_RATE_LIMITED' })
  assert.equal(response.headers['retry-after'], '9')

  let skipped = false
  await middleware({ method: 'GET' }, responseRecorder(), () => { skipped = true })
  assert.equal(skipped, true)

  const expected = new Error('database unavailable')
  const failingMiddleware = createDistributedRateLimitMiddleware({
    pool: { async query() { throw expected } },
    scope: 'auth:login',
    limit: 1,
    windowMs: 1_000,
  })
  let forwarded = null
  await failingMiddleware({ method: 'POST' }, responseRecorder(), (error) => { forwarded = error })
  assert.equal(forwarded, expected)

  const cleanup = await pruneExpiredRateLimitWindows(pool, { retentionMs: 60_000 })
  assert.equal(cleanup, 1)
  assert.match(queries.at(-1).sql, /DELETE FROM fallback_rate_limit_windows/)

  console.log('distributed rate limit tests passed')
}

run().catch((error) => {
  console.error(error)
  process.exit(1)
})
