const assert = require('assert/strict')
const {
  boundedInteger,
  createFixedWindowRateLimiter,
  createRateLimitMiddleware,
  isTrustedProxyAddress,
  normalizeIpAddress,
  requestIpAddress,
} = require('../src/request-security')

function responseRecorder() {
  return {
    body: null,
    headers: {},
    statusCode: 200,
    json(body) {
      this.body = body
      return this
    },
    set(name, value) {
      this.headers[String(name).toLowerCase()] = String(value)
      return this
    },
    status(code) {
      this.statusCode = code
      return this
    },
  }
}

async function run() {
  assert.equal(boundedInteger('12', 5, 1, 20), 12)
  assert.equal(boundedInteger('invalid', 5, 1, 20), 5)
  assert.equal(boundedInteger(99, 5, 1, 20), 20)
  assert.equal(normalizeIpAddress('::ffff:192.168.1.4'), '192.168.1.4')
  assert.equal(isTrustedProxyAddress('172.18.0.2'), true)
  assert.equal(isTrustedProxyAddress('172.40.0.2'), false)

  assert.equal(requestIpAddress({
    headers: { 'cf-connecting-ip': '203.0.113.8' },
    ip: '172.18.0.2',
    socket: { remoteAddress: '172.18.0.2' },
  }), '203.0.113.8')
  assert.equal(requestIpAddress({
    headers: { 'cf-connecting-ip': '198.51.100.9' },
    ip: '203.0.113.20',
    socket: { remoteAddress: '203.0.113.20' },
  }), '203.0.113.20')

  let now = 10_000
  const consume = createFixedWindowRateLimiter({ limit: 2, windowMs: 1_000, now: () => now })
  assert.equal(consume('same-user').allowed, true)
  assert.equal(consume('same-user').allowed, true)
  assert.equal(consume('same-user').allowed, false)
  now += 1_001
  assert.equal(consume('same-user').allowed, true)

  const middleware = createRateLimitMiddleware({
    limit: 1,
    windowMs: 60_000,
    code: 'TEST_RATE_LIMITED',
    key: () => 'key',
  })
  const first = responseRecorder()
  let nextCalls = 0
  middleware({ method: 'POST' }, first, () => { nextCalls += 1 })
  assert.equal(nextCalls, 1)
  assert.equal(first.headers['x-ratelimit-remaining'], '0')

  const second = responseRecorder()
  middleware({ method: 'POST' }, second, () => { nextCalls += 1 })
  assert.equal(nextCalls, 1)
  assert.equal(second.statusCode, 429)
  assert.deepEqual(second.body, { error: 'TEST_RATE_LIMITED' })
  assert.equal(Number(second.headers['retry-after']) > 0, true)

  console.log('request security tests passed')
}

run().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
