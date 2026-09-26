const { stableKey } = require('./request-security')

function elapsedMilliseconds(startedAt) {
  return Number(process.hrtime.bigint() - startedAt) / 1_000_000
}

function safeErrorLogDetails(error, options = {}) {
  const diagnosticMessage = String(error?.message || options.fallbackMessage || 'Unexpected error').slice(0, 240)
  const production = options.production === undefined
    ? String(process.env.NODE_ENV || '').trim().toLowerCase() === 'production'
    : options.production === true
  const details = {
    code: String(error?.code || options.fallbackCode || 'INTERNAL_ERROR')
      .replace(/[^a-z0-9_.-]/gi, '_')
      .slice(0, 80),
    messageRef: stableKey(diagnosticMessage),
  }
  if (!production) details.message = diagnosticMessage
  return details
}

function routeName(req) {
  const base = typeof req.baseUrl === 'string' ? req.baseUrl : ''
  const route = req.route?.path
  if (typeof route === 'string') return `${base}${route}` || '/'
  return 'unmatched'
}

function createRequestObservabilityMiddleware(options = {}) {
  const slowRequestMs = Math.max(100, Math.min(Number(options.slowRequestMs || 1_500), 60_000))
  const logger = options.logger || console

  return (req, res, next) => {
    const startedAt = process.hrtime.bigint()
    const originalWriteHead = res.writeHead
    let headersWritten = false

    res.writeHead = function observedWriteHead(...args) {
      if (!headersWritten && !res.headersSent) {
        headersWritten = true
        const durationMs = elapsedMilliseconds(startedAt)
        res.setHeader('Server-Timing', `app;dur=${durationMs.toFixed(1)}`)
        res.setHeader('X-Response-Time', `${durationMs.toFixed(1)}ms`)
      }
      return originalWriteHead.apply(this, args)
    }

    res.once('finish', () => {
      const durationMs = elapsedMilliseconds(startedAt)
      if (durationMs < slowRequestMs) return
      logger.warn('[api] slow request', {
        requestId: req.requestId,
        method: req.method,
        route: routeName(req),
        status: res.statusCode,
        durationMs: Math.round(durationMs),
        rangeRequest: typeof req.headers?.range === 'string',
      })
    })
    next()
  }
}

module.exports = {
  createRequestObservabilityMiddleware,
  routeName,
  safeErrorLogDetails,
}
