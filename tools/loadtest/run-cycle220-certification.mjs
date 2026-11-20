#!/usr/bin/env node
/**
 * NOVVIN Cycle 220 Certification Harness (Isolated Loopback Only)
 *
 * Real isolated multi-instance stack on 127.0.0.1:
 *  - Ephemeral PostgreSQL 17 cluster
 *  - Ephemeral Redis 7.4.8 (real redis-server via WSL2, identical SHA-256 to Ubuntu alpine image)
 *  - 2x API instances, 2x Realtime Gateway instances (Redis Streams), 2x Signaling Server instances (Redis Streams)
 *
 * Phases:
 *  1. Scalability A/B/C Benchmark & Load Shedding (PG 1x vs Redis 1x vs Redis 2x up to 1,000-1,500 concurrent users)
 *  2. Multi-Instance Consistency & Chaos (Redis Lua atomicity, TTL, Redis SIGKILL -> PG fallback -> auto-recovery;
 *     Realtime Streams 100 ordered msgs 0 dup / 0 loss + cross-node session revocation;
 *     Signaling Streams 40 clients across 2 nodes + SIGKILL failover)
 *  3. Messaging Latency Breakdown (DM & Group: access, persist, backendAck, same-node WS, cross-node WS; idle + loaded)
 *  4. Real Multi-Instance Soak Test (configurable duration, default 180s; RAM/heap/RPS/p95/p99/pool/PG/Redis INFO)
 *  5. Security & Business Invariants Regression (219 findings re-verified + cross-node multi-device call race + INV-04)
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
const { consumeDistributedFixedWindow } = require(path.join(repoRoot, 'infrastructure/local-fallback/api/src/distributed-rate-limit'))

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
    'with tempfile.TemporaryDirectory(prefix="novvin-c220-redis-") as tmp:',
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
              content: `c220-dm-${name}-${workerIndex}-${i}`,
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
              content: `c220-grp-${name}-${workerIndex}-${i}`,
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

async function seedDataset(apiUrl, dbPool) {
  const users = []
  for (let i = 0; i < 24; i += 1) {
    const email = `c220user${i}@novvin.test`
    const reg = await timedFetch(`${apiUrl}/v1/auth/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        email,
        password: 'CertPassword220!Secure',
        displayName: `C220 User ${i}`,
        username: `c220user${i}`,
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

  // Create 12 true 1-on-1 DM chats (users[2*i] <-> users[2*i+1]) with accepted friendship so intro policy allows sustained chat
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

  // Create 2 groups with all 24 members
  const groupIds = []
  for (let g = 0; g < 2; g += 1) {
    const gid = crypto.randomUUID()
    await dbPool.query(
      `INSERT INTO fallback_collection_rooms (id, room_kind, name, creator_id, is_public, created_at, updated_at)
       VALUES ($1::uuid, 'group', $2, $3, TRUE, NOW(), NOW())`,
      [gid, `C220 Group ${g}`, users[g].id],
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

  // Create 25 posts with comments and reactions to make GET /v1/data/feed realistic
  const postIds = []
  for (let p = 0; p < 25; p += 1) {
    const author = users[p % users.length]
    const r = await timedFetch(`${apiUrl}/v1/data/feed/posts`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${author.token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: `C220 realistic feed post #${p} for load and engagement benchmarks` }),
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
  const soakSeconds = Math.max(20, Math.min(Number(process.env.NOVVIN_C220_SOAK_SECONDS || 180), 600))
  const pgBin = resolvePgBin()
  const ext = process.platform === 'win32' ? '.exe' : ''
  const workDir = fs.mkdtempSync(path.join(os.tmpdir(), 'novvin-c220-'))
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
  const pgPass = 'novvin_c220_local_pw'
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
    console.error(`[c220] Booting ephemeral PostgreSQL 17 on 127.0.0.1:${pgPort}...`)
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

    console.error(`[c220] Booting real Redis 7.4.8 (WSL2 isolated) on 127.0.0.1:${redisPort}...`)
    redisHandle = await startLocalRedis748({ port: redisPort, password: redisPass, workDir, logStream })
    const initialRedisInfo = await queryRedisInfo(redisHandle.url, redisPass)
    console.error(`[c220] Redis ${initialRedisInfo.redisVersion} READY (used=${initialRedisInfo.usedMemoryHuman})`)

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
      REALTIME_REDIS_STREAM_NAME: 'novvin:c220:realtime:events',
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
      SIGNALING_INTERNAL_URL: sig1Url,
      ADMIN_SYNC_TOKEN: adminToken,
      CORS_ORIGIN: 'http://127.0.0.1',
      AUTH_REGISTER_RATE_LIMIT_PER_HOUR: '100',
      MESSAGE_WRITE_RATE_LIMIT_PER_MINUTE: String(msgLimit),
      SOCIAL_WRITE_RATE_LIMIT_PER_MINUTE: '5000',
      CALL_INITIATE_RATE_LIMIT_PER_MINUTE: '50',
      PASSWORD_RESET_DELIVERY: 'disabled',
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
      SIGNALING_REDIS_STREAM_NAME: 'novvin:c220:signaling:events',
      SIGNALING_REDIS_STREAM_COUNT: '4',
      SIGNALING_METRICS_TOKEN: internalSecret,
      LIVE_INTERNAL_EVENTS_TOKEN: liveToken,
      SIGNALING_MAX_SOCKETS_PER_IP: '500',
    })

    // Boot Realtime-1 first and API-1 (PG-only mode first for Config A baseline)
    spawnNamed(
      'rt1',
      path.join(repoRoot, 'infrastructure/local-fallback/realtime-gateway/src/server.js'),
      path.join(repoRoot, 'infrastructure/local-fallback/realtime-gateway'),
      makeRtEnv(rt1Port, api1Url),
    )
    await waitForHttpOk(`${rt1Url}/health`, 15_000)

    spawnNamed(
      'api1',
      path.join(repoRoot, 'infrastructure/local-fallback/api/src/server.js'),
      path.join(repoRoot, 'infrastructure/local-fallback/api'),
      makeApiEnv(api1Port, rt1Url, { useRedis: false }),
    )
    await waitForHttpOk(`${api1Url}/health`, 30_000)

    console.error('[c220] Seeding isolated dataset (24 users, 10 DM chats, 2 groups, 25 posts)...')
    const { users, chatIds, groupIds, postIds } = await seedDataset(api1Url, dbPool)

    // =========================================================================
    // PHASE 1: SCALABILITY A/B/C BENCHMARK & LOAD SHEDDING
    // =========================================================================
    const rampTiers = [
      { concurrency: 50, requestsPerUser: 6 },
      { concurrency: 100, requestsPerUser: 5 },
      { concurrency: 250, requestsPerUser: 4 },
      { concurrency: 500, requestsPerUser: 3 },
      { concurrency: 1000, requestsPerUser: 2 },
    ]

    async function runBenchmarkSuite(label, apiUrls, extraTiers = []) {
      const tiers = [...rampTiers, ...extraTiers]
      const results = []
      // Warmup
      await runWave({
        name: `${label}_warmup`,
        concurrency: 20,
        requestsPerUser: 2,
        users,
        apiUrls,
        chatIds,
        groupIds,
      })
      for (const tier of tiers) {
        console.error(`[c220][Phase1:${label}] concurrency=${tier.concurrency}...`)
        const wave = await runWave({
          name: `${label}_${tier.concurrency}`,
          concurrency: tier.concurrency,
          requestsPerUser: tier.requestsPerUser,
          users,
          apiUrls,
          chatIds,
          groupIds,
        })
        const metricsList = await Promise.all(apiUrls.map((u) => fetchJsonMetrics(u, internalSecret, 'Authorization')))
        results.push({ ...wave, apiMetrics: metricsList })
        await new Promise((r) => setTimeout(r, 700))
      }
      return results
    }

    async function runWriteHeavyWave({ name, concurrency, requestsPerUser, apiUrls }) {
      const latencies = []
      let status2xx = 0
      let unexpected5xx = 0
      let rr = 0
      const started = performance.now()
      await Promise.all(
        Array.from({ length: concurrency }, async (_, w) => {
          const user = users[w % users.length]
          const headers = { Authorization: `Bearer ${user.token}`, 'Content-Type': 'application/json' }
          const chatId = chatIds[Math.floor((w % users.length) / 2) % chatIds.length]
          const groupId = groupIds[w % groupIds.length]
          for (let i = 0; i < requestsPerUser; i += 1) {
            const url = apiUrls[(rr++) % apiUrls.length]
            const res = i % 2 === 0
              ? await timedFetch(`${url}/v1/data/messages/chats/${chatId}/messages`, {
                  method: 'POST', headers, body: JSON.stringify({ id: crypto.randomUUID(), content: `wh-${name}-${w}-${i}`, contentType: 'text' }),
                })
              : await timedFetch(`${url}/v1/data/messages/groups/${groupId}/messages`, {
                  method: 'POST', headers, body: JSON.stringify({ id: crypto.randomUUID(), content: `whg-${name}-${w}-${i}`, contentType: 'text' }),
                })
            latencies.push(res.ms)
            if (res.status >= 200 && res.status < 300) status2xx += 1
            else if (res.status >= 500 || res.status === 0) unexpected5xx += 1
          }
        }),
      )
      const elapsedSec = Math.max(0.001, (performance.now() - started) / 1000)
      return {
        name,
        concurrency,
        totalRequests: latencies.length,
        elapsedSeconds: Number(elapsedSec.toFixed(2)),
        rps: Number((latencies.length / elapsedSec).toFixed(1)),
        status2xx,
        unexpected5xx,
        overall: percentiles(latencies),
      }
    }

    // Config A: 1x API, PG Rate Limiter (no Redis)
    const benchPg1x = await runBenchmarkSuite('pg_1api', [api1Url])

    // Boot Realtime-2 + API-2 (PG-only first) to measure pure write-heavy 2x API PG vs 2x API Redis A/B
    spawnNamed(
      'rt2',
      path.join(repoRoot, 'infrastructure/local-fallback/realtime-gateway/src/server.js'),
      path.join(repoRoot, 'infrastructure/local-fallback/realtime-gateway'),
      makeRtEnv(rt2Port, api2Url),
    )
    await waitForHttpOk(`${rt2Url}/health`, 15_000)

    spawnNamed(
      'api2',
      path.join(repoRoot, 'infrastructure/local-fallback/api/src/server.js'),
      path.join(repoRoot, 'infrastructure/local-fallback/api'),
      makeApiEnv(api2Port, rt2Url, { useRedis: false }),
    )
    await waitForHttpOk(`${api2Url}/health`, 30_000)
    console.error('[c220][Phase1:write_ab] 2x API write-heavy PG baseline (c=100, c=250)...')
    const writeAbPg100 = await runWriteHeavyWave({ name: 'write_pg_2api_100', concurrency: 100, requestsPerUser: 4, apiUrls: [api1Url, api2Url] })
    const writeAbPg250 = await runWriteHeavyWave({ name: 'write_pg_2api_250', concurrency: 250, requestsPerUser: 3, apiUrls: [api1Url, api2Url] })
    await stopNamed('api2')

    // Restart API-1 with Redis Rate Limiter enabled for Config B
    await stopNamed('api1')
    spawnNamed(
      'api1',
      path.join(repoRoot, 'infrastructure/local-fallback/api/src/server.js'),
      path.join(repoRoot, 'infrastructure/local-fallback/api'),
      makeApiEnv(api1Port, rt1Url, { useRedis: true }),
    )
    await waitForHttpOk(`${api1Url}/health`, 30_000)
    await new Promise((r) => setTimeout(r, 400))

    const benchRedis1x = await runBenchmarkSuite('redis_1api', [api1Url])

    // Boot API-2 with Redis Rate Limiter for Config C (2x API horizontal scale)
    spawnNamed(
      'api2',
      path.join(repoRoot, 'infrastructure/local-fallback/api/src/server.js'),
      path.join(repoRoot, 'infrastructure/local-fallback/api'),
      makeApiEnv(api2Port, rt2Url, { useRedis: true }),
    )
    await waitForHttpOk(`${api2Url}/health`, 30_000)
    await new Promise((r) => setTimeout(r, 400))

    console.error('[c220][Phase1:write_ab] 2x API write-heavy Redis candidate (c=100, c=250)...')
    const writeAbRedis100 = await runWriteHeavyWave({ name: 'write_redis_2api_100', concurrency: 100, requestsPerUser: 4, apiUrls: [api1Url, api2Url] })
    const writeAbRedis250 = await runWriteHeavyWave({ name: 'write_redis_2api_250', concurrency: 250, requestsPerUser: 3, apiUrls: [api1Url, api2Url] })

    const benchRedis2x = await runBenchmarkSuite('redis_2api', [api1Url, api2Url], [
      { concurrency: 1500, requestsPerUser: 2 },
    ])

    const postPhase1RedisInfo = await queryRedisInfo(redisHandle.url, redisPass)

    // =========================================================================
    // PHASE 2: MULTI-INSTANCE CONSISTENCY, REDIS STREAMS & CHAOS
    // =========================================================================
    console.error('[c220][Phase2] Starting Multi-Instance Consistency, Redis Streams & Chaos tests...')

    // 2A. Rate Limiter Atomicity across 2x API + TTL + Redis SIGKILL -> PG Fallback -> Auto-Recovery
    // Temporarily restart API-1 and API-2 with MESSAGE_WRITE_RATE_LIMIT_PER_MINUTE=20 for exact race test
    await Promise.all([stopNamed('api1'), stopNamed('api2')])
    spawnNamed(
      'api1',
      path.join(repoRoot, 'infrastructure/local-fallback/api/src/server.js'),
      path.join(repoRoot, 'infrastructure/local-fallback/api'),
      makeApiEnv(api1Port, rt1Url, { useRedis: true, msgLimit: 20 }),
    )
    spawnNamed(
      'api2',
      path.join(repoRoot, 'infrastructure/local-fallback/api/src/server.js'),
      path.join(repoRoot, 'infrastructure/local-fallback/api'),
      makeApiEnv(api2Port, rt2Url, { useRedis: true, msgLimit: 20 }),
    )
    await Promise.all([waitForHttpOk(`${api1Url}/health`, 30_000), waitForHttpOk(`${api2Url}/health`, 30_000)])
    await new Promise((r) => setTimeout(r, 500))

    // Clear ephemeral rate-limit counters from Phase 1 before exact limit=20 race & fallback checks
    await dbPool.query('DELETE FROM fallback_rate_limit_windows')
    const resetRlClient = createRedisRespClient({ url: redisHandle.url, password: redisPass, commandTimeoutMs: 1_000 })
    if (await resetRlClient.connect()) {
      const rlKeys = await resetRlClient.sendCommand(['KEYS', 'novvin:rl:*'])
      if (Array.isArray(rlKeys) && rlKeys.length > 0) {
        await resetRlClient.sendCommand(['DEL', ...rlKeys])
      }
      await resetRlClient.close()
    }

    const rlActor = users[14]
    const rlChatId = chatIds[7]
    const rlRaceResults = await Promise.all(
      Array.from({ length: 50 }, (_, idx) => {
        const targetApi = idx % 2 === 0 ? api1Url : api2Url
        return timedFetch(`${targetApi}/v1/data/messages/chats/${rlChatId}/messages`, {
          method: 'POST',
          headers: { Authorization: `Bearer ${rlActor.token}`, 'Content-Type': 'application/json' },
          body: JSON.stringify({ id: crypto.randomUUID(), content: `rl-race-${idx}`, contentType: 'text' }),
        })
      }),
    )
    const rlAllowedCount = rlRaceResults.filter((r) => r.status === 201).length
    const rlLimitedCount = rlRaceResults.filter((r) => r.status === 429).length
    const rl5xxCount = rlRaceResults.filter((r) => r.status >= 500 || r.status === 0).length

    // Direct TTL expiration & window reset verification against real Redis 7.4.8
    const ttlTestClient = createRedisRespClient({ url: redisHandle.url, password: redisPass, commandTimeoutMs: 500 })
    await ttlTestClient.connect()
    const ttlKey = `ttl-test-${crypto.randomUUID()}`
    const t1 = await consumeDistributedFixedWindow({ pool: dbPool, redisClient: ttlTestClient, scope: 'test:ttl', key: ttlKey, limit: 2, windowMs: 1200 })
    const t2 = await consumeDistributedFixedWindow({ pool: dbPool, redisClient: ttlTestClient, scope: 'test:ttl', key: ttlKey, limit: 2, windowMs: 1200 })
    const t3 = await consumeDistributedFixedWindow({ pool: dbPool, redisClient: ttlTestClient, scope: 'test:ttl', key: ttlKey, limit: 2, windowMs: 1200 })
    await new Promise((r) => setTimeout(r, 1350))
    const t4AfterExpiry = await consumeDistributedFixedWindow({ pool: dbPool, redisClient: ttlTestClient, scope: 'test:ttl', key: ttlKey, limit: 2, windowMs: 1200 })
    await ttlTestClient.close()

    // Chaos: SIGKILL Redis 7.4.8 -> verify API-1 & API-2 fail over cleanly to PostgreSQL (0 5xx, limit enforced) -> restart Redis -> verify auto-recovery
    const preKillM1 = await fetchJsonMetrics(api1Url, internalSecret, 'Authorization')
    const preKillM2 = await fetchJsonMetrics(api2Url, internalSecret, 'Authorization')
    await redisHandle.killHard()
    await new Promise((r) => setTimeout(r, 250))

    const fallbackActor = users[16]
    const fallbackChatId = chatIds[8]
    const outageResults = []
    for (let i = 0; i < 24; i += 1) {
      const targetApi = i % 2 === 0 ? api1Url : api2Url
      outageResults.push(await timedFetch(`${targetApi}/v1/data/messages/chats/${fallbackChatId}/messages`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${fallbackActor.token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ id: crypto.randomUUID(), content: `pg-fallback-${i}`, contentType: 'text' }),
      }))
    }
    const outageAllowed = outageResults.filter((r) => r.status === 201).length
    const outageLimited = outageResults.filter((r) => r.status === 429).length
    const outage5xx = outageResults.filter((r) => r.status >= 500 || r.status === 0).length
    const duringOutageM1 = await fetchJsonMetrics(api1Url, internalSecret, 'Authorization')
    const duringOutageM2 = await fetchJsonMetrics(api2Url, internalSecret, 'Authorization')

    const redisRestartStart = performance.now()
    await redisHandle.startAgain()
    let redisRecoveredMs = null
    for (let attempt = 0; attempt < 40; attempt += 1) {
      const [m1, m2] = await Promise.all([
        fetchJsonMetrics(api1Url, internalSecret, 'Authorization'),
        fetchJsonMetrics(api2Url, internalSecret, 'Authorization'),
      ])
      if (m1?.rateLimitRedisReady && m2?.rateLimitRedisReady) {
        redisRecoveredMs = Number((performance.now() - redisRestartStart).toFixed(1))
        break
      }
      await new Promise((r) => setTimeout(r, 150))
    }
    const postRecoverHitsBefore = ((await fetchJsonMetrics(api1Url, internalSecret, 'Authorization'))?.rateLimitRedisHits || 0)
      + ((await fetchJsonMetrics(api2Url, internalSecret, 'Authorization'))?.rateLimitRedisHits || 0)
    const recoverActor = users[18]
    const recoverChatId = chatIds[9]
    const postRecoverRes1 = await timedFetch(`${api1Url}/v1/data/messages/chats/${recoverChatId}/messages`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${recoverActor.token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: crypto.randomUUID(), content: 'after-redis-recovery-1', contentType: 'text' }),
    })
    const postRecoverRes2 = await timedFetch(`${api2Url}/v1/data/messages/chats/${recoverChatId}/messages`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${recoverActor.token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: crypto.randomUUID(), content: 'after-redis-recovery-2', contentType: 'text' }),
    })
    const postRecoverHitsAfter = ((await fetchJsonMetrics(api1Url, internalSecret, 'Authorization'))?.rateLimitRedisHits || 0)
      + ((await fetchJsonMetrics(api2Url, internalSecret, 'Authorization'))?.rateLimitRedisHits || 0)

    const rateLimitMultiInstanceReport = {
      passed:
        rlAllowedCount === 20 &&
        rlLimitedCount === 30 &&
        rl5xxCount === 0 &&
        t1.allowed && t2.allowed && !t3.allowed && t4AfterExpiry.allowed &&
        outage5xx === 0 && outageAllowed === 20 && outageLimited === 4 &&
        ((duringOutageM1?.rateLimitPgFallbacks || 0) + (duringOutageM2?.rateLimitPgFallbacks || 0))
          > ((preKillM1?.rateLimitPgFallbacks || 0) + (preKillM2?.rateLimitPgFallbacks || 0)) &&
        redisRecoveredMs !== null &&
        postRecoverRes1.status === 201 && postRecoverRes2.status === 201 &&
        postRecoverHitsAfter >= postRecoverHitsBefore + 2,
      race50Across2ApisLimit20: { allowed201: rlAllowedCount, limited429: rlLimitedCount, errors5xx: rl5xxCount },
      ttlWindowReset: {
        req1Allowed: t1.allowed,
        req2Allowed: t2.allowed,
        req3Allowed: t3.allowed,
        after1350msAllowed: t4AfterExpiry.allowed,
      },
      redisSigkillFallbackAndRecovery: {
        outageAllowed201: outageAllowed,
        outageLimited429: outageLimited,
        outageErrors5xx: outage5xx,
        pgFallbackDelta: ((duringOutageM1?.rateLimitPgFallbacks || 0) + (duringOutageM2?.rateLimitPgFallbacks || 0))
          - ((preKillM1?.rateLimitPgFallbacks || 0) + (preKillM2?.rateLimitPgFallbacks || 0)),
        redisAutoRecoveryMs: redisRecoveredMs,
        postRecoveryRedisHitsDelta: postRecoverHitsAfter - postRecoverHitsBefore,
      },
    }

    // Restore API-1 and API-2 to high message rate limit for Phases 2B, 2C, 3, 4, 5
    await Promise.all([stopNamed('api1'), stopNamed('api2')])
    spawnNamed(
      'api1',
      path.join(repoRoot, 'infrastructure/local-fallback/api/src/server.js'),
      path.join(repoRoot, 'infrastructure/local-fallback/api'),
      makeApiEnv(api1Port, rt1Url, { useRedis: true, msgLimit: 5000 }),
    )
    spawnNamed(
      'api2',
      path.join(repoRoot, 'infrastructure/local-fallback/api/src/server.js'),
      path.join(repoRoot, 'infrastructure/local-fallback/api'),
      makeApiEnv(api2Port, rt2Url, { useRedis: true, msgLimit: 5000 }),
    )
    await Promise.all([waitForHttpOk(`${api1Url}/health`, 30_000), waitForHttpOk(`${api2Url}/health`, 30_000)])
    // Ensure Realtime-1 and Realtime-2 reconnected to Redis Streams after Redis restart
    for (let i = 0; i < 40; i += 1) {
      const [h1, h2] = await Promise.all([timedFetch(`${rt1Url}/health`), timedFetch(`${rt2Url}/health`)])
      if (h1.json?.redisConnected && h2.json?.redisConnected) break
      await new Promise((r) => setTimeout(r, 150))
    }

    // 2B. Realtime Gateway Multi-Instance Redis Streams (100 ordered msgs across 2 APIs & 2 Realtime nodes + cross-node logout)
    console.error('[c220][Phase2B] Testing Realtime Gateway 2x Redis Streams (100 ordered msgs + cross-node revocation)...')
    const sender = users[0]
    const recipient = users[1]
    const streamChatId = chatIds[0]
    const wsOnRt1 = await openAuthenticatedRealtimeWs(rt1Url, recipient.token, [`chat:${streamChatId}`])
    const wsOnRt2 = await openAuthenticatedRealtimeWs(rt2Url, recipient.token, [`chat:${streamChatId}`])

    const sentOrder = []
    for (let seq = 1; seq <= 100; seq += 1) {
      // Publish through API-1 -> Realtime-1 -> Redis Stream -> Realtime-2 with strict sequencing
      const msgId = crypto.randomUUID()
      sentOrder.push(msgId)
      const r = await timedFetch(`${api1Url}/v1/data/messages/chats/${streamChatId}/messages`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${sender.token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ id: msgId, content: `stream-seq-${seq}`, contentType: 'text' }),
      })
      if (r.status !== 201) throw new Error(`Stream test msg ${seq} failed: ${r.status}`)
      // Yield 8ms so post-commit async deliveryTask publishes in deterministic order
      await new Promise((r) => setTimeout(r, 8))
    }

    // Wait up to 5s for both WS receivers to collect all 100 unique chat events
    const deadlineWs = Date.now() + 5_000
    while (Date.now() < deadlineWs) {
      const c1 = wsOnRt1.events.filter((e) => e.channel === `chat:${streamChatId}` && e.payload?.event === 'message').length
      const c2 = wsOnRt2.events.filter((e) => e.channel === `chat:${streamChatId}` && e.payload?.event === 'message').length
      if (c1 >= 100 && c2 >= 100) break
      await new Promise((r) => setTimeout(r, 50))
    }

    function analyzeStreamDelivery(wsHandle) {
      const chatMsgs = wsHandle.events
        .filter((e) => e.channel === `chat:${streamChatId}` && e.payload?.event === 'message')
        .map((e) => e.payload?.message?.id)
      const uniqueIds = new Set(chatMsgs)
      const duplicates = chatMsgs.length - uniqueIds.size
      const missing = sentOrder.filter((id) => !uniqueIds.has(id)).length
      let orderInversions = 0
      const indexById = new Map(sentOrder.map((id, idx) => [id, idx]))
      let prevIdx = -1
      for (const id of chatMsgs) {
        const curIdx = indexById.get(id)
        if (curIdx !== undefined) {
          if (curIdx < prevIdx) orderInversions += 1
          prevIdx = curIdx
        }
      }
      return { received: chatMsgs.length, unique: uniqueIds.size, duplicates, missing, orderInversions }
    }

    const rt1Delivery = analyzeStreamDelivery(wsOnRt1)
    const rt2Delivery = analyzeStreamDelivery(wsOnRt2)
    wsOnRt1.ws.close()
    wsOnRt2.ws.close()

    // Cross-node session revocation test: login on API-1 -> WS on Realtime-2 -> logout on API-2 -> API-1 rejects & Realtime-2 closes via Redis Stream!
    const loginCross = await timedFetch(`${api1Url}/v1/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: users[18].email, password: 'CertPassword220!Secure' }),
    })
    const crossToken = loginCross.json?.accessToken
    const crossRefreshToken = loginCross.json?.refreshToken
    const wsRevoke = await openAuthenticatedRealtimeWs(rt2Url, crossToken, [])
    const revokeStart = performance.now()
    const logoutCross = await timedFetch(`${api2Url}/v1/auth/logout`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${crossToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: crossRefreshToken }),
    })
    const api1AfterLogout = await timedFetch(`${api1Url}/v1/auth/me`, {
      headers: { Authorization: `Bearer ${crossToken}` },
    })
    let wsRevokeClosed = wsRevoke.getClosedInfo()
    for (let i = 0; i < 30 && !wsRevokeClosed; i += 1) {
      await new Promise((r) => setTimeout(r, 30))
      wsRevokeClosed = wsRevoke.getClosedInfo()
    }
    const wsRevokeLatencyMs = wsRevokeClosed ? Number((performance.now() - revokeStart).toFixed(1)) : null
    wsRevoke.ws.close()

    const [rt1Metrics, rt2Metrics] = await Promise.all([
      fetchJsonMetrics(rt1Url, internalSecret, 'Authorization'),
      fetchJsonMetrics(rt2Url, internalSecret, 'Authorization'),
    ])

    const realtimeMultiInstanceReport = {
      passed:
        rt1Delivery.unique === 100 && rt1Delivery.duplicates === 0 && rt1Delivery.missing === 0 && rt1Delivery.orderInversions === 0 &&
        rt2Delivery.unique === 100 && rt2Delivery.duplicates === 0 && rt2Delivery.missing === 0 && rt2Delivery.orderInversions === 0 &&
        logoutCross.status === 200 && api1AfterLogout.status === 401 &&
        wsRevokeClosed?.code === 4401 && wsRevokeClosed?.reason === 'WEB_SESSION_REVOKED' &&
        rt1Metrics?.redisConnected === true && rt2Metrics?.redisConnected === true,
      sentTotal: 100,
      rt1Receiver: rt1Delivery,
      rt2Receiver: rt2Delivery,
      crossNodeRevocation: {
        logoutStatusOnApi2: logoutCross.status,
        api1MeStatusPostLogout: api1AfterLogout.status,
        wsClosedInfoOnRt2: wsRevokeClosed,
        wsRevokeLatencyMs,
      },
      rt1Metrics,
      rt2Metrics,
    }

    // 2C. Signaling Server Multi-Instance Redis Streams (2x Signaling, 40 clients, cross-node events + SIGKILL failover)
    console.error('[c220][Phase2C] Testing Signaling Server 2x Redis Streams (40 clients across 2 nodes + SIGKILL failover)...')
    spawnNamed(
      'sig1',
      path.join(repoRoot, 'signaling-server/index.js'),
      path.join(repoRoot, 'signaling-server'),
      makeSigEnv(sig1Port, api1Url),
    )
    spawnNamed(
      'sig2',
      path.join(repoRoot, 'signaling-server/index.js'),
      path.join(repoRoot, 'signaling-server'),
      makeSigEnv(sig2Port, api2Url),
    )
    await Promise.all([waitForHttpOk(`${sig1Url}/health`, 20_000), waitForHttpOk(`${sig2Url}/health`, 20_000)])

    const voiceRoomRes = await timedFetch(`${api1Url}/v1/data/voice/rooms`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${users[0].token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'C220 Multi-Node Voice Room', isPublic: true, maxParticipants: 100 }),
    })
    const voiceRoomId = voiceRoomRes.json?.room?.id || voiceRoomRes.json?.id
    if (!voiceRoomId) throw new Error(`Voice room create failed: ${voiceRoomRes.status} ${voiceRoomRes.text}`)

    async function connectSignalingBatch(targetUrl, userSubset) {
      const sockets = []
      const failed = []
      await Promise.all(
        userSubset.map((u) => new Promise((resolve) => {
          const sock = ioClient(targetUrl, {
            transports: ['websocket'],
            reconnection: false,
            auth: { token: u.token },
            extraHeaders: { Origin: 'http://127.0.0.1' },
            timeout: 8_000,
          })
          let done = false
          const finish = (ok, reason = '') => {
            if (done) return
            done = true
            clearTimeout(t)
            if (ok) sockets.push({ sock, user: u })
            else { failed.push(reason || 'failed'); try { sock.disconnect() } catch {} }
            resolve()
          }
          const t = setTimeout(() => finish(false, 'timeout'), 8_000)
          sock.once('connect', () => {
            sock.emit('join-room', { roomId: voiceRoomId, userId: u.id, userName: u.email.split('@')[0] })
            finish(true)
          })
          sock.once('connect_error', (err) => finish(false, err.message))
        })),
      )
      return { sockets, failed }
    }

    const batch1Users = users.slice(0, 12)
    const batch2Users = users.slice(12, 24)
    const sig1Batch = await connectSignalingBatch(sig1Url, batch1Users)
    const sig2Batch = await connectSignalingBatch(sig2Url, batch2Users)
    await new Promise((r) => setTimeout(r, 400))

    // Cross-node room chat & reaction order/delivery check: emit from sig1 -> observe on sig2 (and reverse)
    const sig2ObservedChat = []
    const sig1ObservedChat = []
    const sig2ObservedReactions = []
    const observerOnSig2 = sig2Batch.sockets[0]?.sock
    const observerOnSig1 = sig1Batch.sockets[0]?.sock
    observerOnSig2?.on('chat-message', (msg) => sig2ObservedChat.push(msg))
    observerOnSig1?.on('chat-message', (msg) => sig1ObservedChat.push(msg))
    observerOnSig2?.on('reaction', (ev) => sig2ObservedReactions.push(ev))

    const emitter1 = sig1Batch.sockets[1 % sig1Batch.sockets.length]
    const emitter2 = sig2Batch.sockets[1 % sig2Batch.sockets.length]
    for (let i = 1; i <= 15; i += 1) {
      emitter1.sock.emit('chat-message', {
        roomId: voiceRoomId,
        text: `sig1-to-sig2-${i}`,
      })
      await new Promise((r) => setTimeout(r, 30))
    }
    for (let i = 1; i <= 15; i += 1) {
      emitter2.sock.emit('chat-message', {
        roomId: voiceRoomId,
        text: `sig2-to-sig1-${i}`,
      })
      await new Promise((r) => setTimeout(r, 30))
    }
    // Emit reactions from 5 distinct users on Sig-1 -> observe cross-node over Redis Streams on Sig-2
    for (let i = 2; i < Math.min(7, sig1Batch.sockets.length); i += 1) {
      const entry = sig1Batch.sockets[i]
      entry.sock.emit('reaction', { roomId: voiceRoomId, userId: entry.user.id, emoji: '🔥' })
    }
    await new Promise((r) => setTimeout(r, 700))

    const crossFrom1To2 = sig2ObservedChat
      .map((m) => String(m?.text || m?.content || ''))
      .filter((c) => c.startsWith('sig1-to-sig2-'))
    const crossFrom2To1 = sig1ObservedChat
      .map((m) => String(m?.text || m?.content || ''))
      .filter((c) => c.startsWith('sig2-to-sig1-'))

    // SIGKILL Signaling-1 -> reconnect batch1Users onto Signaling-2
    const failoverStart = performance.now()
    await stopNamed('sig1', 'SIGKILL')
    for (const entry of sig1Batch.sockets) { try { entry.sock.disconnect() } catch {} }
    const failoverBatch = await connectSignalingBatch(sig2Url, batch1Users)
    const failoverMs = Number((performance.now() - failoverStart).toFixed(1))

    const [sig2Health, sig2Metrics] = await Promise.all([
      timedFetch(`${sig2Url}/health`),
      fetchJsonMetrics(sig2Url, liveToken, 'x-live-internal-token'),
    ])

    // Restart sig1 so Phase 4 soak runs with 2x Signaling
    spawnNamed(
      'sig1',
      path.join(repoRoot, 'signaling-server/index.js'),
      path.join(repoRoot, 'signaling-server'),
      makeSigEnv(sig1Port, api1Url),
    )
    await waitForHttpOk(`${sig1Url}/health`, 20_000)

    const signalingMultiInstanceReport = {
      passed:
        sig1Batch.sockets.length === 12 && sig2Batch.sockets.length === 12 &&
        crossFrom1To2.length === 15 && crossFrom2To1.length === 15 &&
        sig2ObservedReactions.length >= 5 &&
        failoverBatch.sockets.length === 12 && failoverBatch.failed.length === 0 &&
        sig2Health.json?.realtimeBus?.mode === 'redis-streams' && sig2Health.json?.realtimeBus?.connected === true,
      initialConnectedSig1: sig1Batch.sockets.length,
      initialConnectedSig2: sig2Batch.sockets.length,
      crossNodeChatSig1ToSig2: crossFrom1To2.length,
      crossNodeChatSig2ToSig1: crossFrom2To1.length,
      crossNodeReactionsSig1ToSig2: sig2ObservedReactions.length,
      sig1SigkillFailoverToSig2: {
        reconnected: failoverBatch.sockets.length,
        failed: failoverBatch.failed.length,
        failoverMs,
      },
      sig2RedisStatus: sig2Health.json?.realtimeBus || null,
      sig2StateConsistency: sig2Health.json?.stateConsistency || null,
      sig2Metrics,
    }

    // Disconnect phase 2C sockets cleanly before Phase 3
    for (const entry of [...sig2Batch.sockets, ...failoverBatch.sockets]) { try { entry.sock.disconnect() } catch {} }

    // =========================================================================
    // PHASE 3: MESSAGING LATENCY BREAKDOWN (DM & GROUP: IDLE + UNDER LOAD)
    // =========================================================================
    console.error('[c220][Phase3] Measuring end-to-end DM & Group messaging latency breakdown (idle + under load)...')
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
            body: JSON.stringify({ id: msgId, content: `lat-${kind}-${i}`, contentType: 'text' }),
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
    // PHASE 4: REAL MULTI-INSTANCE SOAK TEST (2x API + 2x RT + 2x SIG + REDIS + PG)
    // =========================================================================
    console.error(`[c220][Phase4] Running ${soakSeconds}s real multi-instance soak test...`)
    // Keep 20 live WebSockets across rt1/rt2 + 20 Socket.IO clients across sig1/sig2 during soak
    const soakWsClients = await Promise.all(
      users.slice(0, 20).map((u, idx) => openAuthenticatedRealtimeWs(idx % 2 === 0 ? rt1Url : rt2Url, u.token, [])),
    )
    const soakSig1 = await connectSignalingBatch(sig1Url, users.slice(0, 10))
    const soakSig2 = await connectSignalingBatch(sig2Url, users.slice(10, 20))

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
        name: `soak_${soakIteration}`,
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
    for (const entry of [...soakSig1.sockets, ...soakSig2.sockets]) { try { entry.sock.disconnect() } catch {} }

    // =========================================================================
    // PHASE 5: SECURITY REGRESSION (219 FINDINGS RE-TESTED) + CROSS-NODE INVARIANTS
    // =========================================================================
    console.error('[c220][Phase5] Re-verifying 219 security fixes & cross-node concurrency invariants...')
    const alice = users[0]
    const bob = users[1]
    const charlie = users[4]
    const authA = { Authorization: `Bearer ${alice.token}`, 'Content-Type': 'application/json' }
    const authB = { Authorization: `Bearer ${bob.token}`, 'Content-Type': 'application/json' }
    const authC = { Authorization: `Bearer ${charlie.token}`, 'Content-Type': 'application/json' }

    // Re-test every 219 crash vector
    const rNullByte = await timedFetch(`${api1Url}/v1/data/messages/chats/${chatIds[0]}/messages`, {
      method: 'POST', headers: authA, body: JSON.stringify({ content: 'hello\u0000world', contentType: 'text' }),
    })
    const rNanMediaDm = await timedFetch(`${api1Url}/v1/data/messages/chats/${chatIds[0]}/messages`, {
      method: 'POST', headers: authA, body: JSON.stringify({ content: 'ok', contentType: 'text', mediaSize: 'NaN' }),
    })
    const rNonStrDm = await timedFetch(`${api1Url}/v1/data/messages/chats/${chatIds[0]}/messages`, {
      method: 'POST', headers: authA, body: JSON.stringify({ content: { bad: 1 }, contentType: ['text'] }),
    })
    const rNonStrGroup = await timedFetch(`${api2Url}/v1/data/messages/groups/${groupIds[0]}/messages`, {
      method: 'POST', headers: authA, body: JSON.stringify({ content: 12345, contentType: 'text', mediaSize: 'NaN' }),
    })
    let deepObj = { a: 1 }
    for (let d = 0; d < 60; d += 1) deepObj = { n: deepObj }
    const rDeepJson = await timedFetch(`${api1Url}/v1/data/profile/me`, {
      method: 'PATCH', headers: authA, body: JSON.stringify(deepObj),
    })
    const rInternalNoAuth = await timedFetch(`${api1Url}/internal/metrics`)

    // INV-04 under singleflightLoad: create post on API-1 -> read feed on API-2 -> delete on API-2 -> immediate feed read on API-1
    const cPost = await timedFetch(`${api1Url}/v1/data/feed/posts`, {
      method: 'POST', headers: authA, body: JSON.stringify({ content: 'cross-node-delete-check-220' }),
    })
    const cPostId = cPost.json?.post?.id || cPost.json?.id
    const delPost = await timedFetch(`${api2Url}/v1/data/feed/posts/${cPostId}`, { method: 'DELETE', headers: authA })
    const feedAfterDel = await timedFetch(`${api1Url}/v1/data/feed?limit=60`, { headers: authB })
    const resurrected = (feedAfterDel.json?.items || []).some((item) => item.id === cPostId)

    // Cross-node multi-device call accept race (Bob Device 1 on API-1 vs Bob Device 2 on API-2 simultaneously)
    const bobSecondLogin = await timedFetch(`${api2Url}/v1/auth/login`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: bob.email, password: 'CertPassword220!Secure' }),
    })
    const authB2 = { Authorization: `Bearer ${bobSecondLogin.json?.accessToken || ''}`, 'Content-Type': 'application/json' }
    const callId = crypto.randomUUID()
    await timedFetch(`${api1Url}/v1/data/calls/prepare`, {
      method: 'POST', headers: authA,
      body: JSON.stringify({ callId, contextType: 'dm', contextId: chatIds[0], inviteeIds: [bob.id] }),
    })
    const [acc1, acc2] = await Promise.all([
      timedFetch(`${api1Url}/v1/data/calls/${callId}/accept`, { method: 'POST', headers: authB, body: JSON.stringify({}) }),
      timedFetch(`${api2Url}/v1/data/calls/${callId}/accept`, { method: 'POST', headers: authB2, body: JSON.stringify({}) }),
    ])
    const callRaceStatuses = [acc1.status, acc2.status].sort((a, b) => a - b)
    const callConflictCode = (acc1.status === 409 ? acc1.json?.error : acc2.json?.error) || null
    await timedFetch(`${api1Url}/v1/data/calls/${callId}/leave`, {
      method: 'POST', headers: authA, body: JSON.stringify({ action: 'end' }),
    })

    // Cross-node BOLA check
    const msgCrossBola = await timedFetch(`${api1Url}/v1/data/messages/chats/${chatIds[0]}/messages`, {
      method: 'POST', headers: authA, body: JSON.stringify({ id: crypto.randomUUID(), content: 'private-220', contentType: 'text' }),
    })
    const bolaMsgId = msgCrossBola.json?.message?.id
    const charlieDeleteCross = await timedFetch(`${api2Url}/v1/data/messages/chats/${chatIds[0]}/messages/${bolaMsgId}/delete`, {
      method: 'POST', headers: authC, body: JSON.stringify({ forEveryone: true }),
    })

    const securityRegressionReport = {
      passed:
        rNullByte.status === 400 && rNullByte.json?.error === 'INVALID_NULL_BYTE' &&
        rNanMediaDm.status === 201 &&
        rNonStrDm.status === 400 && rNonStrDm.json?.error === 'INVALID_MESSAGE_PAYLOAD' &&
        rNonStrGroup.status === 400 && rNonStrGroup.json?.error === 'INVALID_MESSAGE_PAYLOAD' &&
        rDeepJson.status === 400 && rDeepJson.json?.error === 'PAYLOAD_TOO_DEEPLY_NESTED' &&
        rInternalNoAuth.status === 401 &&
        delPost.status === 200 && !resurrected &&
        callRaceStatuses[0] === 200 && callRaceStatuses[1] === 409 &&
        charlieDeleteCross.status === 403,
      checks: {
        nullByteBlocked400: { status: rNullByte.status, error: rNullByte.json?.error },
        nanMediaSizeSafe201: { status: rNanMediaDm.status },
        nonStringDmBlocked400: { status: rNonStrDm.status, error: rNonStrDm.json?.error },
        nonStringGroupBlocked400: { status: rNonStrGroup.status, error: rNonStrGroup.json?.error },
        deepJsonBlocked400: { status: rDeepJson.status, error: rDeepJson.json?.error },
        internalMetricsUnauth401: { status: rInternalNoAuth.status },
        crossNodePostDeleteNoResurrection: { deleteStatus: delPost.status, resurrected },
        crossNodeCallAcceptRace: { statuses: callRaceStatuses, conflictError: callConflictCode },
        crossNodeBolaDeleteBlocked: { status: charlieDeleteCross.status },
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
        dbPoolMaxPerApi: 25,
        dbPoolLoadShedWaitingPerApi: 150,
      },
      phase1Scalability: {
        pg1Api: benchPg1x,
        redis1Api: benchRedis1x,
        redis2Api: benchRedis2x,
        writeHeavy2ApiAB: {
          pg100: writeAbPg100,
          pg250: writeAbPg250,
          redis100: writeAbRedis100,
          redis250: writeAbRedis250,
        },
        postBenchmarkRedisInfo: postPhase1RedisInfo,
      },
      phase2MultiInstance: {
        rateLimitRedisAndChaos: rateLimitMultiInstanceReport,
        realtimeGatewayStreams: realtimeMultiInstanceReport,
        signalingServerStreams: signalingMultiInstanceReport,
      },
      phase3MessagingLatency: messagingLatencyReport,
      phase4SoakMultiInstance: {
        durationSeconds: soakSeconds,
        iterations: soakIteration,
        totalRequests: soakTotalRequests,
        totalUnexpected5xx: soakTotalUnexpected5xx,
        totalLoadShed503: soakTotalLoadShed,
        passed: soakTotalUnexpected5xx === 0,
        samples: soakSamples,
      },
      phase5SecurityRegression: securityRegressionReport,
    }

    const allPassed =
      report.phase2MultiInstance.rateLimitRedisAndChaos.passed &&
      report.phase2MultiInstance.realtimeGatewayStreams.passed &&
      report.phase2MultiInstance.signalingServerStreams.passed &&
      report.phase4SoakMultiInstance.passed &&
      report.phase5SecurityRegression.passed &&
      [...benchPg1x, ...benchRedis1x, ...benchRedis2x].every((w) => w.unexpected5xx === 0 && w.networkErrors === 0)

    report.summary = { allPassed }

    const outPath = process.env.NOVVIN_C220_REPORT_PATH
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
  console.error('[c220] FATAL:', error)
  process.exit(1)
})
