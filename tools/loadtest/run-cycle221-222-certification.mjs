#!/usr/bin/env node
/**
 * NOVVIN Cycles 221–222 Certification Harness (Isolated Loopback Only)
 *
 * Real isolated multi-instance stack on 127.0.0.1:
 *  - Ephemeral PostgreSQL 17 cluster
 *  - Ephemeral Redis 7.4.8 (real redis-server via WSL2)
 *  - 2x API instances, 2x Realtime Gateway instances (Redis Streams), 2x Signaling Server instances (Redis Streams)
 *
 * Phases:
 *  1. Live Streams (Directos) Multi-Instance Rehydration & SIGKILL Failover (2x Signaling + Real Redis 7.4.8)
 *     + signaling-server Redis integration test suite against real Redis 7.4.8
 *  2. Next-Round Scalability & Performance Benchmark (50..1,500 concurrent users + DM/Group latency breakdown)
 *  3. Extended Multi-Instance Soak (configurable, default 210s) + run-ubuntu-staging-soak.mjs safety guard verification
 *  4. Fault-Injection Chaos Suite (Redis SIGKILL, PG slow/lock contention, Realtime-1 SIGKILL + 60-WS reconnect storm,
 *     Cross-node change-password instant WS revocation)
 *  5. Cycle 221–222 Security & Business Rules Matrix E2E Verification
 */
import { spawn, spawnSync } from 'node:child_process'
import crypto from 'node:crypto'
import fs from 'node:fs'
import net from 'node:net'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const __dirname = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(__dirname, '../..')

const { Pool } = require(path.join(repoRoot, 'infrastructure/local-fallback/api/node_modules/pg'))
const { io: ioClient } = require(path.join(repoRoot, 'infrastructure/local-fallback/api/node_modules/socket.io-client'))
const WebSocket = require(path.join(repoRoot, 'infrastructure/local-fallback/realtime-gateway/node_modules/ws'))
const { createRedisRespClient } = require(path.join(repoRoot, 'infrastructure/local-fallback/api/src/redis-resp-client'))

const PG_BIN_CANDIDATES = [
  process.env.NOVVIN_PG_BIN,
  'C:\\Program Files\\PostgreSQL\\17\\bin',
  'C:\\Program Files\\PostgreSQL\\16\\bin',
  '/usr/lib/postgresql/17/bin',
  '/usr/lib/postgresql/16/bin',
  '/usr/bin',
].filter(Boolean)

const DEFAULT_REDIS_LAB_DIR = process.env.NOVVIN_REDIS_LAB_DIR
  || 'D:/Novvin-Entregas/evidencias/2026-11-19-audit214/redis-lab'

function resolvePgBin() {
  const ext = process.platform === 'win32' ? '.exe' : ''
  for (const dir of PG_BIN_CANDIDATES) {
    if (fs.existsSync(path.join(dir, `initdb${ext}`)) && fs.existsSync(path.join(dir, `pg_ctl${ext}`))) {
      return dir
    }
  }
  throw new Error('PostgreSQL binaries not found')
}

async function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer()
    srv.unref()
    srv.on('error', reject)
    srv.listen(0, '127.0.0.1', () => {
      const { port } = srv.address()
      srv.close(() => resolve(port))
    })
  })
}

async function waitForTcpPort(host, port, timeoutMs = 15_000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const ok = await new Promise((resolve) => {
      const sock = net.createConnection({ host, port })
      sock.once('connect', () => { sock.destroy(); resolve(true) })
      sock.once('error', () => { sock.destroy(); resolve(false) })
    })
    if (ok) return true
    await new Promise((r) => setTimeout(r, 100))
  }
  throw new Error(`TCP port ${host}:${port} did not open within ${timeoutMs}ms`)
}

async function waitForHttpOk(url, timeoutMs = 30_000) {
  const deadline = Date.now() + timeoutMs
  let lastError = 'timeout'
  while (Date.now() < deadline) {
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(2_500) })
      if (res.ok) return true
      lastError = `HTTP ${res.status}`
    } catch (err) {
      lastError = err.message
    }
    await new Promise((r) => setTimeout(r, 150))
  }
  throw new Error(`Service not ready at ${url}: ${lastError}`)
}

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

function parseServerTiming(headerValue) {
  const out = {}
  for (const part of String(headerValue || '').split(',')) {
    const match = /^\s*([a-z0-9_-]+)\s*;\s*dur=([0-9.]+)/i.exec(part)
    if (match) out[match[1].toLowerCase()] = Number(match[2])
  }
  return out
}

async function timedFetch(url, options = {}) {
  const started = performance.now()
  try {
    const res = await fetch(url, { signal: AbortSignal.timeout(options.timeoutMs || 15_000), ...options })
    const text = await res.text()
    let json = null
    try { json = text ? JSON.parse(text) : null } catch {}
    return {
      ok: res.ok,
      status: res.status,
      ms: performance.now() - started,
      json,
      text: text.slice(0, 4096),
      headers: res.headers,
    }
  } catch (error) {
    return { ok: false, status: 0, ms: performance.now() - started, error: error.message }
  }
}

async function fetchJsonMetrics(url, secret, headerName = 'Authorization') {
  const headers = headerName === 'Authorization'
    ? { Authorization: `Bearer ${secret}` }
    : { [headerName]: secret }
  const res = await timedFetch(`${url}/internal/metrics?format=json`, { headers })
  return res.ok ? res.json?.metrics || null : null
}

function spawnNodeService(name, scriptPath, cwd, env, logStream) {
  const child = spawn(process.execPath, [scriptPath], {
    cwd,
    env: { ...process.env, ...env },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  child.stdout.on('data', (chunk) => logStream.write(`[${name}:out] ${chunk}`))
  child.stderr.on('data', (chunk) => logStream.write(`[${name}:err] ${chunk}`))
  return child
}

async function stopChild(child, signal = 'SIGTERM') {
  if (!child || child.exitCode !== null) return
  try { child.kill(signal) } catch {}
  await Promise.race([
    new Promise((resolve) => child.once('exit', resolve)),
    new Promise((resolve) => setTimeout(() => {
      try { child.kill('SIGKILL') } catch {}
      resolve()
    }, 3_000)),
  ])
}

function toWslPath(winPath) {
  const normalized = path.resolve(winPath).replace(/\\/g, '/')
  const match = /^([a-zA-Z]):\/(.*)$/.exec(normalized)
  if (!match) return normalized
  return `/mnt/${match[1].toLowerCase()}/${match[2]}`
}

async function startLocalRedis748({ port, password, workDir, logStream }) {
  const wslLabDir = toWslPath(DEFAULT_REDIS_LAB_DIR)
  const pyFile = path.join(workDir, 'redis_runner.py')
  const pyRunner = [
    'from pathlib import Path',
    'import subprocess, sys, tempfile, time, socket, shutil',
    'base = Path(sys.argv[1])',
    'port = int(sys.argv[2])',
    'password = sys.stdin.readline().strip()',
    'with tempfile.TemporaryDirectory(prefix="novvin-c221-redis-") as tmp:',
    '    root = Path(tmp)',
    '    for f in ["ld-musl-x86_64.so.1", "libcrypto.so.3", "libssl.so.3", "redis-server", "redis-cli"]:',
    '        shutil.copyfile(base / f, root / f)',
    '        (root / f).chmod(0o700)',
    '    config = root / "redis.conf"',
    '    config.write_text(',
    '        f"bind 127.0.0.1\\nprotected-mode yes\\nport {port}\\nrequirepass {password}\\n"',
    '        "appendonly yes\\nappendfsync everysec\\nmaxmemory 256mb\\nmaxmemory-policy noeviction\\nsave \\"\\"\\n",',
    '        encoding="ascii",',
    '    )',
    '    config.chmod(0o600)',
    '    child = None',
    '    def start():',
    '        global child',
    '        with (root / "redis.log").open("ab") as log:',
    '            child = subprocess.Popen(',
    '                [str(root / "ld-musl-x86_64.so.1"), "--library-path", str(root), str(root / "redis-server"), str(config)],',
    '                cwd=root, stdout=log, stderr=log,',
    '            )',
    '        for _ in range(120):',
    '            if child.poll() is not None:',
    '                raise RuntimeError("Redis exited: " + (root / "redis.log").read_text(errors="replace")[-400:])',
    '            try:',
    '                with socket.create_connection(("127.0.0.1", port), 0.2):',
    '                    break',
    '            except OSError:',
    '                time.sleep(0.08)',
    '        print("READY", flush=True)',
    '    def kill_hard():',
    '        global child',
    '        if child is not None and child.poll() is None:',
    '            child.kill()',
    '            child.wait(10)',
    '        print("KILLED", flush=True)',
    '    start()',
    '    try:',
    '        for raw in sys.stdin:',
    '            cmd = raw.strip()',
    '            if cmd == "kill":',
    '                kill_hard()',
    '            elif cmd == "start":',
    '                if child is None or child.poll() is not None:',
    '                    start()',
    '                else:',
    '                    print("READY", flush=True)',
    '            elif cmd == "restart":',
    '                kill_hard()',
    '                start()',
    '            elif cmd == "quit":',
    '                break',
    '    finally:',
    '        if child is not None and child.poll() is None:',
    '            child.terminate()',
    '            try: child.wait(10)',
    '            except Exception: child.kill()',
    '',
  ].join('\n')
  fs.writeFileSync(pyFile, pyRunner, 'utf8')
  const wslPyFile = toWslPath(pyFile)

  const proc = spawn('wsl', ['-d', 'Ubuntu', '-e', 'python3', '-u', wslPyFile, wslLabDir, String(port)], {
    stdio: ['pipe', 'pipe', 'pipe'],
  })

  const lineWaiters = []
  let stdoutBuf = ''
  let stderrTail = ''
  proc.stdout.on('data', (chunk) => {
    const text = chunk.toString('utf8')
    logStream.write(`[redis:out] ${text}`)
    stdoutBuf += text
    const lines = stdoutBuf.split(/\r?\n/)
    stdoutBuf = lines.pop() || ''
    for (const line of lines) {
      const clean = line.trim()
      if (!clean) continue
      const idx = lineWaiters.findIndex((w) => w.expected === clean)
      if (idx !== -1) {
        const waiter = lineWaiters.splice(idx, 1)[0]
        clearTimeout(waiter.timer)
        waiter.resolve()
      }
    }
  })
  proc.stderr.on('data', (chunk) => {
    const text = chunk.toString('utf8')
    stderrTail = (stderrTail + text).slice(-1000)
    logStream.write(`[redis:err] ${text}`)
  })

  function waitForLine(expected, timeoutMs = 15_000) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(
        () => reject(new Error(`Redis wrapper timed out waiting for ${expected} (stderr: ${stderrTail.trim()})`)),
        timeoutMs,
      )
      lineWaiters.push({ expected, resolve, timer })
    })
  }

  const readyPromise = waitForLine('READY', 20_000)
  proc.stdin.write(`${password}\n`)
  await readyPromise
  await waitForTcpPort('127.0.0.1', port, 10_000)

  return {
    port,
    password,
    url: `redis://127.0.0.1:${port}`,
    async killHard() {
      const p = waitForLine('KILLED', 10_000)
      proc.stdin.write('kill\n')
      await p
    },
    async startAgain() {
      const p = waitForLine('READY', 15_000)
      proc.stdin.write('start\n')
      await p
      await waitForTcpPort('127.0.0.1', port, 10_000)
    },
    async stop() {
      try {
        proc.stdin.write('quit\n')
        proc.stdin.end()
      } catch {}
      await stopChild(proc, 'SIGTERM')
    },
  }
}

async function queryRedisInfo(redisUrl, password) {
  const client = createRedisRespClient({ url: redisUrl, password, commandTimeoutMs: 2_000 })
  try {
    const ok = await client.connect()
    if (!ok) return null
    const [memRaw, clientsRaw, statsRaw, serverRaw] = await Promise.all([
      client.sendCommand(['INFO', 'memory']),
      client.sendCommand(['INFO', 'clients']),
      client.sendCommand(['INFO', 'stats']),
      client.sendCommand(['INFO', 'server']),
    ])
    const parseInfo = (raw) => {
      const map = {}
      for (const line of String(raw || '').split(/\r?\n/)) {
        if (!line || line.startsWith('#')) continue
        const idx = line.indexOf(':')
        if (idx > 0) map[line.slice(0, idx)] = line.slice(idx + 1).trim()
      }
      return map
    }
    const mem = parseInfo(memRaw)
    const cli = parseInfo(clientsRaw)
    const st = parseInfo(statsRaw)
    const srv = parseInfo(serverRaw)
    return {
      redisVersion: srv.redis_version || 'unknown',
      usedMemoryBytes: Number(mem.used_memory || 0),
      usedMemoryHuman: mem.used_memory_human || '0B',
      usedMemoryPeakHuman: mem.used_memory_peak_human || '0B',
      connectedClients: Number(cli.connected_clients || 0),
      totalCommandsProcessed: Number(st.total_commands_processed || 0),
      instantaneousOpsPerSec: Number(st.instantaneous_ops_per_sec || 0),
      evictedKeys: Number(st.evicted_keys || 0),
      rejectedConnections: Number(st.rejected_connections || 0),
    }
  } finally {
    await client.close()
  }
}

async function runWave({ name, concurrency, requestsPerUser, users, apiUrls, chatIds, groupIds }) {
  const latencies = []
  const byAction = { feed: [], profile: [], dmSend: [], groupSend: [], notifications: [] }
  const criticalErrors = { dmSend: 0, groupSend: 0, profile: 0, notifications: 0 }
  let status2xx = 0
  let status4xx = 0
  let status429 = 0
  let loadShed503 = 0
  let unexpected5xx = 0
  let networkErrors = 0
  let rrCounter = 0
  const started = performance.now()

  await Promise.all(
    Array.from({ length: concurrency }, async (_, workerIndex) => {
      const user = users[workerIndex % users.length]
      const authHeaders = { Authorization: `Bearer ${user.token}` }
      const jsonHeaders = { ...authHeaders, 'Content-Type': 'application/json' }
      for (let i = 0; i < requestsPerUser; i += 1) {
        const apiUrl = apiUrls[(rrCounter++) % apiUrls.length]
        const mod = (workerIndex + i) % 5
        let res
        let bucket
        if (mod === 0) {
          bucket = 'feed'
          res = await timedFetch(`${apiUrl}/v1/data/feed?limit=20`, { headers: authHeaders })
        } else if (mod === 1) {
          bucket = 'profile'
          const target = users[(workerIndex + 1) % users.length]
          res = await timedFetch(`${apiUrl}/v1/data/profiles/${target.id}`, { headers: authHeaders })
        } else if (mod === 2) {
          bucket = 'dmSend'
          const chatId = chatIds[Math.floor((workerIndex % users.length) / 2) % chatIds.length]
          res = await timedFetch(`${apiUrl}/v1/data/messages/chats/${chatId}/messages`, {
            method: 'POST',
            headers: jsonHeaders,
            body: JSON.stringify({
              id: crypto.randomUUID(),
              content: `c221-dm-${name}-${workerIndex}-${i}`,
              contentType: 'text',
            }),
          })
        } else if (mod === 3) {
          bucket = 'groupSend'
          const groupId = groupIds[workerIndex % groupIds.length]
          res = await timedFetch(`${apiUrl}/v1/data/messages/groups/${groupId}/messages`, {
            method: 'POST',
            headers: jsonHeaders,
            body: JSON.stringify({
              id: crypto.randomUUID(),
              content: `c221-grp-${name}-${workerIndex}-${i}`,
              contentType: 'text',
            }),
          })
        } else {
          bucket = 'notifications'
          res = await timedFetch(`${apiUrl}/v1/data/notifications?limit=15`, { headers: authHeaders })
        }

        latencies.push(res.ms)
        byAction[bucket].push(res.ms)
        if (res.status === 0) {
          networkErrors += 1
          if (bucket !== 'feed') criticalErrors[bucket] += 1
        } else if (res.status === 503 && res.json?.error === 'SERVICE_OVERLOADED') {
          loadShed503 += 1
        } else if (res.status >= 500) {
          unexpected5xx += 1
          if (bucket !== 'feed') criticalErrors[bucket] += 1
        } else if (res.status === 429) {
          status429 += 1
        } else if (res.status >= 400) {
          status4xx += 1
        } else {
          status2xx += 1
        }
      }
    }),
  )

  const elapsedSec = Math.max(0.001, (performance.now() - started) / 1000)
  const total = latencies.length
  return {
    name,
    concurrency,
    apiInstances: apiUrls.length,
    totalRequests: total,
    elapsedSeconds: Number(elapsedSec.toFixed(2)),
    rps: Number((total / elapsedSec).toFixed(1)),
    status2xx,
    status4xx,
    status429,
    loadShed503,
    unexpected5xx,
    networkErrors,
    criticalErrors,
    overall: percentiles(latencies),
    byAction: Object.fromEntries(Object.entries(byAction).map(([k, v]) => [k, percentiles(v)])),
  }
}

async function openAuthenticatedRealtimeWs(realtimeUrl, token, subscribeChannels = []) {
  const wsUrl = realtimeUrl.replace(/^http/, 'ws')
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsUrl, {
      headers: { Authorization: `Bearer ${token}` },
    })
    const events = []
    const listeners = new Set()
    let connected = false
    let closedInfo = null
    const pendingSubs = new Set(subscribeChannels)
    const timer = setTimeout(() => {
      ws.close()
      reject(new Error(`Realtime WS connect/sub timeout at ${wsUrl}`))
    }, 10_000)

    ws.on('message', (raw) => {
      let msg
      try { msg = JSON.parse(raw.toString('utf8')) } catch { return }
      if (msg.type === 'connected') {
        connected = true
        if (pendingSubs.size === 0) {
          clearTimeout(timer)
          resolve({ ws, events, listeners, getClosedInfo: () => closedInfo })
        } else {
          for (const ch of pendingSubs) {
            ws.send(JSON.stringify({ type: 'subscribe', channel: ch }))
          }
        }
        return
      }
      if (msg.type === 'subscribed') {
        pendingSubs.delete(msg.channel)
        if (connected && pendingSubs.size === 0) {
          clearTimeout(timer)
          resolve({ ws, events, listeners, getClosedInfo: () => closedInfo })
        }
        return
      }
      if (msg.type === 'event') {
        const receivedAtMs = performance.now()
        const entry = { ...msg, receivedAtMs }
        events.push(entry)
        for (const fn of listeners) fn(entry)
      }
    })
    ws.on('close', (code, reasonBuf) => {
      closedInfo = { code, reason: reasonBuf?.toString('utf8') || '' }
    })
    ws.on('error', (err) => {
      clearTimeout(timer)
      reject(err)
    })
  })
}

async function connectLiveNamespaceSocket(sigUrl, user) {
  return new Promise((resolve, reject) => {
    const sock = ioClient(`${sigUrl}/live`, {
      transports: ['websocket'],
      reconnection: false,
      auth: { token: user.token },
      extraHeaders: { Origin: 'http://127.0.0.1' },
      timeout: 8_000,
    })
    const events = []
    const record = (name) => (payload) => {
      events.push({ name, payload, at: performance.now() })
    }
    for (const ev of [
      'live-state',
      'host-info',
      'viewer-count',
      'viewer-joined',
      'viewer-left',
      'current-viewers',
      'reaction',
      'reaction-count',
      'live-guest-request',
      'live-guest-requests',
      'live-guest-status',
      'live-guests',
      'live-moderation-state',
      'live-moderated',
      'live-ended',
      'end-live-confirmed',
      'live-join-denied',
      'host-disconnected',
    ]) {
      sock.on(ev, record(ev))
    }
    const timer = setTimeout(() => {
      try { sock.disconnect() } catch {}
      reject(new Error(`Connect /live timeout at ${sigUrl}`))
    }, 8_000)
    sock.once('connect', () => {
      clearTimeout(timer)
      resolve({
        sock,
        events,
        waitForEvent(name, predicate = () => true, timeoutMs = 6_000) {
          const existing = events.find((e) => e.name === name && predicate(e.payload))
          if (existing) return Promise.resolve(existing.payload)
          return new Promise((res, rej) => {
            const t = setTimeout(() => {
              sock.off(name, handler)
              rej(new Error(`Timeout waiting for /live event ${name}`))
            }, timeoutMs)
            const handler = (payload) => {
              if (predicate(payload)) {
                clearTimeout(t)
                sock.off(name, handler)
                res(payload)
              }
            }
            sock.on(name, handler)
          })
        },
      })
    })
    sock.once('connect_error', (err) => {
      clearTimeout(timer)
      reject(err)
    })
  })
}

async function seedDataset(apiUrl, dbPool) {
  const users = []
  for (let i = 0; i < 24; i += 1) {
    const email = `c221user${i}@novvin.test`
    const reg = await timedFetch(`${apiUrl}/v1/auth/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        email,
        password: 'CertPassword221!Secure',
        displayName: `C221 User ${i}`,
        username: `c221user${i}`,
      }),
    })
    if (reg.status !== 201 && reg.status !== 200) {
      throw new Error(`Register user ${i} failed: ${reg.status} ${reg.text}`)
    }
    users.push({
      id: reg.json.user.id,
      email,
      token: reg.json.accessToken,
      refreshToken: reg.json.refreshToken,
    })
  }

  const chatIds = []
  for (let i = 0; i < 12; i += 1) {
    const a = users[i * 2]
    const b = users[i * 2 + 1]
    await dbPool.query(
      `INSERT INTO fallback_friends (id, sender_id, receiver_id, status, created_at)
       VALUES ($1::uuid, $2, $3, 'accepted', NOW())
       ON CONFLICT DO NOTHING`,
      [crypto.randomUUID(), a.id, b.id],
    )
    const r = await timedFetch(`${apiUrl}/v1/data/messages/chats`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${a.token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ participantId: b.id }),
    })
    const chatId = r.json?.chat?.id || r.json?.id
    if (!chatId) throw new Error(`DM chat create failed: ${r.status} ${r.text}`)
    chatIds.push(chatId)
  }

  const groupIds = []
  for (let g = 0; g < 2; g += 1) {
    const gid = crypto.randomUUID()
    await dbPool.query(
      `INSERT INTO fallback_collection_rooms (id, room_kind, name, creator_id, is_public, created_at, updated_at)
       VALUES ($1::uuid, 'group', $2, $3, TRUE, NOW(), NOW())`,
      [gid, `C221 Group ${g}`, users[g].id],
    )
    for (const u of users) {
      await dbPool.query(
        `INSERT INTO fallback_collection_members (room_kind, room_id, user_id, role, joined_at)
         VALUES ('group', $1, $2, $3, NOW())
         ON CONFLICT DO NOTHING`,
        [gid, u.id, u.id === users[g].id ? 'admin' : 'member'],
      )
    }
    groupIds.push(gid)
  }

  const postIds = []
  for (let p = 0; p < 25; p += 1) {
    const author = users[p % users.length]
    const r = await timedFetch(`${apiUrl}/v1/data/feed/posts`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${author.token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: `C221 realistic feed post #${p} for load and engagement benchmarks` }),
    })
    const pid = r.json?.post?.id || r.json?.id
    if (pid) {
      postIds.push(pid)
      if (p < 10) {
        const reactor = users[(p + 3) % users.length]
        await timedFetch(`${apiUrl}/v1/data/feed/posts/${pid}/reaction`, {
          method: 'POST',
          headers: { Authorization: `Bearer ${reactor.token}`, 'Content-Type': 'application/json' },
          body: JSON.stringify({ reaction: 'LIKE' }),
        })
        await timedFetch(`${apiUrl}/v1/data/feed/posts/${pid}/comments`, {
          method: 'POST',
          headers: { Authorization: `Bearer ${reactor.token}`, 'Content-Type': 'application/json' },
          body: JSON.stringify({ content: `Comment on post #${p}` }),
        })
      }
    }
  }

  return { users, chatIds, groupIds, postIds }
}

async function main() {
  const soakSeconds = Math.max(30, Math.min(Number(process.env.NOVVIN_C221_SOAK_SECONDS || 210), 900))
  const pgBin = resolvePgBin()
  const ext = process.platform === 'win32' ? '.exe' : ''
  const workDir = fs.mkdtempSync(path.join(os.tmpdir(), 'novvin-c221-'))
  const pgData = path.join(workDir, 'pgdata')
  const storageDir = path.join(workDir, 'storage')
  fs.mkdirSync(storageDir, { recursive: true })
  const logStream = fs.createWriteStream(path.join(workDir, 'services.log'))

  const pgPort = await freePort()
  const redisPort = await freePort()
  const api1Port = await freePort()
  const api2Port = await freePort()
  const rt1Port = await freePort()
  const rt2Port = await freePort()
  const sig1Port = await freePort()
  const sig2Port = await freePort()

  const jwtSecret = crypto.randomBytes(32).toString('hex')
  const internalSecret = crypto.randomBytes(32).toString('hex')
  const liveToken = crypto.randomBytes(32).toString('hex')
  const adminToken = crypto.randomBytes(32).toString('hex')
  const redisPass = crypto.randomBytes(32).toString('hex')
  const pgPass = 'novvin_c221_local_pw'
  const pwFile = path.join(workDir, 'pgpass.txt')
  const redisPwFile = path.join(workDir, 'redispass.txt')
  fs.writeFileSync(pwFile, pgPass, 'utf8')
  fs.writeFileSync(redisPwFile, redisPass, 'utf8')

  let pgStarted = false
  let redisHandle = null
  let dbPool = null
  const children = {}

  const spawnNamed = (name, scriptPath, cwd, env) => {
    const child = spawnNodeService(name, scriptPath, cwd, env, logStream)
    children[name] = child
    return child
  }
  const stopNamed = async (name, sig = 'SIGTERM') => {
    if (children[name]) {
      await stopChild(children[name], sig)
      delete children[name]
    }
  }

  try {
    console.error(`[c221-222] Booting ephemeral PostgreSQL 17 on 127.0.0.1:${pgPort}...`)
    const initRes = spawnSync(
      path.join(pgBin, `initdb${ext}`),
      ['-D', pgData, '-U', 'postgres', '--auth=scram-sha-256', `--pwfile=${pwFile}`, '-E', 'UTF8', '--no-locale'],
      { encoding: 'utf8' },
    )
    if (initRes.status !== 0) throw new Error(`initdb failed: ${initRes.stderr || initRes.stdout}`)
    fs.appendFileSync(
      path.join(pgData, 'postgresql.conf'),
      `\nlisten_addresses = '127.0.0.1'\nport = ${pgPort}\nmax_connections = 220\nshared_buffers = 128MB\n`,
    )
    const startRes = spawnSync(
      path.join(pgBin, `pg_ctl${ext}`),
      ['-D', pgData, '-l', path.join(workDir, 'postgres.log'), 'start'],
      { stdio: 'ignore' },
    )
    if (startRes.status !== 0) throw new Error(`pg_ctl start failed (exit ${startRes.status})`)
    await waitForTcpPort('127.0.0.1', pgPort, 15_000)
    pgStarted = true

    const databaseUrl = `postgresql://postgres:${pgPass}@127.0.0.1:${pgPort}/postgres`
    dbPool = new Pool({ connectionString: databaseUrl })
    await dbPool.query(fs.readFileSync(path.join(repoRoot, 'infrastructure/local-fallback/db/init/001_schema.sql'), 'utf8'))

    console.error(`[c221-222] Booting real Redis 7.4.8 (WSL2 isolated) on 127.0.0.1:${redisPort}...`)
    redisHandle = await startLocalRedis748({ port: redisPort, password: redisPass, workDir, logStream })
    const initialRedisInfo = await queryRedisInfo(redisHandle.url, redisPass)
    console.error(`[c221-222] Redis ${initialRedisInfo.redisVersion} READY (used=${initialRedisInfo.usedMemoryHuman})`)

    // Run signaling-server/tests/distributed-live-state.redis.integration.test.js against real Redis 7.4.8!
    console.error('[c221-222] Running distributed-live-state.redis.integration.test.js against real Redis 7.4.8...')
    const sigRedisInteg = spawnSync(
      process.execPath,
      ['--test', 'tests/distributed-live-state.redis.integration.test.js'],
      {
        cwd: path.join(repoRoot, 'signaling-server'),
        env: {
          ...process.env,
          NOVVIN_TEST_REDIS_URL: `redis://:${encodeURIComponent(redisPass)}@127.0.0.1:${redisPort}`,
          NOVVIN_TEST_REDIS_PASSWORD: redisPass,
        },
        encoding: 'utf8',
      },
    )
    const sigRedisIntegrationTestPassed = sigRedisInteg.status === 0

    const api1Url = `http://127.0.0.1:${api1Port}`
    const api2Url = `http://127.0.0.1:${api2Port}`
    const rt1Url = `http://127.0.0.1:${rt1Port}`
    const rt2Url = `http://127.0.0.1:${rt2Port}`
    const sig1Url = `http://127.0.0.1:${sig1Port}`
    const sig2Url = `http://127.0.0.1:${sig2Port}`

    const makeRtEnv = (port, apiUrl) => ({
      NODE_ENV: 'test',
      PORT: String(port),
      REALTIME_LISTEN_HOST: '127.0.0.1',
      JWT_SECRET: jwtSecret,
      JWT_ISSUER: 'conectate-local-fallback',
      LOCAL_FALLBACK_API_URL: apiUrl,
      REALTIME_INTERNAL_SECRET: internalSecret,
      REALTIME_REDIS_URL: redisHandle.url,
      REALTIME_REDIS_PASSWORD_FILE: redisPwFile,
      REALTIME_REDIS_STREAM_NAME: 'novvin:c221:realtime:events',
      REALTIME_REDIS_BLOCK_MS: '500',
      REALTIME_MAX_CONNECTIONS: '5000',
      REALTIME_MAX_CONNECTIONS_PER_IP: '3000',
    })

    const makeApiEnv = (port, rtUrl, { useRedis = true, msgLimit = 5000, poolMax = 25, loadShedWaiting = 150 } = {}) => ({
      NODE_ENV: 'test',
      PORT: String(port),
      DATABASE_URL: databaseUrl,
      DATABASE_POOL_MAX: String(poolMax),
      DB_POOL_LOAD_SHED_WAITING: String(loadShedWaiting),
      JWT_SECRET: jwtSecret,
      STORAGE_ROOT: storageDir,
      STORAGE_MIN_FREE_BYTES: '0',
      STORAGE_SIGNING_SECRET: crypto.randomBytes(32).toString('hex'),
      REALTIME_INTERNAL_URL: rtUrl,
      REALTIME_INTERNAL_SECRET: internalSecret,
      LIVE_INTERNAL_EVENTS_TOKEN: liveToken,
      SIGNALING_INTERNAL_URL: sig2Url,
      ADMIN_SYNC_TOKEN: adminToken,
      CORS_ORIGIN: 'http://127.0.0.1',
      AUTH_REGISTER_RATE_LIMIT_PER_HOUR: '200',
      MESSAGE_WRITE_RATE_LIMIT_PER_MINUTE: String(msgLimit),
      SOCIAL_WRITE_RATE_LIMIT_PER_MINUTE: '5000',
      CALL_INITIATE_RATE_LIMIT_PER_MINUTE: '50',
      PASSWORD_RESET_DELIVERY: 'disabled',
      LIVE_CHANNEL_POINTS_ENABLED: 'true',
      ENABLE_LIVE_CHANNEL_POINTS: 'true',
      LIVE_POINTS_SESSION_SECRET: jwtSecret,
      STORAGE_MEDIA_ORIGINS: `${api1Url},${api2Url}`,
      PUBLIC_BASE_URL: `http://127.0.0.1:${port}`,
      ...(useRedis ? {
        RATE_LIMIT_REDIS_URL: redisHandle.url,
        RATE_LIMIT_REDIS_PASSWORD_FILE: redisPwFile,
        RATE_LIMIT_REDIS_COMMAND_TIMEOUT_MS: '200',
      } : {
        RATE_LIMIT_REDIS_URL: '',
        REDIS_URL: '',
      }),
    })

    const makeSigEnv = (port, apiUrl) => ({
      NODE_ENV: 'test',
      PORT: String(port),
      LOCAL_FALLBACK_API_URL: apiUrl,
      FALLBACK_API_URL: apiUrl,
      SIGNALING_ALLOWED_ORIGINS: 'http://127.0.0.1',
      SIGNALING_REDIS_URL: redisHandle.url,
      SIGNALING_REDIS_PASSWORD_FILE: redisPwFile,
      SIGNALING_REDIS_REQUIRED: 'true',
      SIGNALING_REDIS_STREAM_NAME: 'novvin:c221:signaling:events',
      SIGNALING_REDIS_STREAM_COUNT: '4',
      SIGNALING_METRICS_TOKEN: internalSecret,
      LIVE_INTERNAL_EVENTS_TOKEN: liveToken,
      SIGNALING_MAX_SOCKETS_PER_IP: '500',
      SIGNALING_NODE_LIVENESS_SECONDS: '3',
    })

    // Boot 2x Realtime, 2x API, 2x Signaling
    spawnNamed('rt1', path.join(repoRoot, 'infrastructure/local-fallback/realtime-gateway/src/server.js'), path.join(repoRoot, 'infrastructure/local-fallback/realtime-gateway'), makeRtEnv(rt1Port, api1Url))
    spawnNamed('rt2', path.join(repoRoot, 'infrastructure/local-fallback/realtime-gateway/src/server.js'), path.join(repoRoot, 'infrastructure/local-fallback/realtime-gateway'), makeRtEnv(rt2Port, api2Url))
    await Promise.all([waitForHttpOk(`${rt1Url}/health`, 15_000), waitForHttpOk(`${rt2Url}/health`, 15_000)])

    spawnNamed('api1', path.join(repoRoot, 'infrastructure/local-fallback/api/src/server.js'), path.join(repoRoot, 'infrastructure/local-fallback/api'), makeApiEnv(api1Port, rt1Url, { useRedis: true }))
    spawnNamed('api2', path.join(repoRoot, 'infrastructure/local-fallback/api/src/server.js'), path.join(repoRoot, 'infrastructure/local-fallback/api'), makeApiEnv(api2Port, rt2Url, { useRedis: true }))
    await Promise.all([waitForHttpOk(`${api1Url}/health`, 30_000), waitForHttpOk(`${api2Url}/health`, 30_000)])

    spawnNamed('sig1', path.join(repoRoot, 'signaling-server/index.js'), path.join(repoRoot, 'signaling-server'), makeSigEnv(sig1Port, api1Url))
    spawnNamed('sig2', path.join(repoRoot, 'signaling-server/index.js'), path.join(repoRoot, 'signaling-server'), makeSigEnv(sig2Port, api2Url))
    await Promise.all([waitForHttpOk(`${sig1Url}/health`, 20_000), waitForHttpOk(`${sig2Url}/health`, 20_000)])

    console.error('[c221-222] Seeding isolated dataset (24 users, 12 DM chats, 2 groups, 25 posts)...')
    const { users, chatIds, groupIds } = await seedDataset(api1Url, dbPool)

    // =========================================================================
    // PHASE 1: LIVE STREAMS (DIRECTOS) MULTI-INSTANCE REHYDRATION & SIGKILL FAILOVER
    // =========================================================================
    console.error('[c221-222][Phase1] Testing Multi-Instance Live Streams (Directos) Rehydration & SIGKILL Failover across 2x Signaling + Redis 7.4.8...')
    const [sig1InitialHealth, sig2InitialHealth] = await Promise.all([
      timedFetch(`${sig1Url}/health`),
      timedFetch(`${sig2Url}/health`),
    ])

    const hostUser = users[0]
    const guestUser = users[1]
    const viewerUser = users[2]

    // Create Live Stream on API-1 and activate with allowGuestRequests=true
    const createLiveRes = await timedFetch(`${api1Url}/v1/data/live`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${hostUser.token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        title: 'C221 Multi-Instance Rehydrated Live',
        source: 'camera',
      }),
    })
    const liveId = createLiveRes.json?.stream?.id || createLiveRes.json?.live?.id || createLiveRes.json?.id
    if (!liveId) throw new Error(`Create live failed: ${createLiveRes.status} ${createLiveRes.text}`)

    const activateLiveRes = await timedFetch(`${api1Url}/v1/data/live/${liveId}/state`, {
      method: 'PATCH',
      headers: { Authorization: `Bearer ${hostUser.token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        isActive: true,
        allowGuestRequests: true,
      }),
    })
    if (activateLiveRes.status !== 200) {
      throw new Error(`Activate live failed: ${activateLiveRes.status} ${activateLiveRes.text}`)
    }

    // 1. Host connects to /live on Signaling-1
    const hostOnSig1 = await connectLiveNamespaceSocket(sig1Url, hostUser)
    hostOnSig1.sock.emit('join-live', {
      liveId,
      userId: hostUser.id,
      name: 'Host User 0',
      role: 'host',
    })
    const hostInitialViewerCount = await hostOnSig1.waitForEvent('viewer-count', (p) => p?.liveId === liveId)

    // 2. Viewer connects to /live on Signaling-2 (cross-node!)
    const viewerOnSig2 = await connectLiveNamespaceSocket(sig2Url, viewerUser)
    viewerOnSig2.sock.emit('join-live', {
      liveId,
      userId: viewerUser.id,
      name: 'Viewer User 2',
      role: 'viewer',
    })
    const [viewerHostInfo, viewerInitialState] = await Promise.all([
      viewerOnSig2.waitForEvent('host-info', (p) => p?.userId === hostUser.id),
      viewerOnSig2.waitForEvent('live-state', (p) => typeof p?.videoEnabled === 'boolean'),
    ])

    // 3. Guest connects to /live on Signaling-2 and requests to join as guest across nodes
    const guestOnSig2 = await connectLiveNamespaceSocket(sig2Url, guestUser)
    guestOnSig2.sock.emit('join-live', {
      liveId,
      userId: guestUser.id,
      name: 'Guest User 1',
      role: 'viewer',
    })
    await guestOnSig2.waitForEvent('host-info', (p) => p?.userId === hostUser.id)

    guestOnSig2.sock.emit('live-guest-request', {
      liveId,
      userId: guestUser.id,
    })
    const hostReceivedGuestReq = await hostOnSig1.waitForEvent(
      'live-guest-request',
      (p) => p?.userId === guestUser.id && Boolean(p?.socketId),
    )

    // Host on Signaling-1 accepts Guest on Signaling-2!
    hostOnSig1.sock.emit('live-guest-response', {
      liveId,
      targetSocketId: hostReceivedGuestReq.socketId,
      accepted: true,
    })
    const [guestAcceptedOnSig2, guestRosterOnSig2] = await Promise.all([
      guestOnSig2.waitForEvent('live-guest-status', (p) => p?.status === 'accepted'),
      guestOnSig2.waitForEvent('live-guests', (p) => Array.isArray(p) && p.some((g) => g.userId === guestUser.id)),
    ])

    // 4. Cross-node reactions + duplicate clientBatchId deduplication
    viewerOnSig2.sock.emit('send-reaction', { liveId, userId: viewerUser.id, emoji: '❤️' })
    const clientBatchId = crypto.randomUUID()
    guestOnSig2.sock.emit('send-reaction-batch', { liveId, userId: guestUser.id, count: 4, clientBatchId })
    // Replay the exact same clientBatchId to verify cross-node deduplication!
    guestOnSig2.sock.emit('send-reaction-batch', { liveId, userId: guestUser.id, count: 4, clientBatchId })

    await hostOnSig1.waitForEvent('reaction-count', (p) => p?.liveId === liveId && p?.reactionCount === 5)

    // 5. CHAOS: SIGKILL Signaling-1 while Live is active!
    // Also connect an extra viewer on Signaling-1 so we verify dead-node viewer pruning when Signaling-1 dies!
    const doomedViewerOnSig1 = await connectLiveNamespaceSocket(sig1Url, users[3])
    doomedViewerOnSig1.sock.emit('join-live', {
      liveId,
      userId: users[3].id,
      name: 'Doomed Viewer 3',
      role: 'viewer',
    })
    await doomedViewerOnSig1.waitForEvent('host-info', (p) => p?.userId === hostUser.id)

    const liveFailoverStart = performance.now()
    await stopNamed('sig1', 'SIGKILL')
    try { hostOnSig1.sock.disconnect() } catch {}
    try { doomedViewerOnSig1.sock.disconnect() } catch {}

    // Wait for sig1's 3s node liveness TTL to expire
    await new Promise((r) => setTimeout(r, 3_300))

    // Host reconnects onto Signaling-2!
    const hostReconnectStart = performance.now()
    const hostRehydratedOnSig2 = await connectLiveNamespaceSocket(sig2Url, hostUser)
    hostRehydratedOnSig2.sock.emit('join-live', {
      liveId,
      userId: hostUser.id,
      name: 'Host User 0',
      role: 'host',
    })
    const [hostRehydratedReactions, hostRehydratedGuests, hostRehydratedViewers] = await Promise.all([
      hostRehydratedOnSig2.waitForEvent('reaction-count', (p) => p?.liveId === liveId),
      hostRehydratedOnSig2.waitForEvent('live-guests', (p) => Array.isArray(p)),
      hostRehydratedOnSig2.waitForEvent('viewer-count', (p) => p?.liveId === liveId),
    ])
    const hostReconnectLatencyMs = Number((performance.now() - hostReconnectStart).toFixed(1))
    const totalLiveFailoverMs = Number((performance.now() - liveFailoverStart).toFixed(1))

    // Also test Guest disconnect & reconnect rehydration on Signaling-2!
    try { guestOnSig2.sock.disconnect() } catch {}
    await new Promise((r) => setTimeout(r, 300))
    const guestReconnectedOnSig2 = await connectLiveNamespaceSocket(sig2Url, guestUser)
    guestReconnectedOnSig2.sock.emit('join-live', {
      liveId,
      userId: guestUser.id,
      name: 'Guest User 1',
      role: 'viewer',
    })
    const [guestRehydratedAccepted, guestRehydratedReactions] = await Promise.all([
      guestReconnectedOnSig2.waitForEvent('live-guest-status', (p) => p?.status === 'accepted' && p?.rehydrated === true),
      guestReconnectedOnSig2.waitForEvent('reaction-count', (p) => p?.liveId === liveId),
    ])

    // 6. End Live stream and verify all Redis keys for liveId are purged
    hostRehydratedOnSig2.sock.emit('end-live', { liveId })
    const [endConfirmed, viewerEnded] = await Promise.all([
      hostRehydratedOnSig2.waitForEvent('end-live-confirmed', (p) => p?.liveId === liveId),
      viewerOnSig2.waitForEvent('live-ended', () => true).then(() => true),
    ])

    const redisVerifyClient = createRedisRespClient({ url: redisHandle.url, password: redisPass, commandTimeoutMs: 1_500 })
    await redisVerifyClient.connect()
    const remainingLiveKeys = await redisVerifyClient.sendCommand([
      'EXISTS',
      `novvin:signaling:live:state:${liveId}`,
      `novvin:signaling:live:host-lease:${liveId}`,
      `novvin:signaling:live:host-runtime:${liveId}`,
      `novvin:signaling:live:viewers:${liveId}`,
      `novvin:signaling:live:guests:${liveId}`,
      `novvin:signaling:live:guest-requests:${liveId}`,
      `novvin:signaling:live:reactions:${liveId}`,
    ])
    await redisVerifyClient.close()

    try { hostRehydratedOnSig2.sock.disconnect() } catch {}
    try { guestReconnectedOnSig2.sock.disconnect() } catch {}
    try { viewerOnSig2.sock.disconnect() } catch {}

    // Restart sig1 for subsequent phases
    spawnNamed('sig1', path.join(repoRoot, 'signaling-server/index.js'), path.join(repoRoot, 'signaling-server'), makeSigEnv(sig1Port, api1Url))
    await waitForHttpOk(`${sig1Url}/health`, 20_000)

    const liveRehydrationReport = {
      passed:
        sigRedisIntegrationTestPassed &&
        sig1InitialHealth.json?.stateConsistency?.liveSessionState === 'distributed-redis-rehydrated' &&
        sig2InitialHealth.json?.stateConsistency?.liveSessionState === 'distributed-redis-rehydrated' &&
        Boolean(hostInitialViewerCount?.liveId === liveId) &&
        Boolean(viewerHostInfo?.userId === hostUser.id) &&
        Boolean(viewerInitialState && typeof viewerInitialState.videoEnabled === 'boolean') &&
        Boolean(hostReceivedGuestReq?.userId === guestUser.id) &&
        Boolean(guestAcceptedOnSig2?.status === 'accepted') &&
        Array.isArray(guestRosterOnSig2) && guestRosterOnSig2.length === 1 &&
        hostRehydratedReactions?.reactionCount === 5 &&
        Array.isArray(hostRehydratedGuests) &&
        hostRehydratedGuests.length === 1 &&
        hostRehydratedGuests[0]?.userId === guestUser.id &&
        hostRehydratedViewers?.viewerCount === 2 && // dead-node doomedViewerOnSig1 was pruned, leaving viewerOnSig2 + guestOnSig2 = 2!
        guestRehydratedAccepted?.rehydrated === true &&
        guestRehydratedReactions?.reactionCount === 5 &&
        Boolean(endConfirmed?.liveId === liveId) &&
        Boolean(viewerEnded) &&
        Number(remainingLiveKeys) === 0,
      sigRedisIntegrationTestPassed,
      sig1LiveSessionState: sig1InitialHealth.json?.stateConsistency?.liveSessionState,
      sig2LiveSessionState: sig2InitialHealth.json?.stateConsistency?.liveSessionState,
      crossNodeViewerHostInfoReceived: Boolean(viewerHostInfo?.userId === hostUser.id),
      crossNodeGuestAccepted: Boolean(guestAcceptedOnSig2?.status === 'accepted'),
      rehydratedTotalReactions: hostRehydratedReactions?.reactionCount,
      rehydratedGuestCountAfterSig1Sigkill: hostRehydratedGuests?.length || 0,
      rehydratedViewerCountAfterDeadNodePruning: hostRehydratedViewers?.viewerCount,
      guestReconnectRehydratedFlag: Boolean(guestRehydratedAccepted?.rehydrated),
      hostReconnectLatencyAfterLivenessExpiryMs: hostReconnectLatencyMs,
      totalFailoverIncludingLivenessTtlMs: totalLiveFailoverMs,
      remainingRedisKeysAfterEndLive: Number(remainingLiveKeys),
    }

    // =========================================================================
    // PHASE 2: NEXT-ROUND SCALABILITY & PERFORMANCE BENCHMARK (2x API + REDIS + PG)
    // =========================================================================
    console.error('[c221-222][Phase2] Running Next-Round Scalability Benchmark (50..1,500 concurrent users) + Messaging Latency Breakdown...')
    const rampTiers = [
      { concurrency: 50, requestsPerUser: 6 },
      { concurrency: 100, requestsPerUser: 5 },
      { concurrency: 250, requestsPerUser: 4 },
      { concurrency: 500, requestsPerUser: 3 },
      { concurrency: 1000, requestsPerUser: 2 },
      { concurrency: 1500, requestsPerUser: 2 },
    ]
    await runWave({
      name: 'c221_warmup',
      concurrency: 20,
      requestsPerUser: 2,
      users,
      apiUrls: [api1Url, api2Url],
      chatIds,
      groupIds,
    })
    const benchRedis2x = []
    for (const tier of rampTiers) {
      console.error(`[c221-222][Phase2:redis_2api] concurrency=${tier.concurrency}...`)
      const wave = await runWave({
        name: `c221_redis_2api_${tier.concurrency}`,
        concurrency: tier.concurrency,
        requestsPerUser: tier.requestsPerUser,
        users,
        apiUrls: [api1Url, api2Url],
        chatIds,
        groupIds,
      })
      const metricsList = await Promise.all([api1Url, api2Url].map((u) => fetchJsonMetrics(u, internalSecret, 'Authorization')))
      benchRedis2x.push({ ...wave, apiMetrics: metricsList })
      await new Promise((r) => setTimeout(r, 600))
    }

    // Measure DM & Group Messaging Latency Breakdown (Idle + 40 Concurrent Feed Users)
    async function measureMessagingLatency({ kind, targetId, channel, count, backgroundLoad }) {
      const dmSender = users[2]
      const dmRecipient = users[3]
      const wsSameNode = await openAuthenticatedRealtimeWs(rt1Url, dmRecipient.token, [channel])
      const wsCrossNode = await openAuthenticatedRealtimeWs(rt2Url, dmRecipient.token, [channel])

      let bgStop = false
      const bgPromises = []
      if (backgroundLoad > 0) {
        for (let b = 0; b < backgroundLoad; b += 1) {
          const bgUser = users[(b + 4) % users.length]
          bgPromises.push((async () => {
            while (!bgStop) {
              await timedFetch(`${b % 2 === 0 ? api1Url : api2Url}/v1/data/feed?limit=15`, {
                headers: { Authorization: `Bearer ${bgUser.token}` },
              })
            }
          })())
        }
        await new Promise((r) => setTimeout(r, 200))
      }

      const accessSamples = []
      const persistSamples = []
      const backendAckSamples = []
      const sameNodeWsSamples = []
      const crossNodeWsSamples = []
      let errors = 0

      try {
        for (let i = 0; i < count; i += 1) {
          const msgId = crypto.randomUUID()
          let resolveSame
          let resolveCross
          const samePromise = new Promise((r) => { resolveSame = r })
          const crossPromise = new Promise((r) => { resolveCross = r })
          const tSend = performance.now()

          const matchesMessage = (ev) => {
            if (ev.channel === channel && ev.payload?.message?.id === msgId) return true
            if (ev.channel === `user:${dmRecipient.id}` && ev.payload?.message?.id === msgId) return true
            return false
          }
          const onSame = (ev) => {
            if (matchesMessage(ev)) {
              wsSameNode.listeners.delete(onSame)
              resolveSame(ev.receivedAtMs - tSend)
            }
          }
          const onCross = (ev) => {
            if (matchesMessage(ev)) {
              wsCrossNode.listeners.delete(onCross)
              resolveCross(ev.receivedAtMs - tSend)
            }
          }
          wsSameNode.listeners.add(onSame)
          wsCrossNode.listeners.add(onCross)

          const endpoint = kind === 'dm'
            ? `${api1Url}/v1/data/messages/chats/${targetId}/messages`
            : `${api1Url}/v1/data/messages/groups/${targetId}/messages`
          const res = await timedFetch(endpoint, {
            method: 'POST',
            headers: { Authorization: `Bearer ${dmSender.token}`, 'Content-Type': 'application/json' },
            body: JSON.stringify({ id: msgId, content: `lat221-${kind}-${i}`, contentType: 'text' }),
          })
          if (res.status !== 201) {
            errors += 1
            wsSameNode.listeners.delete(onSame)
            wsCrossNode.listeners.delete(onCross)
            continue
          }
          backendAckSamples.push(res.ms)
          const st = parseServerTiming(res.headers?.get('server-timing'))
          if (Number.isFinite(st.access)) accessSamples.push(st.access)
          if (Number.isFinite(st.persist)) persistSamples.push(st.persist)

          const [sameMs, crossMs] = await Promise.all([
            Promise.race([samePromise, new Promise((r) => setTimeout(() => r(null), 3_000))]),
            Promise.race([crossPromise, new Promise((r) => setTimeout(() => r(null), 3_000))]),
          ])
          wsSameNode.listeners.delete(onSame)
          wsCrossNode.listeners.delete(onCross)
          if (sameMs !== null) sameNodeWsSamples.push(sameMs)
          if (crossMs !== null) crossNodeWsSamples.push(crossMs)
        }
      } finally {
        bgStop = true
        await Promise.allSettled(bgPromises)
        wsSameNode.ws.close()
        wsCrossNode.ws.close()
      }

      return {
        kind,
        samplesRequested: count,
        backgroundConcurrentFeedUsers: backgroundLoad,
        errors,
        access: percentiles(accessSamples),
        persist: percentiles(persistSamples),
        backendAck: percentiles(backendAckSamples),
        sameNodeWsDelivery: percentiles(sameNodeWsSamples),
        crossNodeWsDelivery: percentiles(crossNodeWsSamples),
      }
    }

    const dmChatForLatency = chatIds[1]
    const groupForLatency = groupIds[0]
    const messagingLatencyReport = {
      dmIdle: await measureMessagingLatency({
        kind: 'dm',
        targetId: dmChatForLatency,
        channel: `chat:${dmChatForLatency}`,
        count: 50,
        backgroundLoad: 0,
      }),
      dmUnder40UsersLoad: await measureMessagingLatency({
        kind: 'dm',
        targetId: dmChatForLatency,
        channel: `chat:${dmChatForLatency}`,
        count: 50,
        backgroundLoad: 40,
      }),
      groupIdle: await measureMessagingLatency({
        kind: 'group',
        targetId: groupForLatency,
        channel: `user:${users[3].id}`,
        count: 50,
        backgroundLoad: 0,
      }),
      groupUnder40UsersLoad: await measureMessagingLatency({
        kind: 'group',
        targetId: groupForLatency,
        channel: `user:${users[3].id}`,
        count: 50,
        backgroundLoad: 40,
      }),
    }

    // =========================================================================
    // PHASE 3: EXTENDED MULTI-INSTANCE SOAK + STAGING SOAK RUNNER VERIFICATION
    // =========================================================================
    console.error('[c221-222][Phase3] Verifying run-ubuntu-staging-soak.mjs safety guards & running extended multi-instance soak...')
    const soakScriptPath = path.join(repoRoot, 'tools/loadtest/run-ubuntu-staging-soak.mjs')
    const guardMissingFlag = spawnSync(process.execPath, [soakScriptPath], {
      env: { ...process.env, NOVVIN_STAGING_ISOLATED: '' },
      encoding: 'utf8',
    })
    const guardProdDomain = spawnSync(process.execPath, [soakScriptPath], {
      env: {
        ...process.env,
        NOVVIN_STAGING_ISOLATED: 'true',
        STAGING_API_URL: 'https://api.novvin.app',
      },
      encoding: 'utf8',
    })
    const guardNonLoopback = spawnSync(process.execPath, [soakScriptPath], {
      env: {
        ...process.env,
        NOVVIN_STAGING_ISOLATED: 'true',
        NOVVIN_ALLOW_NON_LOOPBACK_STAGING_HOST: 'false',
        STAGING_API_URL: 'http://10.20.30.40:3080',
      },
      encoding: 'utf8',
    })

    // Extended multi-instance soak (default 210s) with active WebSockets + Signaling clients + HTTP waves
    console.error(`[c221-222][Phase3] Running ${soakSeconds}s extended multi-instance soak test...`)
    const soakWsClients = await Promise.all(
      users.slice(0, 20).map((u, idx) => openAuthenticatedRealtimeWs(idx % 2 === 0 ? rt1Url : rt2Url, u.token, [])),
    )
    const soakLiveClients = await Promise.all(
      users.slice(0, 12).map((u, idx) => connectLiveNamespaceSocket(idx % 2 === 0 ? sig1Url : sig2Url, u)),
    )

    const soakSamples = []
    const soakStartMs = Date.now()
    const soakEndMs = soakStartMs + soakSeconds * 1000
    let soakIteration = 0
    let soakTotalRequests = 0
    let soakTotalUnexpected5xx = 0
    let soakTotalLoadShed = 0

    while (Date.now() < soakEndMs) {
      soakIteration += 1
      const wave = await runWave({
        name: `soak221_${soakIteration}`,
        concurrency: 40,
        requestsPerUser: 4,
        users,
        apiUrls: [api1Url, api2Url],
        chatIds,
        groupIds,
      })
      soakTotalRequests += wave.totalRequests
      soakTotalUnexpected5xx += wave.unexpected5xx
      soakTotalLoadShed += wave.loadShed503

      const elapsedSec = Math.round((Date.now() - soakStartMs) / 1000)
      if (soakIteration === 1 || soakIteration % 5 === 0 || Date.now() + 1500 >= soakEndMs) {
        const [m1, m2, r1, r2, s1, s2, redisInfo, pgStats] = await Promise.all([
          fetchJsonMetrics(api1Url, internalSecret, 'Authorization'),
          fetchJsonMetrics(api2Url, internalSecret, 'Authorization'),
          fetchJsonMetrics(rt1Url, internalSecret, 'Authorization'),
          fetchJsonMetrics(rt2Url, internalSecret, 'Authorization'),
          fetchJsonMetrics(sig1Url, liveToken, 'x-live-internal-token'),
          fetchJsonMetrics(sig2Url, liveToken, 'x-live-internal-token'),
          queryRedisInfo(redisHandle.url, redisPass),
          dbPool.query(`
            SELECT
              (SELECT count(*)::int FROM pg_stat_activity WHERE datname = current_database()) AS total_conns,
              (SELECT count(*)::int FROM pg_stat_activity WHERE datname = current_database() AND state = 'active') AS active_conns,
              xact_commit::bigint AS xact_commit,
              blks_hit::bigint AS blks_hit,
              blks_read::bigint AS blks_read
            FROM pg_stat_database
            WHERE datname = current_database()
          `),
        ])
        const pgRow = pgStats.rows[0] || {}
        const hits = Number(pgRow.blks_hit || 0)
        const reads = Number(pgRow.blks_read || 0)
        soakSamples.push({
          elapsedSeconds: elapsedSec,
          iteration: soakIteration,
          rps: wave.rps,
          p50Ms: wave.overall.p50Ms,
          p95Ms: wave.overall.p95Ms,
          p99Ms: wave.overall.p99Ms,
          unexpected5xx: wave.unexpected5xx,
          loadShed503: wave.loadShed503,
          api1RssMB: m1 ? Number((m1.memoryRssBytes / 1048576).toFixed(1)) : 0,
          api1HeapMB: m1 ? Number((m1.memoryHeapUsedBytes / 1048576).toFixed(1)) : 0,
          api2RssMB: m2 ? Number((m2.memoryRssBytes / 1048576).toFixed(1)) : 0,
          api2HeapMB: m2 ? Number((m2.memoryHeapUsedBytes / 1048576).toFixed(1)) : 0,
          api1LoopLagP95Ms: m1?.eventLoopLagP95Ms || 0,
          api2LoopLagP95Ms: m2?.eventLoopLagP95Ms || 0,
          api1PoolWaiting: m1?.dbPool?.waiting || 0,
          api2PoolWaiting: m2?.dbPool?.waiting || 0,
          rt1RssMB: r1 ? Number((r1.memoryRssBytes / 1048576).toFixed(1)) : 0,
          rt2RssMB: r2 ? Number((r2.memoryRssBytes / 1048576).toFixed(1)) : 0,
          sig1RssMB: s1 ? Number((s1.memoryRssBytes / 1048576).toFixed(1)) : 0,
          sig2RssMB: s2 ? Number((s2.memoryRssBytes / 1048576).toFixed(1)) : 0,
          redisUsedMemoryHuman: redisInfo?.usedMemoryHuman || 'n/a',
          redisUsedMemoryMB: redisInfo ? Number((redisInfo.usedMemoryBytes / 1048576).toFixed(2)) : 0,
          redisConnectedClients: redisInfo?.connectedClients || 0,
          redisEvictedKeys: redisInfo?.evictedKeys || 0,
          pgTotalConns: Number(pgRow.total_conns || 0),
          pgActiveConns: Number(pgRow.active_conns || 0),
          pgCacheHitPct: hits + reads > 0 ? Number(((hits / (hits + reads)) * 100).toFixed(2)) : 100,
        })
      }
      await new Promise((r) => setTimeout(r, 120))
    }

    for (const h of soakWsClients) { try { h.ws.close() } catch {} }
    for (const h of soakLiveClients) { try { h.sock.disconnect() } catch {} }

    // =========================================================================
    // PHASE 4: FAULT-INJECTION CHAOS & RECOVERY SUITE
    // =========================================================================
    console.error('[c221-222][Phase4] Running 4 Fault-Injection Chaos & Recovery Scenarios...')

    // Scenario 4A: PostgreSQL Slow Query / Lock Contention Injection during concurrent API traffic
    const lockClient = await dbPool.connect()
    let pgContention5xx = 0
    let pgContention2xx = 0
    try {
      await lockClient.query('BEGIN')
      // Hold row lock on posts[0] for 350ms while 30 concurrent requests hit feed & DM
      await lockClient.query('SELECT pg_sleep(0.35)')
      const contentionWave = await runWave({
        name: 'chaos_pg_contention',
        concurrency: 30,
        requestsPerUser: 2,
        users,
        apiUrls: [api1Url, api2Url],
        chatIds,
        groupIds,
      })
      pgContention5xx = contentionWave.unexpected5xx
      pgContention2xx = contentionWave.status2xx
      await lockClient.query('COMMIT')
    } finally {
      lockClient.release()
    }
    const [m1AfterLock, m2AfterLock] = await Promise.all([
      fetchJsonMetrics(api1Url, internalSecret, 'Authorization'),
      fetchJsonMetrics(api2Url, internalSecret, 'Authorization'),
    ])

    // Scenario 4B: Realtime-1 SIGKILL + 60-WebSocket Reconnect Storm onto Realtime-2
    const stormSocketsRt1 = await Promise.all(
      Array.from({ length: 30 }, (_, i) => openAuthenticatedRealtimeWs(rt1Url, users[i % 2].token, [`chat:${chatIds[0]}`])),
    )
    const stormSocketsRt2 = await Promise.all(
      Array.from({ length: 30 }, (_, i) => openAuthenticatedRealtimeWs(rt2Url, users[i % 2].token, [`chat:${chatIds[0]}`])),
    )
    const rtStormStart = performance.now()
    await stopNamed('rt1', 'SIGKILL')
    for (const h of stormSocketsRt1) { try { h.ws.close() } catch {} }
    // Reconnect all 30 clients onto Realtime-2 in a simultaneous storm
    const reconnectedOnRt2 = await Promise.all(
      Array.from({ length: 30 }, (_, i) => openAuthenticatedRealtimeWs(rt2Url, users[i % 2].token, [`chat:${chatIds[0]}`])),
    )
    const rtStormReconnectMs = Number((performance.now() - rtStormStart).toFixed(1))
    // Publish a message through API-2 -> verify all 60 connected clients on Realtime-2 receive it!
    const stormMsgId = crypto.randomUUID()
    await timedFetch(`${api2Url}/v1/data/messages/chats/${chatIds[0]}/messages`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${users[0].token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: stormMsgId, content: 'storm-verify-msg', contentType: 'text' }),
    })
    await new Promise((r) => setTimeout(r, 400))
    const all60Sockets = [...stormSocketsRt2, ...reconnectedOnRt2]
    const stormDeliveredCount = all60Sockets.filter((h) =>
      h.events.some((e) => e.channel === `chat:${chatIds[0]}` && e.payload?.message?.id === stormMsgId),
    ).length
    for (const h of all60Sockets) { try { h.ws.close() } catch {} }

    // Restart Realtime-1
    spawnNamed('rt1', path.join(repoRoot, 'infrastructure/local-fallback/realtime-gateway/src/server.js'), path.join(repoRoot, 'infrastructure/local-fallback/realtime-gateway'), makeRtEnv(rt1Port, api1Url))
    await waitForHttpOk(`${rt1Url}/health`, 15_000)

    // Scenario 4C: Cross-Node Password Change Instant WebSocket Revocation (Cycle 221 Security Fix #5)
    const pwChangeUser = users[23]
    const pwLogin = await timedFetch(`${api1Url}/v1/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: pwChangeUser.email, password: 'CertPassword221!Secure' }),
    })
    const pwOldToken = pwLogin.json?.accessToken
    const wsBeforePwChange = await openAuthenticatedRealtimeWs(rt2Url, pwOldToken, [])
    const pwRevokeStart = performance.now()
    const pwChangeRes = await timedFetch(`${api2Url}/v1/auth/change-password`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${pwOldToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        currentPassword: 'CertPassword221!Secure',
        newPassword: 'CertPassword221!Updated9',
      }),
    })
    let pwWsClosed = wsBeforePwChange.getClosedInfo()
    for (let i = 0; i < 30 && !pwWsClosed; i += 1) {
      await new Promise((r) => setTimeout(r, 30))
      pwWsClosed = wsBeforePwChange.getClosedInfo()
    }
    const pwWsRevokeLatencyMs = pwWsClosed ? Number((performance.now() - pwRevokeStart).toFixed(1)) : null
    wsBeforePwChange.ws.close()
    // Old token must immediately fail on API-1 (0ms stale auth cache!)
    const oldTokenCheckOnApi1 = await timedFetch(`${api1Url}/v1/auth/me`, {
      headers: { Authorization: `Bearer ${pwOldToken}` },
    })

    // Scenario 4D: Redis 7.4.8 Sudden Drop (SIGKILL) -> PG Fallback -> Auto-Recovery
    await redisHandle.killHard()
    await new Promise((r) => setTimeout(r, 200))
    const redisDownRes = await timedFetch(`${api1Url}/v1/data/messages/chats/${chatIds[2]}/messages`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${users[4].token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: crypto.randomUUID(), content: 'during-redis-down', contentType: 'text' }),
    })
    const redisRecoverStart = performance.now()
    await redisHandle.startAgain()
    let redisAutoRecoveryMs = null
    for (let i = 0; i < 40; i += 1) {
      const [m1, m2, r1, r2, s1, s2] = await Promise.all([
        fetchJsonMetrics(api1Url, internalSecret, 'Authorization'),
        fetchJsonMetrics(api2Url, internalSecret, 'Authorization'),
        timedFetch(`${rt1Url}/health`),
        timedFetch(`${rt2Url}/health`),
        timedFetch(`${sig1Url}/health`),
        timedFetch(`${sig2Url}/health`),
      ])
      if (
        m1?.rateLimitRedisReady &&
        m2?.rateLimitRedisReady &&
        r1.json?.redisConnected &&
        r2.json?.redisConnected &&
        s1.json?.realtimeBus?.connected &&
        s2.json?.realtimeBus?.connected
      ) {
        redisAutoRecoveryMs = Number((performance.now() - redisRecoverStart).toFixed(1))
        break
      }
      await new Promise((r) => setTimeout(r, 150))
    }

    const chaosSuiteReport = {
      passed:
        pgContention5xx === 0 &&
        pgContention2xx === 60 &&
        (m1AfterLock?.dbPool?.waiting || 0) === 0 &&
        (m2AfterLock?.dbPool?.waiting || 0) === 0 &&
        reconnectedOnRt2.length === 30 &&
        stormDeliveredCount === 60 &&
        pwChangeRes.status === 200 &&
        pwWsClosed?.code === 4401 &&
        pwWsClosed?.reason === 'WEB_SESSION_REVOKED' &&
        oldTokenCheckOnApi1.status === 401 &&
        redisDownRes.status === 201 &&
        redisAutoRecoveryMs !== null,
      pgLockContention: {
        status2xx: pgContention2xx,
        unexpected5xx: pgContention5xx,
        poolWaitingAfterRelease: (m1AfterLock?.dbPool?.waiting || 0) + (m2AfterLock?.dbPool?.waiting || 0),
      },
      realtimeSigkillAnd60WsStorm: {
        reconnectedCount: reconnectedOnRt2.length,
        reconnectStormLatencyMs: rtStormReconnectMs,
        postStormDeliveredTo60Clients: stormDeliveredCount,
      },
      crossNodePasswordChangeWsRevocation: {
        changePasswordStatusOnApi2: pwChangeRes.status,
        oldTokenStatusOnApi1: oldTokenCheckOnApi1.status,
        wsClosedOnRt2: pwWsClosed,
        wsRevokeLatencyMs: pwWsRevokeLatencyMs,
      },
      redisSigkillAndFullClusterRecovery: {
        writeDuringOutageStatus: redisDownRes.status,
        fullClusterAutoRecoveryMs: redisAutoRecoveryMs,
      },
    }

    // =========================================================================
    // PHASE 5: CYCLE 221–222 NOVEL SECURITY FIXES & BUSINESS RULES MATRIX E2E
    // =========================================================================
    console.error('[c221-222][Phase5] Verifying Cycle 221–222 Novel Security Fixes & Business Rules Matrix E2E...')
    const alice = users[0]
    const bob = users[1]
    const charlie = users[4]
    const authA = { Authorization: `Bearer ${alice.token}`, 'Content-Type': 'application/json' }
    const authB = { Authorization: `Bearer ${bob.token}`, 'Content-Type': 'application/json' }
    const authC = { Authorization: `Bearer ${charlie.token}`, 'Content-Type': 'application/json' }

    // 5.1 Live Points E2E (previously crashed with 500 TypeError: identity.ids.includes is not a function)
    const lpBalanceRes = await timedFetch(`${api1Url}/v1/data/live/${liveId}/points/balance`, { headers: authA })
    const lpRewardsRes = await timedFetch(`${api2Url}/v1/data/live/${liveId}/points/rewards`, { headers: authA })

    // 5.2 Music Report Sybil resistance & 404 on unknown track
    const unknownTrackReport = await timedFetch(`${api1Url}/v1/data/music/${crypto.randomUUID()}/report`, {
      method: 'POST', headers: authB, body: JSON.stringify({ reason: 'copyright' }),
    })

    // 5.3 Moments non-numeric durationHours (previously 500 RangeError) + visibility allowlist
    const momentObjPath = `${alice.id}/moment221.jpg`
    const momentPublicUrl = `${api1Url}/storage/v1/object/public/moment-media/${momentObjPath}`
    await dbPool.query(
      `INSERT INTO fallback_storage_objects (id, bucket_id, name, owner_id, content_type, size_bytes, public_url, local_path, created_at, updated_at)
       VALUES ($1::uuid, 'moment-media', $2, $3::uuid, 'image/jpeg', 1024, $4, $5, NOW(), NOW())
       ON CONFLICT (bucket_id, name) DO NOTHING`,
      [crypto.randomUUID(), momentObjPath, alice.id, momentPublicUrl, path.join(storageDir, 'moment-media', momentObjPath)],
    )
    const momentBadDur = await timedFetch(`${api1Url}/v1/data/moments`, {
      method: 'POST',
      headers: authA,
      body: JSON.stringify({
        mediaUrl: momentPublicUrl,
        caption: 'Safe moment 221',
        mediaType: 'image',
        durationHours: 'not-a-number',
        visibility: 'DROP_TABLE',
      }),
    })

    // 5.4 Cycle 219/220 regression checks (null byte, NaN mediaSize, non-string payload, deep JSON, cross-node delete, call race, BOLA)
    const rNullByte = await timedFetch(`${api1Url}/v1/data/messages/chats/${chatIds[0]}/messages`, {
      method: 'POST', headers: authA, body: JSON.stringify({ content: 'hello\u0000world', contentType: 'text' }),
    })
    const rNanMediaDm = await timedFetch(`${api1Url}/v1/data/messages/chats/${chatIds[0]}/messages`, {
      method: 'POST', headers: authA, body: JSON.stringify({ content: 'ok', contentType: 'text', mediaSize: 'NaN' }),
    })
    const rNonStrDm = await timedFetch(`${api1Url}/v1/data/messages/chats/${chatIds[0]}/messages`, {
      method: 'POST', headers: authA, body: JSON.stringify({ content: { bad: 1 }, contentType: ['text'] }),
    })
    let deepObj = { a: 1 }
    for (let d = 0; d < 60; d += 1) deepObj = { n: deepObj }
    const rDeepJson = await timedFetch(`${api1Url}/v1/data/profile/me`, {
      method: 'PATCH', headers: authA, body: JSON.stringify(deepObj),
    })

    const msgCrossBola = await timedFetch(`${api1Url}/v1/data/messages/chats/${chatIds[0]}/messages`, {
      method: 'POST', headers: authA, body: JSON.stringify({ id: crypto.randomUUID(), content: 'private-221', contentType: 'text' }),
    })
    const bolaMsgId = msgCrossBola.json?.message?.id
    const charlieDeleteCross = await timedFetch(`${api2Url}/v1/data/messages/chats/${chatIds[0]}/messages/${bolaMsgId}/delete`, {
      method: 'POST', headers: authC, body: JSON.stringify({ forEveryone: true }),
    })

    const securityAndBusinessReport = {
      passed:
        lpBalanceRes.status === 200 &&
        lpRewardsRes.status === 200 && Array.isArray(lpRewardsRes.json?.rewards) &&
        unknownTrackReport.status === 404 && unknownTrackReport.json?.error === 'TRACK_NOT_FOUND' &&
        momentBadDur.status === 201 && momentBadDur.json?.moment?.visibility === 'all' &&
        rNullByte.status === 400 && rNullByte.json?.error === 'INVALID_NULL_BYTE' &&
        rNanMediaDm.status === 201 &&
        rNonStrDm.status === 400 && rNonStrDm.json?.error === 'INVALID_MESSAGE_PAYLOAD' &&
        rDeepJson.status === 400 && rDeepJson.json?.error === 'PAYLOAD_TOO_DEEPLY_NESTED' &&
        charlieDeleteCross.status === 403 &&
        guardMissingFlag.status === 2 &&
        guardProdDomain.status === 2 &&
        guardNonLoopback.status === 2,
      checks: {
        livePointsBalance200: lpBalanceRes.status,
        livePointsRewards200: lpRewardsRes.status,
        musicReportUnknownTrack404: { status: unknownTrackReport.status, error: unknownTrackReport.json?.error },
        momentInvalidDurationAndVisibilityNormalized201: {
          status: momentBadDur.status,
          visibility: momentBadDur.json?.moment?.visibility,
        },
        nullByteBlocked400: rNullByte.status,
        nanMediaSizeSafe201: rNanMediaDm.status,
        nonStringDmBlocked400: rNonStrDm.status,
        deepJsonBlocked400: rDeepJson.status,
        crossNodeBolaDeleteBlocked403: charlieDeleteCross.status,
        ubuntuStagingSoakGuards: {
          missingFlagExitCode: guardMissingFlag.status,
          prodDomainExitCode: guardProdDomain.status,
          nonLoopbackExitCode: guardNonLoopback.status,
        },
      },
    }

    const report = {
      executedAt: new Date().toISOString(),
      environment: {
        isolatedLoopback: true,
        hostPlatform: `${process.platform}-${process.arch}`,
        nodeVersion: process.version,
        cpuCount: os.cpus().length,
        totalRamGB: Number((os.totalmem() / 1073741824).toFixed(1)),
        databaseEngine: 'PostgreSQL 17 (isolated ephemeral cluster, max_connections=220)',
        redisEngine: `Redis ${initialRedisInfo.redisVersion} (isolated WSL2 ephemeral instance, requirepass+AOF+256MB)`,
      },
      phase1LiveStreamsMultiInstanceRehydration: liveRehydrationReport,
      phase2ScalabilityAndLatency: {
        redis2ApiBenchmark: benchRedis2x,
        messagingLatency: messagingLatencyReport,
      },
      phase3ExtendedSoak: {
        durationSeconds: soakSeconds,
        iterations: soakIteration,
        totalRequests: soakTotalRequests,
        totalUnexpected5xx: soakTotalUnexpected5xx,
        totalLoadShed503: soakTotalLoadShed,
        passed: soakTotalUnexpected5xx === 0,
        samples: soakSamples,
      },
      phase4ChaosAndRecovery: chaosSuiteReport,
      phase5SecurityAndBusinessRules: securityAndBusinessReport,
    }

    const allPassed =
      report.phase1LiveStreamsMultiInstanceRehydration.passed &&
      report.phase3ExtendedSoak.passed &&
      report.phase4ChaosAndRecovery.passed &&
      report.phase5SecurityAndBusinessRules.passed &&
      benchRedis2x.every((w) => w.unexpected5xx === 0 && w.networkErrors === 0)

    report.summary = { allPassed }

    const outPath = process.env.NOVVIN_C221_REPORT_PATH
    if (outPath) {
      fs.mkdirSync(path.dirname(outPath), { recursive: true })
      fs.writeFileSync(outPath, JSON.stringify(report, null, 2), 'utf8')
    }
    console.log(JSON.stringify(report, null, 2))
    if (!allPassed) process.exitCode = 1
  } finally {
    await Promise.all(Object.keys(children).map((name) => stopNamed(name)))
    if (redisHandle) await redisHandle.stop().catch(() => {})
    if (dbPool) await dbPool.end().catch(() => {})
    if (pgStarted) {
      spawnSync(path.join(pgBin, `pg_ctl${ext}`), ['-D', pgData, '-m', 'fast', 'stop'], { stdio: 'ignore' })
    }
    logStream.end()
    try { fs.rmSync(workDir, { recursive: true, force: true }) } catch {}
  }
}

main().catch((error) => {
  console.error('[c221-222] FATAL:', error)
  process.exit(1)
})
