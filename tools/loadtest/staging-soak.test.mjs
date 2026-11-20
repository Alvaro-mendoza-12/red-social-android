import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import http from 'node:http'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'
import test from 'node:test'
import { runStagingArrivals } from './staging-arrivals.mjs'
import { setTimeout as wait } from 'node:timers/promises'

const script = fileURLToPath(new URL('./run-ubuntu-staging-soak.mjs', import.meta.url))

async function runFixture(t, { writeStatus = 201, wrongId = false, seedRejected = false,
  duration = '1', silentRecipient = false, arrivalRate = '0' } = {}) {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'novvin-soak-contract-'))
  t.after(() => rm(dir, { recursive: true, force: true }))
  let users = 0
  let chats = 0
  const server = http.createServer(async (req, res) => {
    const chunks = []
    for await (const chunk of req) chunks.push(chunk)
    const body = chunks.length ? JSON.parse(Buffer.concat(chunks)) : {}
    const url = new URL(req.url, 'http://localhost')
    const send = (status, value) => {
      res.writeHead(status, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify(value))
    }
    if (url.pathname === '/health') return send(200, { ok: true })
    if (url.pathname.endsWith('/register')) return send(201, { user: { id: String(++users) }, accessToken: 'test-' + users })
    if (url.pathname.endsWith('/chats')) return send(201, { chat: { id: String(++chats) } })
    if (url.pathname.endsWith('/messages')) {
      if (body.content === 'Staging introduction') return send(201, { message: { id: body.id } })
      return send(writeStatus, { message: { id: wrongId ? 'previous-message' : body.id } })
    }
    if (url.pathname.endsWith('/request')) return send(seedRejected ? 409 : 200, { status: seedRejected ? 'pending' : 'open' })
    if (url.pathname.includes('/profiles/')) return send(200, { profile: { id: url.pathname.split('/').at(-1) } })
    if (['/v1/data/feed', '/v1/data/notifications'].includes(url.pathname)) return send(200, { items: [] })
    return send(404, {})
  })
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  t.after(() => new Promise(resolve => { server.close(resolve); server.closeAllConnections() }))
  if (silentRecipient) {
    const gatewayRequire = createRequire(new URL('../../infrastructure/local-fallback/realtime-gateway/package.json', import.meta.url))
    const ws = new (gatewayRequire('ws').WebSocketServer)({ server })
    ws.on('connection', (socket, req) => {
      socket.send(JSON.stringify({ type: 'connected', userId: req.headers.authorization.replace('Bearer test-', '') }))
      socket.on('message', raw => socket.send(JSON.stringify({ type: 'subscribed', channel: JSON.parse(raw).channel })))
    })
    t.after(() => ws.close())
  }
  const env = Object.fromEntries(Object.entries(process.env).filter(([key]) => !/^(SOAK_|STAGING_|NOVVIN_)/i.test(key)))
  const runner = spawn(process.execPath, [script], {
    env: { ...env, NOVVIN_STAGING_ISOLATED: 'true', STAGING_API_URL: 'http://127.0.0.1:' + server.address().port,
      SOAK_CONCURRENCY: '5', SOAK_DURATION_SECONDS: duration, SOAK_SAMPLE_INTERVAL_SEC: '1',
      SOAK_MAX_FUNCTIONAL_FAILURES: '1', SOAK_OUTPUT_DIR: dir, SOAK_ARRIVAL_RPS: arrivalRate,
      SOAK_WS_CLIENTS: silentRecipient ? '5' : '0', STAGING_REALTIME_URL: 'http://127.0.0.1:' + server.address().port },
    stdio: ['ignore', 'pipe', 'pipe'], timeout: 12000,
  })
  let log = ''
  runner.stdout.on('data', chunk => { log += chunk })
  runner.stderr.on('data', chunk => { log += chunk })
  const [code] = await once(runner, 'close')
  const report = await readFile(path.join(dir, 'soak-summary.json'), 'utf8').then(JSON.parse, () => null)
  const windows = report?.jsonlFile
    ? (await readFile(report.jsonlFile, 'utf8')).trim().split('\n').map(line => JSON.parse(line))
    : []
  return { code, report, windows, log, users }
}

test('accounts for every completed HTTP operation including the final sample', async t => {
  const { code, report, windows, log } = await runFixture(t)
  assert.equal(code, 0, log)
  assert.equal(report.passed, true)
  assert.equal(report.totals.totalFunctionalFailures, 0)
  assert.equal(Object.values(report.operations).reduce((n, op) => n + op.passed, 0), report.totals.totalReqs)
  assert.equal(windows.reduce((n, w) => n + w.latency.count, 0), report.totals.totalReqs)
})

for (const status of [401, 409, 429, 503]) {
  test('records a rejected write at HTTP ' + status, async t => {
    const { code, report } = await runFixture(t, { writeStatus: status })
    assert.equal(code, 1)
    assert.equal(report.passed, false)
    assert.ok(report.operations.message.statuses[status] > 0)
    assert.equal(report.operations.message.passed, 0)
    assert.ok(report.totals.totalFunctionalFailures > 0)
  })
}

test('fails a 201 ACK for a different message', async t => {
  const { code, report } = await runFixture(t, { wrongId: true })
  assert.equal(code, 1)
  assert.equal(report.operations.message.passed, 0)
  assert.ok(report.operations.message.statuses[201] > 0)
})

test('a rejected recipient setup aborts before the measured writes', async t => {
  const { code, report, log } = await runFixture(t, { seedRejected: true })
  assert.equal(code, 1)
  assert.equal(report.passed, false)
  assert.match(log, /recipient acceptance failed/)
})

test('a successful HTTP write with a missing recipient event fails the run', async t => {
  const { code, report } = await runFixture(t, { silentRecipient: true })
  assert.equal(code, 1)
  assert.ok(report.operations.message.passed > 0)
  assert.equal(report.delivery.received, 0)
  assert.equal(report.delivery.missed, report.delivery.expected)
  assert.ok(report.delivery.missed > 0)
})

test('arrival accounting reconciles scheduled slots with completed operations', async t => {
  const { report } = await runFixture(t, { arrivalRate: '20' })
  assert.equal(report.arrivals.planned, 20)
  assert.equal(report.arrivals.dispatched, report.totals.totalReqs)
  assert.equal(report.droppedArrivalIterations + report.arrivals.dispatched, 20)
  assert.equal(report.arrivals.notScheduledAfterStop, 0)
})

test('invalid duration is rejected before creating any users', async t => {
  const { code, users } = await runFixture(t, { duration: 'NaN' })
  assert.equal(code, 1)
  assert.equal(users, 0)
})

test('overdue arrivals are counted without issuing a catch-up burst', async () => {
  const result = await runStagingArrivals({ rate: 100, concurrency: 2, start: performance.now() - 500,
    durationMs: 100, stopped: () => false, issue: () => assert.fail('late request') })
  assert.equal(result.late, 10)
  assert.equal(result.dispatched, 0)
})

test('in-flight limit accounts for dropped slots and drains the last request', async () => {
  const result = await runStagingArrivals({ rate: 40, concurrency: 1, start: performance.now(),
    durationMs: 100, stopped: () => false, issue: () => wait(150) })
  assert.equal(result.peakInFlight, 1)
  assert.equal(result.dispatched, 1)
  assert.equal(result.atCapacity + result.late, 3)
})
