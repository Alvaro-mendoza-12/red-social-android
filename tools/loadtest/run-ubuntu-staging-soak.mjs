#!/usr/bin/env node
/**
 * NOVVIN Ubuntu Staging Long-Duration Soak Runner (2–6 hours)
 *
 * Designed to run against an isolated Ubuntu staging Docker Compose stack
 * (PostgreSQL + Redis + multi-instance API / Realtime Gateway / Signaling).
 *
 * Safety guards:
 *  1. Refuses to run unless NOVVIN_STAGING_ISOLATED=true is explicitly set.
 *  2. Blocks known production domains (novvin.app, conectate.com, supabase.co, etc.).
 *  3. Blocks non-loopback targets unless NOVVIN_ALLOW_NON_LOOPBACK_STAGING_HOST=true.
 *  4. Stop at the configured error budget and fail on any incorrect business response.
 *  5. SIGINT / SIGTERM during the measured workload drains in-flight operations and writes a failed summary.
 *
 * Example (2 hours, 50 workers sharing 10 synthetic accounts, 30s telemetry):
 *   NOVVIN_STAGING_ISOLATED=true \
 *   STAGING_API_URLS=http://127.0.0.1:3080,http://127.0.0.1:3081 \
 *   STAGING_REALTIME_URLS=http://127.0.0.1:3002,http://127.0.0.1:3003 \
 *   STAGING_SIGNALING_URLS=http://127.0.0.1:3004,http://127.0.0.1:3005 \
 *   STAGING_ADMIN_SYNC_TOKEN=... \
 *   STAGING_REALTIME_INTERNAL_SECRET=... \
 *   STAGING_LIVE_INTERNAL_TOKEN=... \
 *   SOAK_DURATION_HOURS=2 \
 *   SOAK_CONCURRENCY=50 \
 *   node tools/loadtest/run-ubuntu-staging-soak.mjs
 *
 * To reuse previously registered synthetic accounts, set SOAK_FIXTURE_CREDENTIALS_FILE
 * to a private JSON array of {email, password} and SOAK_ACCOUNTS to its length.
 * SOAK_DURATION_SECONDS selects a shorter smoke run. SOAK_WS_CLIENTS opens
 * recipient chat subscriptions. Set SOAK_ARRIVAL_RPS for a fixed arrival
 * rate with SOAK_CONCURRENCY as the maximum number of in-flight operations.
 */
import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { setTimeout as wait } from 'node:timers/promises'
import { observeStagingChats } from './staging-chat-delivery.mjs'
import { runStagingArrivals } from './staging-arrivals.mjs'

const FORBIDDEN_PRODUCTION_HOST_PATTERNS = [
  /(^|\.)novvinapp\.com$/i,
  /(^|\.)conectate\.tail9b267c\.ts\.net$/i,
  /^(?:conectate|100\.91\.84\.2|192\.168\.18\.22)$/i,
  /(^|\.)novvin\.app$/i,
  /(^|\.)conectate\.com$/i,
  /(^|\.)supabase\.co$/i,
  /(^|\.)vercel\.app$/i,
  /(^|\.)cloudflare\.com$/i,
]

function parseUrlList(raw, fallbackSingle = '') {
  const combined = String(raw || fallbackSingle || '').trim()
  if (!combined) return []
  return combined
    .split(',')
    .map((u) => u.trim().replace(/\/+$/, ''))
    .filter(Boolean)
}

function isLoopbackHost(hostname) {
  const h = String(hostname || '').trim().toLowerCase()
  return h === '127.0.0.1' || h === 'localhost' || h === '::1' || h === '[::1]'
}

function validateStagingTargets(urls) {
  if (String(process.env.NOVVIN_STAGING_ISOLATED || '').toLowerCase() !== 'true') {
    console.error('REFUSED: Set NOVVIN_STAGING_ISOLATED=true to confirm target is an isolated staging stack, never production.')
    process.exit(2)
  }
  const allowNonLoopback = String(process.env.NOVVIN_ALLOW_NON_LOOPBACK_STAGING_HOST || '').toLowerCase() === 'true'
  for (const rawUrl of urls) {
    let parsed
    try {
      parsed = new URL(rawUrl)
    } catch {
      console.error('REFUSED: Invalid target URL.')
      process.exit(2)
    }
    if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password || parsed.search || parsed.hash) {
      console.error('REFUSED: Expected an HTTP(S) service URL without credentials, query or fragment.')
      process.exit(2)
    }
    for (const pattern of FORBIDDEN_PRODUCTION_HOST_PATTERNS) {
      if (pattern.test(parsed.hostname.replace(/\.$/, ''))) {
        console.error(`REFUSED: Target host "${parsed.hostname}" matches forbidden production domain pattern ${pattern}.`)
        process.exit(2)
      }
    }
    if (!isLoopbackHost(parsed.hostname) && !allowNonLoopback) {
      console.error(
        `REFUSED: Target host "${parsed.hostname}" is not loopback. Set NOVVIN_ALLOW_NON_LOOPBACK_STAGING_HOST=true only for an isolated private staging host.`,
      )
      process.exit(2)
    }
  }
}

const apiUrls = parseUrlList(process.env.STAGING_API_URLS, process.env.STAGING_API_URL || 'http://127.0.0.1:3080')
const realtimeUrls = parseUrlList(process.env.STAGING_REALTIME_URLS, process.env.STAGING_REALTIME_URL || '')
const signalingUrls = parseUrlList(process.env.STAGING_SIGNALING_URLS, process.env.STAGING_SIGNALING_URL || '')
validateStagingTargets([...apiUrls, ...realtimeUrls, ...signalingUrls])

const apiUrl = apiUrls[0]
const adminToken = String(process.env.STAGING_ADMIN_SYNC_TOKEN || '').trim()
const realtimeSecret = String(process.env.STAGING_REALTIME_INTERNAL_SECRET || '').trim()
const liveToken = String(process.env.STAGING_LIVE_INTERNAL_TOKEN || '').trim()

function setting(name, fallback, min, max, integer = true) {
  const value = Number(process.env[name] ?? fallback)
  if (!Number.isFinite(value) || (integer && !Number.isInteger(value)) || value < min || value > max) {
    throw new Error(`Invalid ${name}: expected ${min}..${max}`)
  }
  return value
}

if (!apiUrls.length) throw new Error('At least one staging API is required')
if (process.env.SOAK_DURATION_SECONDS && process.env.SOAK_DURATION_HOURS) throw new Error('Choose one duration unit')
const durationSeconds = process.env.SOAK_DURATION_SECONDS !== undefined
  ? setting('SOAK_DURATION_SECONDS', 7200, 1, 43200)
  : setting('SOAK_DURATION_HOURS', 2, 0.01, 12, false) * 3600
const durationHours = durationSeconds / 3600
const concurrency = setting('SOAK_CONCURRENCY', 50, 5, 500)
const accountCount = setting('SOAK_ACCOUNTS', 10, 2, 100)
const sampleIntervalSec = setting('SOAK_SAMPLE_INTERVAL_SEC', 30, 1, 300)
const max5xxAbort = setting('SOAK_MAX_5XX_ABORT', 25, 1, 500)
const maxNetErrAbort = setting('SOAK_MAX_NET_ERR_ABORT', 50, 1, 1000)
const maxFunctionalAbort = setting('SOAK_MAX_FUNCTIONAL_FAILURES', 25, 1, 1000)
const wsClients = setting('SOAK_WS_CLIENTS', 0, 0, 100)
const arrivalRate = setting('SOAK_ARRIVAL_RPS', 0, 0, 2000)
if (wsClients > 0 && !realtimeUrls.length) throw new Error('SOAK_WS_CLIENTS requires a Realtime URL')
const outDir = path.resolve(process.env.SOAK_OUTPUT_DIR || './soak-staging-output')
fs.mkdirSync(outDir, { recursive: true })
const jsonlPath = path.join(outDir, `soak-${Date.now()}.jsonl`)
const summaryPath = path.resolve(process.env.SOAK_REPORT_PATH || path.join(outDir, 'soak-summary.json'))
fs.mkdirSync(path.dirname(summaryPath), { recursive: true })

function percentiles(samples) {
  if (!samples.length) return { count: 0, avgMs: 0, p50Ms: 0, p95Ms: 0, p99Ms: 0, maxMs: 0 }
  const sorted = [...samples].sort((a, b) => a - b)
  const at = (q) => sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * q))]
  const sum = sorted.reduce((acc, v) => acc + v, 0)
  return {
    count: sorted.length,
    avgMs: Number((sum / sorted.length).toFixed(2)),
    p50Ms: Number(at(0.50).toFixed(2)),
    p95Ms: Number(at(0.95).toFixed(2)),
    p99Ms: Number(at(0.99).toFixed(2)),
    maxMs: Number(sorted[sorted.length - 1].toFixed(2)),
  }
}

async function timedFetch(url, options = {}) {
  const started = performance.now()
  try {
    const res = await fetch(url, { signal: AbortSignal.timeout(options.timeoutMs || 15_000), ...options, redirect: 'manual' })
    const text = await res.text()
    let json = null
    try { json = text ? JSON.parse(text) : null } catch {}
    return { ok: res.ok, status: res.status, ms: performance.now() - started, json, text: text.slice(0, 512) }
  } catch (error) {
    return { ok: false, status: 0, ms: performance.now() - started, error: error.message }
  }
}

async function fetchMetrics(url, headers) {
  if (!url) return null
  const r = await timedFetch(`${url}/internal/metrics?format=json`, { headers })
  return r.ok ? r.json?.metrics || null : null
}

async function seedStagingUsers() {
  const tag = Date.now().toString(36)
  const credentialsFile = process.env.SOAK_FIXTURE_CREDENTIALS_FILE
  let fixtures
  if (credentialsFile) {
    fixtures = JSON.parse(fs.readFileSync(credentialsFile, 'utf8'))
    if (!Array.isArray(fixtures) || fixtures.length !== accountCount ||
        !fixtures.every(row => typeof row?.email === 'string' && /^[^@\s]+@staging\.novvin\.invalid$/i.test(row.email) &&
          typeof row.password === 'string' && row.password.length > 0) ||
        new Set(fixtures.map(row => row.email.toLowerCase())).size !== accountCount) {
      throw new Error('Expected one distinct synthetic staging credential per account')
    }
  }
  const users = []
  for (let i = 0; i < accountCount; i += 1) {
    const credentials = fixtures ? { email: fixtures[i].email, password: fixtures[i].password } : {
      email: `soak_${tag}_${i}@staging.novvin.invalid`,
      password: crypto.randomBytes(24).toString('base64url') + 'aA1!',
      displayName: `Soak User ${i}`,
      username: `soak_${tag}_${i}`.slice(0, 22),
    }
    const route = fixtures ? 'login' : 'register'
    const session = await timedFetch(`${apiUrl}/v1/auth/${route}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(credentials),
    })
    if (!session.ok || !session.json?.accessToken || !session.json?.user?.id) {
      throw new Error(`Staging ${route} failed at account ${i}: HTTP ${session.status}`)
    }
    users.push({ id: session.json.user.id, token: session.json.accessToken })
  }
  const chatIds = []
  for (let i = 0; i < Math.min(10, Math.floor(users.length / 2)); i += 1) {
    const r = await timedFetch(`${apiUrl}/v1/data/messages/chats`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${users[i * 2].token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ participantId: users[i * 2 + 1].id }),
    })
    const cid = r.json?.chat?.id || r.json?.id
    if (!r.ok || !cid) throw new Error(`Staging chat seed failed at ${i}: HTTP ${r.status}`)
    const chatUrl = `${apiUrl}/v1/data/messages/chats/${cid}`
    const pair = { id: cid, a: users[i * 2], b: users[i * 2 + 1] }
    let requestStatus = 'new'
    if (fixtures) {
      const request = await timedFetch(chatUrl + '/request', { headers: { Authorization: `Bearer ${pair.b.token}` } })
      if (!request.ok) throw new Error(`Staging recipient lookup failed at ${i}: HTTP ${request.status}`)
      requestStatus = request.json?.status
      if (requestStatus === 'open') {
        chatIds.push(pair)
        continue
      }
      if (requestStatus !== 'new' && requestStatus !== 'pending_incoming') {
        throw new Error(`Staging recipient cannot accept conversation at ${i}`)
      }
    }
    if (requestStatus === 'new') {
      const id = crypto.randomUUID()
      const intro = await timedFetch(chatUrl + '/messages', {
        method: 'POST',
        headers: { Authorization: `Bearer ${pair.a.token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ id, content: 'Staging introduction', contentType: 'text' }),
      })
      if (intro.status !== 201 || intro.json?.message?.id !== id) throw new Error(`Staging introduction failed at ${i}: HTTP ${intro.status}`)
    }
    const accept = await timedFetch(chatUrl + '/request', {
      method: 'POST',
      headers: { Authorization: `Bearer ${users[i * 2 + 1].token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ action: 'accept' }),
    })
    if (!accept.ok || accept.json?.status !== 'open') throw new Error(`Staging recipient acceptance failed at ${i}: HTTP ${accept.status}`)
    chatIds.push(pair)
  }
  return { users, chatIds }
}

async function main() {
  console.log(`[staging-soak] Targets=${apiUrls.join(',')} Duration=${durationHours}h Concurrency=${concurrency} Sample=${sampleIntervalSec}s`)
  for (const url of apiUrls) {
    const health = await timedFetch(`${url}/health`)
    if (!health.ok) throw new Error(`Staging health check failed at ${url}: HTTP ${health.status}`)
  }

  const { users, chatIds } = await seedStagingUsers()
  const recipients = await observeStagingChats(realtimeUrls, chatIds, wsClients)
  const delivery = { clients: wsClients, expected: 0, received: 0, missed: 0 }
  const durationMs = Math.round(durationHours * 3600 * 1000)
  const startedAt = performance.now()
  const deadline = startedAt + durationMs
  let sampleCount = 0
  let firstRss = null
  let lastRss = null
  let rssSamples = 0
  let firstP95 = null
  let lastP95 = null
  let totalReqs = 0
  let total2xx = 0
  let total429 = 0
  let totalLoadShed503 = 0
  let totalUnexpected5xx = 0
  let totalNetworkErrors = 0
  let totalFunctionalFailures = 0
  const operationNames = ['feed', 'profile', 'message', 'notifications']
  const operations = Object.fromEntries(operationNames.map(name =>
    [name, { attempted: 0, passed: 0, statuses: {} }]))
  let stop = false
  let stopReason = 'completed'
  let rrCounter = 0

  process.on('SIGINT', () => { stop = true; stopReason = 'SIGINT' })
  process.on('SIGTERM', () => { stop = true; stopReason = 'SIGTERM' })

  const windowHttpByOperation = Object.fromEntries(operationNames.map(name => [name, []]))
  let windowAcksWithMissedReceipt = []
  let windowDeliveryLatencies = []
  let windowCount = 0
  let windowStart = performance.now()

  let arrivals = null
  async function issue(idx, step) {
    const user = users[idx % users.length]
    const pair = chatIds[idx % chatIds.length]
    const authHdr = { Authorization: `Bearer ${user.token}` }
    const targetApi = apiUrls[(rrCounter++) % apiUrls.length]
    let res
    let valid
    const operationName = operationNames[(idx + step) % operationNames.length]
    const operation = operations[operationName]
    let missingDelivery = false
    if (operationName === 'feed') {
      res = await timedFetch(`${targetApi}/v1/data/feed?limit=20`, { headers: authHdr })
      valid = res.status === 200 && Array.isArray(res.json?.items)
    } else if (operationName === 'profile') {
      const target = users[(idx + 1) % users.length]
      res = await timedFetch(`${targetApi}/v1/data/profiles/${target.id}`, { headers: authHdr })
      valid = res.status === 200 && res.json?.profile?.id === target.id
    } else if (operationName === 'message') {
      const id = crypto.randomUUID()
      const reception = recipients.expect(pair.id, id)
      res = await timedFetch(`${targetApi}/v1/data/messages/chats/${pair.id}/messages`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${pair.a.token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ id, content: `soak-msg-${idx}-${step}`, contentType: 'text' }),
      })
      valid = res.status === 201 && res.json?.message?.id === id
      if (!valid) reception.cancel()
      const observed = await reception.received
      if (valid) {
        delivery.expected += observed.length
        for (const ms of observed) {
          if (ms === null) {
            missingDelivery = true
            delivery.missed += 1
          } else {
            delivery.received += 1
            windowDeliveryLatencies.push(ms)
          }
        }
      }
    } else {
      res = await timedFetch(`${targetApi}/v1/data/notifications?limit=15`, { headers: authHdr })
      valid = res.status === 200 && Array.isArray(res.json?.items)
    }
    operation.attempted += 1
    operation.statuses[res.status] = (operation.statuses[res.status] || 0) + 1
    if (valid) operation.passed += 1
    if (!valid || missingDelivery) totalFunctionalFailures += 1
    totalReqs += 1
    windowCount += 1
    windowHttpByOperation[operationName].push(res.ms)
    if (missingDelivery) windowAcksWithMissedReceipt.push(res.ms)
    if (res.status === 0) totalNetworkErrors += 1
    else if (res.status === 503 && res.json?.error === 'SERVICE_OVERLOADED') totalLoadShed503 += 1
    else if (res.status >= 500) totalUnexpected5xx += 1
    else if (res.status === 429) total429 += 1
    else if (res.status >= 200 && res.status < 300) total2xx += 1

    if (totalUnexpected5xx >= max5xxAbort) {
      stop = true
      stopReason = `circuit_breaker_5xx_threshold_${max5xxAbort}`
      return
    }
    if (totalNetworkErrors >= maxNetErrAbort) {
      stop = true
      stopReason = `circuit_breaker_network_error_threshold_${maxNetErrAbort}`
      return
    }
    if (totalFunctionalFailures >= maxFunctionalAbort) {
      stop = true
      stopReason = `circuit_breaker_functional_threshold_${maxFunctionalAbort}`
      return
    }
  }

  async function runWorkload() {
    if (arrivalRate) {
      arrivals = await runStagingArrivals({
        rate: arrivalRate, concurrency, start: startedAt, durationMs, stopped: () => stop,
        issue: index => issue(index % concurrency, 1 + Math.floor(index / concurrency)),
      })
      return
    }
    await Promise.all(Array.from({ length: concurrency }, async (_, idx) => {
      let step = 0
      while (!stop && performance.now() < deadline) {
        await issue(idx, ++step)
        if (!stop) await wait(150)
      }
    }))
  }

  async function sampleWindow() {
    const elapsedSec = Math.round((performance.now() - startedAt) / 1000)
    const winSec = Math.max(0.001, (performance.now() - windowStart) / 1000)
    const rps = Number((windowCount / winSec).toFixed(1))
    const pct = percentiles(Object.values(windowHttpByOperation).flat())
    const httpLatencyByOperation = Object.fromEntries(operationNames.map(name =>
      [name, percentiles(windowHttpByOperation[name])]))
    const httpAckWithMissedReceipt = percentiles(windowAcksWithMissedReceipt)
    const wsDelivery = percentiles(windowDeliveryLatencies)
    windowDeliveryLatencies = []
    const cumulative = { totalReqs, total2xx, total429, totalLoadShed503, totalUnexpected5xx, totalNetworkErrors, totalFunctionalFailures }
    for (const name of operationNames) windowHttpByOperation[name].length = 0
    windowAcksWithMissedReceipt = []
    windowCount = 0
    windowStart = performance.now()

    const [apiMetricsList, rtMetricsList, sigMetricsList] = await Promise.all([
      Promise.all(
        apiUrls.map((u) =>
          realtimeSecret
            ? fetchMetrics(u, { Authorization: `Bearer ${realtimeSecret}` })
            : adminToken
              ? fetchMetrics(u, { 'x-admin-sync-token': adminToken })
              : Promise.resolve(null),
        ),
      ),
      Promise.all(
        realtimeUrls.map((u) =>
          realtimeSecret ? fetchMetrics(u, { Authorization: `Bearer ${realtimeSecret}` }) : Promise.resolve(null),
        ),
      ),
      Promise.all(
        signalingUrls.map((u) =>
          liveToken ? fetchMetrics(u, { 'x-live-internal-token': liveToken }) : Promise.resolve(null),
        ),
      ),
    ])
    const apiM = apiMetricsList[0] || null
    const rtM = rtMetricsList[0] || null
    const sigM = sigMetricsList[0] || null
    const entry = {
      ts: new Date().toISOString(),
      elapsedSeconds: elapsedSec,
      windowRps: rps,
      latency: pct,
      httpLatencyByOperation,
      httpAckWithMissedReceipt,
      wsDelivery,
      cumulative,
      api: apiM,
      apiInstances: apiMetricsList.filter(Boolean),
      realtime: rtM,
      realtimeInstances: rtMetricsList.filter(Boolean),
      signaling: sigM,
      signalingInstances: sigMetricsList.filter(Boolean),
    }
    sampleCount += 1
    if (apiM?.memoryRssBytes > 0) {
      firstRss ??= apiM.memoryRssBytes
      lastRss = apiM.memoryRssBytes
      rssSamples += 1
    }
    firstP95 ??= pct.p95Ms
    lastP95 = pct.p95Ms
    fs.appendFileSync(jsonlPath, `${JSON.stringify(entry)}\n`, 'utf8')
    console.log(`[staging-soak] t=${elapsedSec}s rps=${rps} p95=${pct.p95Ms}ms p99=${pct.p99Ms}ms 5xx=${totalUnexpected5xx} apiRssMB=${apiM ? (apiM.memoryRssBytes / 1048576).toFixed(1) : 'n/a'}`)
  }

  const samplingDone = new AbortController()
  const sampler = (async () => {
    while (!samplingDone.signal.aborted) {
      try {
        await wait(sampleIntervalSec * 1000, undefined, { signal: samplingDone.signal })
      } catch (error) {
        if (error.name === 'AbortError') break
        throw error
      }
      if (windowCount) await sampleWindow()
    }
  })()

  try {
    await runWorkload()
  } finally {
    samplingDone.abort()
    recipients.close()
    await sampler
  }
  if (windowCount) await sampleWindow()

  const memoryDriftMB = rssSamples >= 2
    ? Number(((lastRss - firstRss) / 1048576).toFixed(1))
    : null
  const p95DriftMs = sampleCount >= 2
    ? Number((lastP95 - firstP95).toFixed(2))
    : null

  const summary = {
    completedAt: new Date().toISOString(),
    stopReason,
    targetApiUrls: apiUrls,
    targetRealtimeUrls: realtimeUrls,
    targetSignalingUrls: signalingUrls,
    requestedDurationHours: durationHours,
    actualDurationSeconds: Math.round((performance.now() - startedAt) / 1000),
    concurrency,
    totals: { totalReqs, total2xx, total429, totalLoadShed503, totalUnexpected5xx, totalNetworkErrors, totalFunctionalFailures },
    operations,
    delivery,
    sampleCount,
    workload: arrivalRate ? 'constant-arrival-rate' : 'closed-loop',
    arrivalRate,
    arrivals,
    syntheticAccounts: users.length,
    droppedArrivalIterations: arrivals ? arrivals.late + arrivals.atCapacity : null,
    apiRssDriftMB: memoryDriftMB,
    p95DriftMs,
    jsonlFile: jsonlPath,
    passed: totalFunctionalFailures === 0 && Object.values(operations).every(op => op.passed > 0) &&
      (wsClients === 0 || delivery.expected > 0) && (!arrivals || arrivals.dispatched === arrivals.planned) && stopReason === 'completed',
  }
  fs.writeFileSync(summaryPath, JSON.stringify(summary, null, 2), 'utf8')
  console.log(JSON.stringify(summary, null, 2))
  if (!summary.passed) process.exitCode = 1
}

main().catch((err) => {
  fs.writeFileSync(summaryPath, JSON.stringify({
    completedAt: new Date().toISOString(), passed: false, stopReason: 'runner_failed',
  }, null, 2), 'utf8')
  console.error('[staging-soak] FATAL:', err)
  process.exitCode = 1
})
