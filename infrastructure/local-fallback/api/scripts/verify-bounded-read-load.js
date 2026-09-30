'use strict'

// Read-only load probe. No signups, writes, unbounded concurrency, or tokens in output.
const fs = require('node:fs')
const { performance } = require('node:perf_hooks')

async function runReadLoad({ baseUrl, requests = 48, concurrency = 8, timeoutMs = 8000,
  path = '/ready', fetchImpl = fetch }) {
  const base = new URL(baseUrl)
  if (!['http:', 'https:'].includes(base.protocol) || base.username || base.password ||
      base.pathname !== '/' || base.search || base.hash) throw new Error('Invalid origin')
  if (!['/health', '/ready'].includes(path)) throw new Error('Only health/readiness reads are allowed')
  for (const [value, max] of [[requests, 200], [concurrency, 24], [timeoutMs, 15000]]) {
    if (!Number.isSafeInteger(value) || value < 1 || value > max) throw new Error('Invalid bounded load')
  }
  let next = 0
  const samples = []
  const started = performance.now()
  await Promise.all(Array.from({ length: Math.min(concurrency, requests) }, async () => {
    while (next < requests) {
      next++
      const at = performance.now()
      try {
        const response = await fetchImpl(new URL(path, base), {
          redirect: 'error', signal: AbortSignal.timeout(timeoutMs),
        })
        const body = await response.text()
        if (body.length > 65536) throw new Error('Response too large')
        const ok = response.status === 200 && JSON.parse(body).ok === true
        const serverMs = Number.parseFloat(response.headers.get('x-response-time'))
        samples.push({ ok, status: response.status, ms: performance.now() - at,
          serverMs: Number.isFinite(serverMs) ? serverMs : null })
      } catch {
        samples.push({ ok: false, status: null, ms: performance.now() - at, serverMs: null })
      }
    }
  }))
  const times = samples.map(s => s.ms).sort((a, b) => a - b)
  const percentile = p => Math.round(times[Math.ceil(times.length * p) - 1])
  return {
    ok: samples.every(s => s.ok), path, requests, concurrency,
    successes: samples.filter(s => s.ok).length,
    failures: samples.filter(s => !s.ok).length,
    p50Ms: percentile(.5), p95Ms: percentile(.95), maxMs: Math.round(times.at(-1)),
    elapsedMs: Math.round(performance.now() - started),
    maxServerMs: Math.max(0, ...samples.map(s => s.serverMs || 0)),
    scope: 'Bounded concurrent health/readiness reads; not concurrent logged-in users, writes, media or a capacity guarantee',
  }
}

if (require.main === module) {
  runReadLoad({ baseUrl: process.env.SMOKE_API_BASE_URL,
    requests: Number(process.env.LOAD_REQUESTS || 48), concurrency: Number(process.env.LOAD_CONCURRENCY || 8) })
    .then(report => {
      if (process.env.LOAD_REPORT_FILE) fs.writeFileSync(process.env.LOAD_REPORT_FILE, JSON.stringify(report, null, 2))
      console.log(JSON.stringify(report, null, 2))
      if (!report.ok) process.exitCode = 1
    }).catch(() => { console.error('Bounded read load failed; verify origin and numeric limits.'); process.exitCode = 1 })
}
module.exports = { runReadLoad }
