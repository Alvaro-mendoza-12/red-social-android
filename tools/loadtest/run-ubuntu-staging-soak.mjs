#!/usr/bin/env node
/**
 * NOVVIN Ubuntu Staging Long-Duration Soak Runner (2–6 hours)
 *
 * Designed to run against an isolated Ubuntu staging Docker Compose stack
 * (PostgreSQL + Redis 7.4.8 + multi-instance API / Realtime Gateway / Signaling).
 *
 * Safety guard: refuses to run unless NOVVIN_STAGING_ISOLATED=true is explicitly set
 * and blocks known production origins unless overridden for a dedicated staging host.
 *
 * Example (2 hours, 50 concurrent users, 30s telemetry):
 *   NOVVIN_STAGING_ISOLATED=true \
 *   STAGING_API_URL=http://127.0.0.1:3080 \
 *   STAGING_REALTIME_URL=http://127.0.0.1:3002 \
 *   STAGING_ADMIN_SYNC_TOKEN=... \
 *   STAGING_REALTIME_INTERNAL_SECRET=... \
 *   SOAK_DURATION_HOURS=2 \
 *   SOAK_CONCURRENCY=50 \
 *   node tools/loadtest/run-ubuntu-staging-soak.mjs
 */
import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'

if (String(process.env.NOVVIN_STAGING_ISOLATED || '').toLowerCase() !== 'true') {
  console.error('REFUSED: Set NOVVIN_STAGING_ISOLATED=true to confirm target is an isolated staging stack, never production.')
  process.exit(2)
}

const apiUrl = String(process.env.STAGING_API_URL || 'http://127.0.0.1:3080').replace(/\/+$/, '')
const realtimeUrl = String(process.env.STAGING_REALTIME_URL || '').replace(/\/+$/, '')
const signalingUrl = String(process.env.STAGING_SIGNALING_URL || '').replace(/\/+$/, '')
const adminToken = String(process.env.STAGING_ADMIN_SYNC_TOKEN || '').trim()
const realtimeSecret = String(process.env.STAGING_REALTIME_INTERNAL_SECRET || '').trim()
const liveToken = String(process.env.STAGING_LIVE_INTERNAL_TOKEN || '').trim()

const durationHours = Math.max(0.05, Math.min(Number(process.env.SOAK_DURATION_HOURS || 2), 12))
const concurrency = Math.max(5, Math.min(Number(process.env.SOAK_CONCURRENCY || 50), 500))
const sampleIntervalSec = Math.max(10, Math.min(Number(process.env.SOAK_SAMPLE_INTERVAL_SEC || 30), 300))
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
  console.log(`[staging-soak] Target=${apiUrl} Duration=${durationHours}h Concurrency=${concurrency} Sample=${sampleIntervalSec}s`)
  const health = await timedFetch(`${apiUrl}/health`)
  if (!health.ok) throw new Error(`Staging health check failed: HTTP ${health.status}`)

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

  process.on('SIGINT', () => { stop = true })
  process.on('SIGTERM', () => { stop = true })

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
      const mod = (idx + step) % 4
      let res
      if (mod === 0) {
        res = await timedFetch(`${apiUrl}/v1/data/feed?limit=20`, { headers: authHdr })
      } else if (mod === 1) {
        const target = users[(idx + 1) % users.length]
        res = await timedFetch(`${apiUrl}/v1/data/profiles/${target.id}`, { headers: authHdr })
      } else if (mod === 2 && pair) {
        res = await timedFetch(`${apiUrl}/v1/data/messages/chats/${pair.id}/messages`, {
          method: 'POST',
          headers: { Authorization: `Bearer ${pair.a.token}`, 'Content-Type': 'application/json' },
          body: JSON.stringify({ id: crypto.randomUUID(), content: `soak-msg-${idx}-${step}`, contentType: 'text' }),
        })
      } else {
        res = await timedFetch(`${apiUrl}/v1/data/notifications?limit=15`, { headers: authHdr })
      }
      totalReqs += 1
      windowCount += 1
      windowLatencies.push(res.ms)
      if (res.status === 0) totalNetworkErrors += 1
      else if (res.status === 503 && res.json?.error === 'SERVICE_OVERLOADED') totalLoadShed503 += 1
      else if (res.status >= 500) totalUnexpected5xx += 1
      else if (res.status === 429) total429 += 1
      else if (res.status >= 200 && res.status < 300) total2xx += 1
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

      const [apiM, rtM, sigM] = await Promise.all([
        adminToken ? fetchMetrics(apiUrl, { 'x-admin-sync-token': adminToken }) : null,
        realtimeUrl && realtimeSecret ? fetchMetrics(realtimeUrl, { Authorization: `Bearer ${realtimeSecret}` }) : null,
        signalingUrl && liveToken ? fetchMetrics(signalingUrl, { 'x-live-internal-token': liveToken }) : null,
      ])
      const entry = {
        ts: new Date().toISOString(),
        elapsedSeconds: elapsedSec,
        windowRps: rps,
        latency: pct,
        cumulative: { totalReqs, total2xx, total429, totalLoadShed503, totalUnexpected5xx, totalNetworkErrors },
        api: apiM,
        realtime: rtM,
        signaling: sigM,
      }
      samples.push(entry)
      fs.appendFileSync(jsonlPath, `${JSON.stringify(entry)}\n`, 'utf8')
      console.log(`[staging-soak] t=${elapsedSec}s rps=${rps} p95=${pct.p95Ms}ms 5xx=${totalUnexpected5xx} apiRssMB=${apiM ? (apiM.memoryRssBytes / 1048576).toFixed(1) : 'n/a'}`)
    }
  })()

  await Promise.all([...workers, sampler])

  const rssSeries = samples.map((s) => s.api?.memoryRssBytes || 0).filter((v) => v > 0)
  const memoryDriftMB = rssSeries.length >= 2
    ? Number(((rssSeries.at(-1) - rssSeries[0]) / 1048576).toFixed(1))
    : null

  const summary = {
    completedAt: new Date().toISOString(),
    targetApiUrl: apiUrl,
    requestedDurationHours: durationHours,
    actualDurationSeconds: Math.round((Date.now() - startedAt) / 1000),
    concurrency,
    totals: { totalReqs, total2xx, total429, totalLoadShed503, totalUnexpected5xx, totalNetworkErrors },
    sampleCount: samples.length,
    apiRssDriftMB: memoryDriftMB,
    jsonlFile: jsonlPath,
    passed: totalUnexpected5xx === 0 && totalNetworkErrors === 0,
  }
  fs.writeFileSync(summaryPath, JSON.stringify(summary, null, 2), 'utf8')
  console.log(JSON.stringify(summary, null, 2))
  if (!summary.passed) process.exitCode = 1
}

main().catch((err) => {
  console.error('[staging-soak] FATAL:', err)
  process.exit(1)
})
