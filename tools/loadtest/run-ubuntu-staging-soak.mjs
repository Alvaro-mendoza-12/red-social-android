#!/usr/bin/env node
/**
 * NOVVIN Ubuntu Staging Long-Duration Soak Runner (2–6 hours)
 *
 * Designed to run against an isolated Ubuntu staging Docker Compose stack
 * (PostgreSQL + Redis 7.4.8 + multi-instance API / Realtime Gateway / Signaling).
 *
 * Safety guards:
 *  1. Refuses to run unless NOVVIN_STAGING_ISOLATED=true is explicitly set.
 *  2. Blocks known production domains (novvin.app, conectate.com, supabase.co, etc.).
 *  3. Blocks non-loopback targets unless NOVVIN_ALLOW_NON_LOOPBACK_STAGING_HOST=true.
 *  4. Circuit-breaker auto-stop if unexpected 5xx or network errors exceed threshold.
 *  5. Clean SIGINT / SIGTERM handling that always writes soak-summary.json.
 *
 * Example (2 hours, 50 concurrent users, 30s telemetry):
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
 */
import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'

const FORBIDDEN_PRODUCTION_HOST_PATTERNS = [
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
      console.error(`REFUSED: Invalid target URL "${rawUrl}".`)
      process.exit(2)
    }
    for (const pattern of FORBIDDEN_PRODUCTION_HOST_PATTERNS) {
      if (pattern.test(parsed.hostname)) {
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

const durationHours = Math.max(0.01, Math.min(Number(process.env.SOAK_DURATION_HOURS || 2), 12))
const concurrency = Math.max(5, Math.min(Number(process.env.SOAK_CONCURRENCY || 50), 500))
const sampleIntervalSec = Math.max(5, Math.min(Number(process.env.SOAK_SAMPLE_INTERVAL_SEC || 30), 300))
const max5xxAbort = Math.max(5, Math.min(Number(process.env.SOAK_MAX_5XX_ABORT || 25), 500))
const maxNetErrAbort = Math.max(10, Math.min(Number(process.env.SOAK_MAX_NET_ERR_ABORT || 50), 1000))
const outDir = path.resolve(process.env.SOAK_OUTPUT_DIR || './soak-staging-output')
fs.mkdirSync(outDir, { recursive: true })
const jsonlPath = path.join(outDir, `soak-${Date.now()}.jsonl`)
const summaryPath = path.join(outDir, 'soak-summary.json')

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
    const res = await fetch(url, { signal: AbortSignal.timeout(options.timeoutMs || 15_000), ...options })
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
  const count = Math.max(10, Math.min(concurrency, 40))
  const tag = Date.now().toString(36)
  const users = []
  for (let i = 0; i < count; i += 1) {
    const email = `soak_${tag}_${i}@staging.novvin.invalid`
    const reg = await timedFetch(`${apiUrl}/v1/auth/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        email,
        password: 'StagingSoakPassword220!',
        displayName: `Soak User ${i}`,
        username: `soak_${tag}_${i}`.slice(0, 22),
      }),
    })
    if (!reg.ok || !reg.json?.accessToken) {
      throw new Error(`Staging user seed failed at ${i}: HTTP ${reg.status} ${reg.text}`)
    }
    users.push({ id: reg.json.user.id, token: reg.json.accessToken })
  }
  const chatIds = []
  for (let i = 0; i < Math.min(10, Math.floor(users.length / 2)); i += 1) {
    const r = await timedFetch(`${apiUrl}/v1/data/messages/chats`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${users[i * 2].token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ participantId: users[i * 2 + 1].id }),
    })
    const cid = r.json?.chat?.id || r.json?.id
    if (cid) chatIds.push({ id: cid, a: users[i * 2], b: users[i * 2 + 1] })
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
  const durationMs = Math.round(durationHours * 3600 * 1000)
  const startedAt = Date.now()
  const deadline = startedAt + durationMs
  const samples = []
  let totalReqs = 0
  let total2xx = 0
  let total429 = 0
  let totalLoadShed503 = 0
  let totalUnexpected5xx = 0
  let totalNetworkErrors = 0
  let stop = false
  let stopReason = 'completed'
  let rrCounter = 0

  process.on('SIGINT', () => { stop = true; stopReason = 'SIGINT' })
  process.on('SIGTERM', () => { stop = true; stopReason = 'SIGTERM' })

  let windowLatencies = []
  let windowCount = 0
  let windowStart = performance.now()

  const workers = Array.from({ length: concurrency }, async (_, idx) => {
    const user = users[idx % users.length]
    const pair = chatIds[idx % chatIds.length]
    const authHdr = { Authorization: `Bearer ${user.token}` }
    let step = 0
    while (!stop && Date.now() < deadline) {
      step += 1
      const targetApi = apiUrls[(rrCounter++) % apiUrls.length]
      const mod = (idx + step) % 4
      let res
      if (mod === 0) {
        res = await timedFetch(`${targetApi}/v1/data/feed?limit=20`, { headers: authHdr })
      } else if (mod === 1) {
        const target = users[(idx + 1) % users.length]
        res = await timedFetch(`${targetApi}/v1/data/profiles/${target.id}`, { headers: authHdr })
      } else if (mod === 2 && pair) {
        res = await timedFetch(`${targetApi}/v1/data/messages/chats/${pair.id}/messages`, {
          method: 'POST',
          headers: { Authorization: `Bearer ${pair.a.token}`, 'Content-Type': 'application/json' },
          body: JSON.stringify({ id: crypto.randomUUID(), content: `soak-msg-${idx}-${step}`, contentType: 'text' }),
        })
      } else {
        res = await timedFetch(`${targetApi}/v1/data/notifications?limit=15`, { headers: authHdr })
      }
      totalReqs += 1
      windowCount += 1
      windowLatencies.push(res.ms)
      if (res.status === 0) totalNetworkErrors += 1
      else if (res.status === 503 && res.json?.error === 'SERVICE_OVERLOADED') totalLoadShed503 += 1
      else if (res.status >= 500) totalUnexpected5xx += 1
      else if (res.status === 429) total429 += 1
      else if (res.status >= 200 && res.status < 300) total2xx += 1

      if (totalUnexpected5xx >= max5xxAbort) {
        stop = true
        stopReason = `circuit_breaker_5xx_threshold_${max5xxAbort}`
        break
      }
      if (totalNetworkErrors >= maxNetErrAbort) {
        stop = true
        stopReason = `circuit_breaker_network_error_threshold_${maxNetErrAbort}`
        break
      }
      await new Promise((r) => setTimeout(r, 150))
    }
  })

  const sampler = (async () => {
    while (!stop && Date.now() < deadline) {
      await new Promise((r) => setTimeout(r, sampleIntervalSec * 1000))
      const elapsedSec = Math.round((Date.now() - startedAt) / 1000)
      const winSec = Math.max(0.001, (performance.now() - windowStart) / 1000)
      const rps = Number((windowCount / winSec).toFixed(1))
      const pct = percentiles(windowLatencies)
      windowLatencies = []
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
        cumulative: { totalReqs, total2xx, total429, totalLoadShed503, totalUnexpected5xx, totalNetworkErrors },
        api: apiM,
        apiInstances: apiMetricsList.filter(Boolean),
        realtime: rtM,
        realtimeInstances: rtMetricsList.filter(Boolean),
        signaling: sigM,
        signalingInstances: sigMetricsList.filter(Boolean),
      }
      samples.push(entry)
      fs.appendFileSync(jsonlPath, `${JSON.stringify(entry)}\n`, 'utf8')
      console.log(`[staging-soak] t=${elapsedSec}s rps=${rps} p95=${pct.p95Ms}ms p99=${pct.p99Ms}ms 5xx=${totalUnexpected5xx} apiRssMB=${apiM ? (apiM.memoryRssBytes / 1048576).toFixed(1) : 'n/a'}`)
    }
  })()

  await Promise.all([...workers, sampler])

  const rssSeries = samples.map((s) => s.api?.memoryRssBytes || 0).filter((v) => v > 0)
  const memoryDriftMB = rssSeries.length >= 2
    ? Number(((rssSeries.at(-1) - rssSeries[0]) / 1048576).toFixed(1))
    : null
  const p95Series = samples.map((s) => s.latency?.p95Ms || 0).filter((v) => v > 0)
  const p95DriftMs = p95Series.length >= 2
    ? Number((p95Series.at(-1) - p95Series[0]).toFixed(2))
    : null

  const summary = {
    completedAt: new Date().toISOString(),
    stopReason,
    targetApiUrls: apiUrls,
    targetRealtimeUrls: realtimeUrls,
    targetSignalingUrls: signalingUrls,
    requestedDurationHours: durationHours,
    actualDurationSeconds: Math.round((Date.now() - startedAt) / 1000),
    concurrency,
    totals: { totalReqs, total2xx, total429, totalLoadShed503, totalUnexpected5xx, totalNetworkErrors },
    sampleCount: samples.length,
    apiRssDriftMB: memoryDriftMB,
    p95DriftMs,
    jsonlFile: jsonlPath,
    passed: totalUnexpected5xx === 0 && totalNetworkErrors === 0 && stopReason === 'completed',
  }
  fs.writeFileSync(summaryPath, JSON.stringify(summary, null, 2), 'utf8')
  console.log(JSON.stringify(summary, null, 2))
  if (!summary.passed) process.exitCode = 1
}

main().catch((err) => {
  console.error('[staging-soak] FATAL:', err)
  process.exit(1)
})
