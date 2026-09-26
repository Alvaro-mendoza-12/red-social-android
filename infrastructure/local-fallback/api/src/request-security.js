const crypto = require('crypto')

function boundedInteger(value, fallback, minimum, maximum) {
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) return fallback
  return Math.round(Math.max(minimum, Math.min(maximum, parsed)))
}

function normalizeIpAddress(value) {
  const address = String(value || '').trim().toLowerCase()
  return address.startsWith('::ffff:') ? address.slice(7) : address
}

function isTrustedProxyAddress(value) {
  const address = normalizeIpAddress(value)
  if (!address) return false
  if (address === '::1' || address === '127.0.0.1') return true
  if (address.startsWith('10.') || address.startsWith('192.168.')) return true
  if (address.startsWith('fc') || address.startsWith('fd')) return true
  const match = /^172\.(\d{1,3})\./.exec(address)
  return Boolean(match && Number(match[1]) >= 16 && Number(match[1]) <= 31)
}

function requestIpAddress(req) {
  const peerAddress = normalizeIpAddress(req.socket?.remoteAddress || '')
  const cloudflareAddress = normalizeIpAddress(req.headers?.['cf-connecting-ip'] || '')
  if (cloudflareAddress && isTrustedProxyAddress(peerAddress)) return cloudflareAddress.slice(0, 120)
  return normalizeIpAddress(req.ip || peerAddress || 'unknown').slice(0, 120) || 'unknown'
}

function stableKey(value) {
  return crypto.createHash('sha256').update(String(value || '')).digest('hex').slice(0, 24)
}

function constantTimeSecretEqual(left, right) {
  const leftText = String(left || '')
  const rightText = String(right || '')
  if (!leftText || !rightText) return false
  const leftDigest = crypto.createHash('sha256').update(leftText).digest()
  const rightDigest = crypto.createHash('sha256').update(rightText).digest()
  return crypto.timingSafeEqual(leftDigest, rightDigest)
}

function parseCorsOrigins(value, { production = false } = {}) {
  const raw = String(value || '').trim()
  if (raw === '*') {
    if (production) throw new Error('CORS_ORIGIN must be an explicit trusted HTTPS origin in production')
    return '*'
  }
  const values = Array.from(new Set(raw.split(',').map((entry) => entry.trim()).filter(Boolean)))
  if (values.length === 0) {
    throw new Error('CORS_ORIGIN must contain at least one trusted origin')
  }
  return values.map((entry) => {
    let parsed
    try {
      parsed = new URL(entry)
    } catch {
      throw new Error(`CORS_ORIGIN contains an invalid origin: ${entry}`)
    }
    const isOriginOnly = parsed.origin === entry.replace(/\/$/, '')
      && !parsed.username
      && !parsed.password
      && !parsed.search
      && !parsed.hash
      && (parsed.pathname === '/' || parsed.pathname === '')
    if (!isOriginOnly || !['http:', 'https:'].includes(parsed.protocol)) {
      throw new Error(`CORS_ORIGIN contains an invalid origin: ${entry}`)
    }
    if (production && parsed.protocol !== 'https:') {
      throw new Error('CORS_ORIGIN must be an explicit trusted HTTPS origin in production')
    }
    return parsed.origin
  })
}

function createFixedWindowRateLimiter({ limit, windowMs, now = Date.now, maxEntries = 50_000 }) {
  const windows = new Map()
  let lastSweepAt = 0

  function sweep(currentTime) {
    if (currentTime - lastSweepAt < windowMs && windows.size <= maxEntries) return
    lastSweepAt = currentTime
    for (const [key, window] of windows) {
      if (currentTime - window.startedAt >= windowMs) windows.delete(key)
    }
    while (windows.size > maxEntries) {
      windows.delete(windows.keys().next().value)
    }
  }

  return function consume(rawKey) {
    const currentTime = now()
    sweep(currentTime)
    const key = String(rawKey || 'anonymous').trim().slice(0, 200) || 'anonymous'
    let window = windows.get(key)
    if (!window || currentTime - window.startedAt >= windowMs) {
      window = { startedAt: currentTime, count: 0 }
      windows.set(key, window)
    }
    window.count += 1
    const retryAfterSeconds = Math.max(
      1,
      Math.ceil((windowMs - (currentTime - window.startedAt)) / 1000),
    )
    return {
      allowed: window.count <= limit,
      limit,
      remaining: Math.max(0, limit - window.count),
      retryAfterSeconds,
    }
  }
}

function createRateLimitMiddleware({
  limit,
  windowMs,
  code = 'RATE_LIMITED',
  key = (req) => requestIpAddress(req),
  methods = null,
  now,
}) {
  const allowedMethods = methods ? new Set(methods.map((method) => String(method).toUpperCase())) : null
  const consume = createFixedWindowRateLimiter({ limit, windowMs, now })
  return function rateLimitMiddleware(req, res, next) {
    if (allowedMethods && !allowedMethods.has(String(req.method || '').toUpperCase())) return next()
    const result = consume(key(req))
    res.set('x-ratelimit-limit', String(result.limit))
    res.set('x-ratelimit-remaining', String(result.remaining))
    if (result.allowed) return next()
    res.set('retry-after', String(result.retryAfterSeconds))
    return res.status(429).json({ error: code })
  }
}

module.exports = {
  boundedInteger,
  constantTimeSecretEqual,
  createFixedWindowRateLimiter,
  createRateLimitMiddleware,
  isTrustedProxyAddress,
  normalizeIpAddress,
  parseCorsOrigins,
  requestIpAddress,
  stableKey,
}
