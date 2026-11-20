const crypto = require('crypto')

const REDIS_FIXED_WINDOW_LUA = `
local current = redis.call('INCR', KEYS[1])
local ttl = redis.call('PTTL', KEYS[1])
if current == 1 or ttl < 0 then
  redis.call('PEXPIRE', KEYS[1], ARGV[1])
  ttl = tonumber(ARGV[1])
end
return {current, ttl}
`.trim()

function positiveInteger(value, name, maximum) {
  const parsed = Number(value)
  if (!Number.isSafeInteger(parsed) || parsed <= 0 || parsed > maximum) {
    throw new TypeError(`${name} must be an integer between 1 and ${maximum}`)
  }
  return parsed
}

function normalizedScope(value) {
  const scope = String(value || '').trim().toLowerCase()
  if (!/^[a-z0-9][a-z0-9:_-]{0,79}$/.test(scope)) {
    throw new TypeError('rate-limit scope is invalid')
  }
  return scope
}

function hashRateLimitKey(value) {
  return crypto.createHash('sha256').update(String(value || 'anonymous')).digest('hex')
}

async function consumeRedisFixedWindow({
  redisClient,
  scope,
  keyHash,
  limit,
  windowMs,
  keyPrefix = 'novvin:rl',
  commandTimeoutMs = 200,
}) {
  if (!redisClient || typeof redisClient.sendCommand !== 'function' || !redisClient.isReady?.()) {
    const err = new Error('REDIS_RATE_LIMIT_UNAVAILABLE')
    err.code = 'REDIS_RATE_LIMIT_UNAVAILABLE'
    throw err
  }
  const redisKey = `${keyPrefix}:${scope}:${keyHash}`
  const reply = await redisClient.sendCommand(
    ['EVAL', REDIS_FIXED_WINDOW_LUA, '1', redisKey, String(windowMs)],
    { timeoutMs: commandTimeoutMs },
  )
  if (!Array.isArray(reply) || reply.length < 2) {
    const err = new Error('REDIS_RATE_LIMIT_INVALID_REPLY')
    err.code = 'REDIS_RATE_LIMIT_INVALID_REPLY'
    throw err
  }
  const count = Number(reply[0] || 0)
  const ttlMs = Number(reply[1] || windowMs)
  const retryAfterSeconds = Math.max(1, Math.ceil(Math.max(ttlMs, 1) / 1000))
  return {
    allowed: count > 0 && count <= limit,
    limit,
    remaining: Math.max(0, limit - count),
    retryAfterSeconds,
    backend: 'redis',
  }
}

async function consumePostgresFixedWindow({ pool, scope, keyHash, limit, windowMs }) {
  if (!pool || typeof pool.query !== 'function') throw new TypeError('pool.query is required')
  const { rows } = await pool.query(
    `INSERT INTO fallback_rate_limit_windows(
        scope, key_hash, window_started_at, request_count, expires_at
     )
     VALUES (
       $1, $2, NOW(), 1, NOW() + ($3::bigint * INTERVAL '1 millisecond')
     )
     ON CONFLICT (scope, key_hash) DO UPDATE SET
       window_started_at = CASE
         WHEN fallback_rate_limit_windows.expires_at <= NOW() THEN NOW()
         ELSE fallback_rate_limit_windows.window_started_at
       END,
       request_count = CASE
         WHEN fallback_rate_limit_windows.expires_at <= NOW() THEN 1
         ELSE LEAST(fallback_rate_limit_windows.request_count + 1, $4::bigint + 1)
       END,
       expires_at = CASE
         WHEN fallback_rate_limit_windows.expires_at <= NOW()
           THEN NOW() + ($3::bigint * INTERVAL '1 millisecond')
         ELSE fallback_rate_limit_windows.expires_at
       END
     RETURNING request_count::text,
       GREATEST(1, CEIL(EXTRACT(EPOCH FROM (expires_at - NOW()))))::integer
         AS retry_after_seconds`,
    [scope, keyHash, windowMs, limit],
  )
  const count = Number(rows[0]?.request_count || 0)
  const retryAfterSeconds = Math.max(1, Number(rows[0]?.retry_after_seconds || 1))
  return {
    allowed: count > 0 && count <= limit,
    limit,
    remaining: Math.max(0, limit - count),
    retryAfterSeconds,
  }
}

async function consumeDistributedFixedWindow({
  pool,
  redisClient = null,
  keyPrefix = 'novvin:rl',
  redisCommandTimeoutMs = 200,
  onRedisHit = null,
  onRedisFallback = null,
  scope,
  key,
  limit,
  windowMs,
}) {
  if (!pool || typeof pool.query !== 'function') throw new TypeError('pool.query is required')
  const safeScope = normalizedScope(scope)
  const safeLimit = positiveInteger(limit, 'limit', 1_000_000)
  const safeWindowMs = positiveInteger(windowMs, 'windowMs', 7 * 24 * 60 * 60_000)
  const keyHash = hashRateLimitKey(key)

  if (redisClient) {
    if (redisClient.isReady?.()) {
      try {
        const result = await consumeRedisFixedWindow({
          redisClient,
          scope: safeScope,
          keyHash,
          limit: safeLimit,
          windowMs: safeWindowMs,
          keyPrefix,
          commandTimeoutMs: redisCommandTimeoutMs,
        })
        onRedisHit?.()
        return {
          allowed: result.allowed,
          limit: result.limit,
          remaining: result.remaining,
          retryAfterSeconds: result.retryAfterSeconds,
        }
      } catch (error) {
        onRedisFallback?.(error)
      }
    } else {
      onRedisFallback?.(null)
    }
  }

  return consumePostgresFixedWindow({
    pool,
    scope: safeScope,
    keyHash,
    limit: safeLimit,
    windowMs: safeWindowMs,
  })
}

function createDistributedRateLimitMiddleware({
  pool,
  redisClient = null,
  keyPrefix = 'novvin:rl',
  redisCommandTimeoutMs = 200,
  onRedisHit = null,
  onRedisFallback = null,
  scope,
  limit,
  windowMs,
  code = 'RATE_LIMITED',
  key = () => 'anonymous',
  methods = null,
}) {
  const safeScope = normalizedScope(scope)
  const allowedMethods = methods
    ? new Set(methods.map((method) => String(method).toUpperCase()))
    : null
  return async function distributedRateLimitMiddleware(req, res, next) {
    if (allowedMethods && !allowedMethods.has(String(req.method || '').toUpperCase())) return next()
    try {
      const result = await consumeDistributedFixedWindow({
        pool,
        redisClient,
        keyPrefix,
        redisCommandTimeoutMs,
        onRedisHit,
        onRedisFallback,
        scope: safeScope,
        key: key(req),
        limit,
        windowMs,
      })
      res.set('x-ratelimit-limit', String(result.limit))
      res.set('x-ratelimit-remaining', String(result.remaining))
      if (result.allowed) return next()
      res.set('retry-after', String(result.retryAfterSeconds))
      return res.status(429).json({ error: code })
    } catch (error) {
      // Sensitive operations fail closed when the authoritative shared limit
      // cannot be checked. The API error middleware returns a request id.
      return next(error)
    }
  }
}

async function pruneExpiredRateLimitWindows(pool, { retentionMs = 24 * 60 * 60_000 } = {}) {
  if (!pool || typeof pool.query !== 'function') throw new TypeError('pool.query is required')
  const safeRetentionMs = positiveInteger(retentionMs, 'retentionMs', 30 * 24 * 60 * 60_000)
  const result = await pool.query(
    `DELETE FROM fallback_rate_limit_windows
      WHERE expires_at < NOW() - ($1::bigint * INTERVAL '1 millisecond')`,
    [safeRetentionMs],
  )
  return Number(result.rowCount || 0)
}

module.exports = {
  REDIS_FIXED_WINDOW_LUA,
  consumeDistributedFixedWindow,
  consumePostgresFixedWindow,
  consumeRedisFixedWindow,
  createDistributedRateLimitMiddleware,
  hashRateLimitKey,
  normalizedScope,
  pruneExpiredRateLimitWindows,
}
