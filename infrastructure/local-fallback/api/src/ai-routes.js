const DEFAULT_AI_INTERNAL_URL = 'http://ai-chatbot:3010'
const DEFAULT_AI_TIMEOUT_MS = 3000
const DEFAULT_AI_CHAT_TIMEOUT_MS = 3000
const DEFAULT_AI_CHAT_RATE_LIMIT_PER_MINUTE = 30
const DEFAULT_AI_FEEDBACK_RATE_LIMIT_PER_MINUTE = 20

function boundedInteger(value, fallback, minimum, maximum) {
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) return fallback
  return Math.round(Math.max(minimum, Math.min(maximum, parsed)))
}

function cleanText(value, maximum) {
  return String(value || '').trim().slice(0, maximum)
}

function createFixedWindowRateLimiter({ limit, windowMs = 60_000, now = Date.now }) {
  const windows = new Map()
  return function consume(key) {
    const currentTime = now()
    const cleanKey = cleanText(key, 160) || 'anonymous'
    let window = windows.get(cleanKey)
    if (!window || currentTime - window.startedAt >= windowMs) {
      window = { startedAt: currentTime, count: 0 }
      windows.set(cleanKey, window)
    }
    window.count += 1

    if (windows.size > 10_000) {
      for (const [entryKey, entry] of windows) {
        if (currentTime - entry.startedAt >= windowMs) windows.delete(entryKey)
      }
    }

    return {
      allowed: window.count <= limit,
      retryAfterSeconds: Math.max(1, Math.ceil((windowMs - (currentTime - window.startedAt)) / 1000)),
    }
  }
}

function sanitizeContext(context, userId) {
  const source = context && typeof context === 'object' && !Array.isArray(context) ? context : {}
  const history = String(source.history || '').trim()
  return {
    userId: cleanText(userId, 80) || null,
    conversationId: cleanText(source.conversationId, 128),
    history: history.slice(-6000),
    screen: cleanText(source.screen, 80),
    locale: cleanText(source.locale, 24),
    detailLevel: source.detailLevel === 'expanded' ? 'expanded' : 'standard',
  }
}

function sanitizeFeedback(body, userId) {
  return {
    userId: cleanText(userId, 80) || null,
    message: cleanText(body?.message, 1000),
    answer: cleanText(body?.answer, 1500),
    intent: cleanText(body?.intent, 80),
    rating: cleanText(body?.rating, 32),
    expectedIntent: cleanText(body?.expectedIntent, 80),
    comment: cleanText(body?.comment, 500),
  }
}

function publicAiErrorCode(error) {
  const candidate = String(error?.code || error?.payload?.error || '').trim()
  return /^[A-Z][A-Z0-9_]{1,79}$/.test(candidate) ? candidate : 'AI_SERVICE_ERROR'
}

function handleAiError(error, res, next) {
  const status = Number(error?.status)
  if (status >= 400 && status <= 504) {
    const code = publicAiErrorCode(error)
    const log = status >= 500 ? console.error : console.warn
    log('[api] AI proxy request failed', {
      status,
      code,
    })
    return res.status(status).json({
      error: code,
    })
  }
  return next(error)
}

async function callAiService(path, payload, timeoutMs = null) {
  const baseUrl = String(process.env.AI_INTERNAL_URL || DEFAULT_AI_INTERNAL_URL).replace(/\/+$/, '')
  const effectiveTimeoutMs = boundedInteger(
    timeoutMs ?? process.env.AI_REQUEST_TIMEOUT_MS,
    DEFAULT_AI_TIMEOUT_MS,
    250,
    10_000,
  )
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), effectiveTimeoutMs)
  try {
    const response = await fetch(`${baseUrl}${path}`, {
      method: payload == null ? 'GET' : 'POST',
      headers: payload == null ? undefined : { 'content-type': 'application/json' },
      body: payload == null ? undefined : JSON.stringify(payload),
      signal: controller.signal,
    })
    const text = await response.text()
    let body = {}
    if (text.trim()) {
      try {
        body = JSON.parse(text)
      } catch {
        body = { message: text }
      }
    }
    if (!response.ok) {
      const error = new Error(body.error || body.message || `AI service returned ${response.status}`)
      error.status = response.status
      error.payload = body
      throw error
    }
    return body
  } catch (error) {
    if (error.name === 'AbortError') {
      const timeoutError = new Error('AI service timed out')
      timeoutError.status = 504
      timeoutError.code = 'AI_SERVICE_TIMEOUT'
      timeoutError.cause = error
      throw timeoutError
    } else if (!error.status) {
      const unavailableError = new Error('AI service is unavailable')
      unavailableError.status = 503
      unavailableError.code = 'AI_SERVICE_UNAVAILABLE'
      unavailableError.cause = error
      throw unavailableError
    }
    throw error
  } finally {
    clearTimeout(timeout)
  }
}

function registerAiRoutes(app, { authRequired }) {
  const chatLimiter = createFixedWindowRateLimiter({
    limit: boundedInteger(
      process.env.AI_CHAT_RATE_LIMIT_PER_MINUTE,
      DEFAULT_AI_CHAT_RATE_LIMIT_PER_MINUTE,
      5,
      120,
    ),
  })
  const feedbackLimiter = createFixedWindowRateLimiter({
    limit: boundedInteger(
      process.env.AI_FEEDBACK_RATE_LIMIT_PER_MINUTE,
      DEFAULT_AI_FEEDBACK_RATE_LIMIT_PER_MINUTE,
      5,
      120,
    ),
  })

  function rateLimit(req, res, limiter) {
    const result = limiter(req.auth?.sub)
    if (result.allowed) return false
    res.set('retry-after', String(result.retryAfterSeconds))
    res.status(429).json({ error: 'AI_RATE_LIMITED' })
    return true
  }

  app.get('/v1/ai/health', authRequired, async (_req, res, next) => {
    try {
      res.json(await callAiService('/ready', null, 2000))
    } catch (error) {
      handleAiError(error, res, next)
    }
  })

  app.post('/v1/ai/chat', authRequired, async (req, res, next) => {
    try {
      if (rateLimit(req, res, chatLimiter)) return
      const message = String(req.body?.message || '').trim()
      if (!message) return res.status(400).json({ error: 'MESSAGE_REQUIRED' })
      if (message.length > 1000) return res.status(413).json({ error: 'MESSAGE_TOO_LONG' })
      const answer = await callAiService('/v1/chat', {
        message,
        context: sanitizeContext(req.body?.context, req.auth?.sub),
      }, process.env.AI_CHAT_REQUEST_TIMEOUT_MS || DEFAULT_AI_CHAT_TIMEOUT_MS)
      return res.json(answer)
    } catch (error) {
      handleAiError(error, res, next)
    }
  })

  app.post('/v1/ai/feedback', authRequired, async (req, res, next) => {
    try {
      if (rateLimit(req, res, feedbackLimiter)) return
      const result = await callAiService(
        '/v1/feedback',
        sanitizeFeedback(req.body, req.auth?.sub),
      )
      return res.status(202).json(result)
    } catch (error) {
      handleAiError(error, res, next)
    }
  })
}

module.exports = {
  callAiService,
  createFixedWindowRateLimiter,
  handleAiError,
  publicAiErrorCode,
  registerAiRoutes,
  sanitizeContext,
  sanitizeFeedback,
}
