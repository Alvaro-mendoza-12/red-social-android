const crypto = require('crypto')
const { monitorEventLoopDelay } = require('perf_hooks')
const { stableKey } = require('./request-security')

const W3C_TRACEPARENT_RE = /^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$/i

function parseOrCreateTraceparent(rawHeader) {
  const value = String(rawHeader || '').trim()
  const match = W3C_TRACEPARENT_RE.exec(value)
  if (match && !/^0+$/.test(match[1]) && !/^0+$/.test(match[2])) {
    const traceId = match[1].toLowerCase()
    const parentSpanId = match[2].toLowerCase()
    const flags = match[3].toLowerCase()
    const spanId = crypto.randomBytes(8).toString('hex')
    return {
      traceId,
      parentSpanId,
      spanId,
      flags,
      traceparent: `00-${traceId}-${spanId}-${flags}`,
    }
  }
  const traceId = crypto.randomBytes(16).toString('hex')
  const spanId = crypto.randomBytes(8).toString('hex')
  return {
    traceId,
    parentSpanId: null,
    spanId,
    flags: '01',
    traceparent: `00-${traceId}-${spanId}-01`,
  }
}

function elapsedMilliseconds(startedAt) {
  return Number(process.hrtime.bigint() - startedAt) / 1_000_000
}

function createApiMetricsCollector({ pool = null, sampleSize = 2048 } = {}) {
  const maxSamples = Math.max(64, Math.min(Number(sampleSize) || 2048, 16_384))
  const latencies = new Float64Array(maxSamples)
  let latencyCursor = 0
  let latencyFilled = 0
  const startedAtMs = Date.now()
  const loopMonitor = monitorEventLoopDelay({ resolution: 20 })
  loopMonitor.enable()
  const counters = {
    requestsTotal: 0,
    inFlight: 0,
    status2xx: 0,
    status3xx: 0,
    status4xx: 0,
    status5xx: 0,
    rateLimitedTotal: 0,
    loadShedTotal: 0,
    slowRequestsTotal: 0,
  }
  const routeCounters = new Map()

  function percentile(sorted, ratio) {
    if (sorted.length === 0) return 0
    const idx = Math.min(sorted.length - 1, Math.floor(sorted.length * ratio))
    return Number(sorted[idx].toFixed(2))
  }

  return {
    beginRequest() {
      counters.inFlight += 1
    },
    recordLoadShed() {
      counters.loadShedTotal += 1
    },
    endRequest({ route, statusCode, durationMs, slow }) {
      counters.inFlight = Math.max(0, counters.inFlight - 1)
      counters.requestsTotal += 1
      if (statusCode >= 500) counters.status5xx += 1
      else if (statusCode >= 400) counters.status4xx += 1
      else if (statusCode >= 300) counters.status3xx += 1
      else counters.status2xx += 1
      if (statusCode === 429) counters.rateLimitedTotal += 1
      if (slow) counters.slowRequestsTotal += 1

      latencies[latencyCursor] = durationMs
      latencyCursor = (latencyCursor + 1) % maxSamples
      if (latencyFilled < maxSamples) latencyFilled += 1

      const key = String(route || 'unmatched').slice(0, 120)
      if (!routeCounters.has(key) && routeCounters.size >= 256) {
        routeCounters.delete(routeCounters.keys().next().value)
      }
      const stat = routeCounters.get(key) || { count: 0, errors5xx: 0, totalMs: 0 }
      stat.count += 1
      if (statusCode >= 500) stat.errors5xx += 1
      stat.totalMs += durationMs
      routeCounters.set(key, stat)
    },
    snapshot() {
      const slice = Array.from(latencies.subarray(0, latencyFilled)).sort((a, b) => a - b)
      const mem = process.memoryUsage()
      return {
        uptimeSeconds: Math.round((Date.now() - startedAtMs) / 1000),
        requestsTotal: counters.requestsTotal,
        inFlight: counters.inFlight,
        status2xx: counters.status2xx,
        status3xx: counters.status3xx,
        status4xx: counters.status4xx,
        status5xx: counters.status5xx,
        rateLimitedTotal: counters.rateLimitedTotal,
        loadShedTotal: counters.loadShedTotal,
        slowRequestsTotal: counters.slowRequestsTotal,
        latencyP50Ms: percentile(slice, 0.5),
        latencyP95Ms: percentile(slice, 0.95),
        latencyP99Ms: percentile(slice, 0.99),
        eventLoopLagP95Ms: Number((loopMonitor.percentile(95) / 1e6).toFixed(2)),
        memoryRssBytes: mem.rss,
        memoryHeapUsedBytes: mem.heapUsed,
        dbPool: pool ? {
          total: Number(pool.totalCount || 0),
          idle: Number(pool.idleCount || 0),
          waiting: Number(pool.waitingCount || 0),
        } : null,
      }
    },
    formatPrometheus() {
      const s = this.snapshot()
      const lines = [
        '# TYPE novvin_api_requests_total counter',
        `novvin_api_requests_total ${s.requestsTotal}`,
        '# TYPE novvin_api_requests_in_flight gauge',
        `novvin_api_requests_in_flight ${s.inFlight}`,
        '# TYPE novvin_api_responses_total counter',
        `novvin_api_responses_total{class="2xx"} ${s.status2xx}`,
        `novvin_api_responses_total{class="3xx"} ${s.status3xx}`,
        `novvin_api_responses_total{class="4xx"} ${s.status4xx}`,
        `novvin_api_responses_total{class="5xx"} ${s.status5xx}`,
        '# TYPE novvin_api_rate_limited_total counter',
        `novvin_api_rate_limited_total ${s.rateLimitedTotal}`,
        '# TYPE novvin_api_load_shed_total counter',
        `novvin_api_load_shed_total ${s.loadShedTotal}`,
        '# TYPE novvin_api_slow_requests_total counter',
        `novvin_api_slow_requests_total ${s.slowRequestsTotal}`,
        '# TYPE novvin_api_latency_ms gauge',
        `novvin_api_latency_ms{quantile="0.5"} ${s.latencyP50Ms}`,
        `novvin_api_latency_ms{quantile="0.95"} ${s.latencyP95Ms}`,
        `novvin_api_latency_ms{quantile="0.99"} ${s.latencyP99Ms}`,
        '# TYPE novvin_api_event_loop_lag_p95_ms gauge',
        `novvin_api_event_loop_lag_p95_ms ${s.eventLoopLagP95Ms}`,
        '# TYPE novvin_api_memory_rss_bytes gauge',
        `novvin_api_memory_rss_bytes ${s.memoryRssBytes}`,
        '# TYPE novvin_api_memory_heap_used_bytes gauge',
        `novvin_api_memory_heap_used_bytes ${s.memoryHeapUsedBytes}`,
      ]
      if (s.dbPool) {
        lines.push(
          '# TYPE novvin_api_db_pool_connections gauge',
          `novvin_api_db_pool_connections{state="total"} ${s.dbPool.total}`,
          `novvin_api_db_pool_connections{state="idle"} ${s.dbPool.idle}`,
          `novvin_api_db_pool_connections{state="waiting"} ${s.dbPool.waiting}`,
        )
      }
      return `${lines.join('\n')}\n`
    },
  }
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
  if (req.observabilityRoute === '/rest/v1/rpc/delete_own_account' ||
      req.observabilityRoute === '/rest/v1/rpc/:operation') return req.observabilityRoute
  const base = typeof req.baseUrl === 'string' ? req.baseUrl : ''
  const route = req.route?.path
  if (typeof route === 'string') return `${base}${route}` || '/'
  return 'unmatched'
}

function createRequestObservabilityMiddleware(options = {}) {
  const slowRequestMs = Math.max(100, Math.min(Number(options.slowRequestMs || 1_500), 60_000))
  const logger = options.logger || console
  const metrics = options.metrics || null

  return (req, res, next) => {
    const startedAt = process.hrtime.bigint()
    const originalWriteHead = res.writeHead
    let headersWritten = false
    let finished = false
    if (metrics) metrics.beginRequest()

    res.writeHead = function observedWriteHead(...args) {
      if (!headersWritten && !res.headersSent) {
        headersWritten = true
        const durationMs = elapsedMilliseconds(startedAt)
        res.setHeader('Server-Timing', `app;dur=${durationMs.toFixed(1)}`)
        res.setHeader('X-Response-Time', `${durationMs.toFixed(1)}ms`)
      }
      return originalWriteHead.apply(this, args)
    }

    const complete = () => {
      if (finished) return
      finished = true
      const durationMs = elapsedMilliseconds(startedAt)
      const slow = durationMs >= slowRequestMs
      const route = routeName(req)
      if (metrics) {
        metrics.endRequest({ route, statusCode: res.statusCode, durationMs, slow })
      }
      if (!slow) return
      logger.warn('[api] slow request', {
        requestId: req.requestId,
        correlationId: req.correlationId || req.requestId,
        traceId: req.traceId || null,
        method: req.method,
        route,
        status: res.statusCode,
        durationMs: Math.round(durationMs),
        rangeRequest: typeof req.headers?.range === 'string',
      })
    }

    res.once('finish', complete)
    res.once('close', complete)
    next()
  }
}

module.exports = {
  createApiMetricsCollector,
  createRequestObservabilityMiddleware,
  parseOrCreateTraceparent,
  routeName,
  safeErrorLogDetails,
}
