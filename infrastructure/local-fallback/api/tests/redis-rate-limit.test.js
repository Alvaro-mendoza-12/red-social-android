const assert = require('assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const {
  encodeRespCommand,
  parseRedisConnectionConfig,
  parseRespValue,
} = require('../src/redis-resp-client')
const {
  REDIS_FIXED_WINDOW_LUA,
  consumeDistributedFixedWindow,
  createDistributedRateLimitMiddleware,
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
  // 1. RESP2 encoding & decoding including multibyte UTF-8, embedded CRLF and nested XREAD arrays
  const encoded = encodeRespCommand(['SET', 'k', 'hola\r\n🚀'])
  assert.equal(
    encoded.toString('utf8'),
    `*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$${Buffer.byteLength('hola\r\n🚀')}\r\nhola\r\n🚀\r\n`,
  )

  const simple = parseRespValue(Buffer.from('+PONG\r\n'), 0)
  assert.equal(simple.value, 'PONG')

  const integer = parseRespValue(Buffer.from(':42\r\n'), 0)
  assert.equal(integer.value, 42)

  const nullBulk = parseRespValue(Buffer.from('$-1\r\n'), 0)
  assert.equal(nullBulk.value, null)

  const errReply = parseRespValue(Buffer.from('-ERR wrong number of arguments\r\n'), 0)
  assert.match(errReply.error.message, /wrong number of arguments/)

  const nestedStreamReply = Buffer.from(
    '*1\r\n*2\r\n$22\r\nnovvin:realtime:events\r\n*1\r\n*2\r\n$15\r\n1700000000000-0\r\n*4\r\n$6\r\norigin\r\n$3\r\nn-1\r\n$7\r\npayload\r\n$11\r\n{"ok":true}\r\n',
  )
  // Verify incremental framing returns null until full buffer arrives
  assert.equal(parseRespValue(nestedStreamReply.subarray(0, 24), 0), null)
  const parsedStream = parseRespValue(nestedStreamReply, 0)
  assert.equal(parsedStream.value[0][0], 'novvin:realtime:events')
  assert.equal(parsedStream.value[0][1][0][0], '1700000000000-0')
  assert.deepEqual(parsedStream.value[0][1][0][1], ['origin', 'n-1', 'payload', '{"ok":true}'])

  // 2. Config parsing & passwordFile reading
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'novvin-rl-test-'))
  try {
    const secretFile = path.join(tmpDir, 'redis.secret')
    fs.writeFileSync(secretFile, 'super-secret-redis-password-123456\n', 'utf8')
    const cfg = parseRedisConnectionConfig({
      url: 'rediss://redis.internal:6380/2',
      passwordFile: secretFile,
    })
    assert.equal(cfg.host, 'redis.internal')
    assert.equal(cfg.port, 6380)
    assert.equal(cfg.useTls, true)
    assert.equal(cfg.db, 2)
    assert.equal(cfg.password, 'super-secret-redis-password-123456')
    assert.throws(() => parseRedisConnectionConfig({ url: 'http://127.0.0.1:6379' }), /redis:\/\/ or rediss:\/\//)
  } finally {
    fs.rmSync(tmpDir, { recursive: true, force: true })
  }

  // 3. Redis fast-path executes Lua atomically and avoids PostgreSQL completely
  let pgCalls = 0
  const pgPool = {
    async query() {
      pgCalls += 1
      return { rows: [{ request_count: '1', retry_after_seconds: 60 }] }
    },
  }
  const redisCommands = []
  let redisCounter = 0
  const readyRedis = {
    isReady: () => true,
    async sendCommand(args) {
      redisCommands.push(args)
      redisCounter += 1
      return [redisCounter, 59_500]
    },
  }
  let redisHits = 0
  let pgFallbacks = 0

  const first = await consumeDistributedFixedWindow({
    pool: pgPool,
    redisClient: readyRedis,
    onRedisHit: () => { redisHits += 1 },
    onRedisFallback: () => { pgFallbacks += 1 },
    scope: 'data:message-write',
    key: 'user:alice',
    limit: 2,
    windowMs: 60_000,
  })
  assert.deepEqual(first, { allowed: true, limit: 2, remaining: 1, retryAfterSeconds: 60 })
  assert.equal(pgCalls, 0)
  assert.equal(redisHits, 1)
  assert.equal(pgFallbacks, 0)
  assert.equal(redisCommands[0][0], 'EVAL')
  assert.equal(redisCommands[0][1], REDIS_FIXED_WINDOW_LUA)
  assert.equal(redisCommands[0][2], '1')
  assert.match(redisCommands[0][3], /^novvin:rl:data:message-write:[a-f0-9]{64}$/)

  await consumeDistributedFixedWindow({
    pool: pgPool,
    redisClient: readyRedis,
    onRedisHit: () => { redisHits += 1 },
    scope: 'data:message-write',
    key: 'user:alice',
    limit: 2,
    windowMs: 60_000,
  })
  const third = await consumeDistributedFixedWindow({
    pool: pgPool,
    redisClient: readyRedis,
    onRedisHit: () => { redisHits += 1 },
    scope: 'data:message-write',
    key: 'user:alice',
    limit: 2,
    windowMs: 60_000,
  })
  assert.deepEqual(third, { allowed: false, limit: 2, remaining: 0, retryAfterSeconds: 60 })
  assert.equal(pgCalls, 0)

  // 4. Redis outage falls back deterministically to PostgreSQL (never fail-open)
  const failingRedis = {
    isReady: () => true,
    async sendCommand() {
      throw new Error('REDIS_CONNECTION_CLOSED')
    },
  }
  const fallbackMiddleware = createDistributedRateLimitMiddleware({
    pool: {
      async query() {
        pgCalls += 1
        return { rows: [{ request_count: '3', retry_after_seconds: 15 }] }
      },
    },
    redisClient: failingRedis,
    onRedisFallback: () => { pgFallbacks += 1 },
    scope: 'data:message-write',
    limit: 2,
    windowMs: 60_000,
    code: 'MESSAGE_RATE_LIMITED',
    key: () => 'user:alice',
    methods: ['POST'],
  })
  const res = responseRecorder()
  let nextCalled = null
  await fallbackMiddleware({ method: 'POST' }, res, (err) => { nextCalled = err || 'next' })
  assert.equal(nextCalled, null)
  assert.equal(res.statusCode, 429)
  assert.deepEqual(res.body, { error: 'MESSAGE_RATE_LIMITED' })
  assert.equal(res.headers['retry-after'], '15')
  assert.equal(pgCalls, 1)
  assert.equal(pgFallbacks, 1)

  // 5. Both Redis and PostgreSQL failing must stay fail-closed
  const dbDownError = new Error('pg down')
  const bothDownMiddleware = createDistributedRateLimitMiddleware({
    pool: { async query() { throw dbDownError } },
    redisClient: { isReady: () => false },
    scope: 'auth:login',
    limit: 5,
    windowMs: 60_000,
  })
  let forwarded = null
  await bothDownMiddleware({ method: 'POST' }, responseRecorder(), (err) => { forwarded = err })
  assert.equal(forwarded, dbDownError)

  console.log('redis rate limit & RESP2 protocol tests passed')
}

run().catch((error) => {
  console.error(error)
  process.exit(1)
})
