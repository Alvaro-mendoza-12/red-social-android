#!/usr/bin/env node
/**
 * NOVVIN Deep Technical Certification Suite (Isolated Loopback Only)
 *
 * Covers:
 *  A. Breakpoint Ramp (10 -> 25 -> 50 -> 100 -> 250 -> 500 -> 1,000 concurrent users)
 *     + Graceful Load Shedding proof + Post-Breakpoint Recovery
 *  B. Extended Soak Test (60s continuous traffic, memory/GC/pool/lag tracking)
 *  C. Controlled Chaos Engineering (Realtime SIGKILL, PostgreSQL stall, Signaling restart + Socket.IO storm)
 *  D. Real PostgreSQL 17 pg_dump -> pg_restore Backup & Integrity Drill (exact row counts + SHA-256)
 *  E. 14 Live Business-Rule Invariants + 42-Vector Automated Security/Robustness Fuzzer (0 HTTP 500s)
 *
 * SAFETY: Strictly 127.0.0.1 ephemeral ports and temporary PostgreSQL 17 cluster in os.tmpdir().
 * Never touches production, active Ubuntu servers, external services, or real user data.
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

const PG_BIN_CANDIDATES = [
  process.env.NOVVIN_PG_BIN,
  'C:\\Program Files\\PostgreSQL\\17\\bin',
  'C:\\Program Files\\PostgreSQL\\16\\bin',
  '/usr/lib/postgresql/17/bin',
  '/usr/lib/postgresql/16/bin',
  '/usr/bin',
].filter(Boolean)

function resolvePgBin() {
  const ext = process.platform === 'win32' ? '.exe' : ''
  for (const dir of PG_BIN_CANDIDATES) {
    if (
      fs.existsSync(path.join(dir, `initdb${ext}`)) &&
      fs.existsSync(path.join(dir, `pg_ctl${ext}`)) &&
      fs.existsSync(path.join(dir, `pg_dump${ext}`)) &&
      fs.existsSync(path.join(dir, `pg_restore${ext}`))
    ) {
      return dir
    }
  }
  throw new Error('PostgreSQL binaries (initdb/pg_ctl/pg_dump/pg_restore) not found')
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
    await new Promise((r) => setTimeout(r, 120))
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
    await new Promise((r) => setTimeout(r, 200))
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

async function timedFetch(url, options = {}) {
  const started = performance.now()
  try {
    const res = await fetch(url, { signal: AbortSignal.timeout(options.timeoutMs || 15_000), ...options })
    const text = await res.text()
    let json = null
    try { json = text ? JSON.parse(text) : null } catch {}
    return { ok: res.ok, status: res.status, ms: performance.now() - started, json, text: text.slice(0, 4096), headers: res.headers }
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

async function runWave({ name, concurrency, requestsPerUser, users, apiUrl, chatIds, groupIds }) {
  const latencies = []
  const byAction = { feed: [], profile: [], dmSend: [], groupSend: [], notifications: [] }
  const criticalErrors = { dmSend: 0, groupSend: 0, profile: 0, notifications: 0 }
  let status2xx = 0
  let status4xx = 0
  let status429 = 0
  let loadShed503 = 0
  let unexpected5xx = 0
  let networkErrors = 0
  const started = performance.now()

  await Promise.all(
    Array.from({ length: concurrency }, async (_, workerIndex) => {
      const pairCount = Math.min(chatIds.length, Math.floor(users.length / 2))
      const actorIdx = workerIndex % (pairCount * 2)
      const actor = users[actorIdx]
      const chatId = chatIds[Math.floor(actorIdx / 2)]
      const groupId = groupIds[workerIndex % groupIds.length]
      const peer = users[(actorIdx + 1) % (pairCount * 2)]
      const authHeaders = {
        Authorization: `Bearer ${actor.token}`,
        'Content-Type': 'application/json',
        'x-correlation-id': `cert-${name}-${workerIndex}`,
      }

      for (let step = 0; step < requestsPerUser; step++) {
        const mod = (workerIndex + step) % 5
        let res
        let bucket
        if (mod === 0) {
          bucket = 'feed'
          res = await timedFetch(`${apiUrl}/v1/data/feed?limit=10`, { headers: authHeaders })
        } else if (mod === 1) {
          bucket = 'dmSend'
          res = await timedFetch(`${apiUrl}/v1/data/messages/chats/${chatId}/messages`, {
            method: 'POST',
            headers: authHeaders,
            body: JSON.stringify({
              content: `cert-dm-${workerIndex}-${step}`,
              contentType: 'text',
              clientMessageId: crypto.randomUUID(),
            }),
          })
        } else if (mod === 2) {
          bucket = 'groupSend'
          res = await timedFetch(`${apiUrl}/v1/data/messages/groups/${groupId}/messages`, {
            method: 'POST',
            headers: authHeaders,
            body: JSON.stringify({
              content: `cert-grp-${workerIndex}-${step}`,
              contentType: 'text',
              clientMessageId: crypto.randomUUID(),
            }),
          })
        } else if (mod === 3) {
          bucket = 'profile'
          res = await timedFetch(`${apiUrl}/v1/data/profiles/${peer.id}`, { headers: authHeaders })
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

async function runBackupRestoreDrill({ pgBin, ext, pgPort, pgPass, workDir, primaryUrl }) {
  const dumpFile = path.join(workDir, 'novvin_backup.dump')
  const env = { ...process.env, PGPASSWORD: pgPass }

  const dumpStart = performance.now()
  const dumpRes = spawnSync(
    path.join(pgBin, `pg_dump${ext}`),
    ['-h', '127.0.0.1', '-p', String(pgPort), '-U', 'postgres', '-Fc', '-f', dumpFile, 'postgres'],
    { env, encoding: 'utf8' },
  )
  const dumpMs = Number((performance.now() - dumpStart).toFixed(1))
  if (dumpRes.status !== 0) {
    throw new Error(`pg_dump failed: ${dumpRes.stderr || dumpRes.stdout}`)
  }
  const dumpSizeBytes = fs.statSync(dumpFile).size

  const adminPool = new Pool({ connectionString: primaryUrl })
  try {
    await adminPool.query('CREATE DATABASE novvin_restore_drill')
  } finally {
    await adminPool.end()
  }

  const restoreStart = performance.now()
  const restoreRes = spawnSync(
    path.join(pgBin, `pg_restore${ext}`),
    ['-h', '127.0.0.1', '-p', String(pgPort), '-U', 'postgres', '-d', 'novvin_restore_drill', '--no-owner', '--no-acl', dumpFile],
    { env, encoding: 'utf8' },
  )
  const restoreMs = Number((performance.now() - restoreStart).toFixed(1))
  if (restoreRes.status !== 0) {
    throw new Error(`pg_restore failed: ${restoreRes.stderr || restoreRes.stdout}`)
  }

  const restoredUrl = `postgresql://postgres:${pgPass}@127.0.0.1:${pgPort}/novvin_restore_drill`
  const tables = [
    'fallback_users',
    'fallback_chats',
    'fallback_messages',
    'fallback_collection_rooms',
    'fallback_room_messages',
    'fallback_posts',
  ]

  async function fingerprintDb(url) {
    const p = new Pool({ connectionString: url })
    try {
      const counts = {}
      for (const table of tables) {
        const { rows } = await p.query(`SELECT COUNT(*)::int AS c FROM ${table}`)
        counts[table] = rows[0].c
      }
      const { rows: msgRows } = await p.query(
        'SELECT id::text, chat_id, sender_id, content FROM fallback_messages ORDER BY id ASC',
      )
      const { rows: roomRows } = await p.query(
        'SELECT id::text, room_id, sender_id, content FROM fallback_room_messages ORDER BY id ASC',
      )
      const sha256 = crypto
        .createHash('sha256')
        .update(JSON.stringify({ msgRows, roomRows }))
        .digest('hex')
      return { counts, sha256 }
    } finally {
      await p.end()
    }
  }

  const [primaryFp, restoredFp] = await Promise.all([fingerprintDb(primaryUrl), fingerprintDb(restoredUrl)])
  const countsMatch = tables.every((t) => primaryFp.counts[t] === restoredFp.counts[t])
  const checksumMatch = primaryFp.sha256 === restoredFp.sha256

  return {
    passed: countsMatch && checksumMatch && primaryFp.counts.fallback_messages > 0,
    dumpMs,
    dumpSizeBytes,
    restoreRtoMs: restoreMs,
    countsMatch,
    checksumMatch,
    rowCounts: primaryFp.counts,
    sha256: primaryFp.sha256,
  }
}

async function runSignalingChaosAndStress({ signalingUrl, internalSecret, apiUrl, users, spawnSignaling, stopSignaling }) {
  const host = users[0]
  // Create a real public voice room via API so signaling admits real authenticated Socket.IO clients
  const createdRoom = await timedFetch(`${apiUrl}/v1/data/voice/rooms`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${host.token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: 'Cert Voice Stress Room', isPublic: true, maxParticipants: 150 }),
  })
  const roomId = createdRoom.json?.room?.id || createdRoom.json?.id
  if (!roomId) {
    throw new Error(`Voice room creation failed: status=${createdRoom.status} body=${createdRoom.text}`)
  }

  const clientCount = 40
  async function connectVoiceBatch(count) {
    const sockets = []
    let failed = 0
    const start = performance.now()
    await Promise.all(
      Array.from({ length: count }, (_, idx) => new Promise((resolve) => {
        const actor = users[idx % users.length]
        const sock = ioClient(signalingUrl, {
          transports: ['websocket'],
          reconnection: false,
          auth: { token: actor.token },
          extraHeaders: { Origin: 'http://127.0.0.1' },
          timeout: 8_000,
        })
        let done = false
        const finish = (ok) => {
          if (done) return
          done = true
          clearTimeout(timer)
          if (ok) sockets.push({ sock, actor })
          else { failed += 1; try { sock.disconnect() } catch {} }
          resolve()
        }
        const timer = setTimeout(() => finish(false), 8_000)
        sock.once('connect', () => {
          sock.emit('join-room', { roomId, userId: actor.id, userName: actor.displayName })
          finish(true)
        })
        sock.once('connect_error', () => finish(false))
      })),
    )
    return { sockets, failed, connectMs: Number((performance.now() - start).toFixed(1)) }
  }

  // 1. Connect 40 authenticated Socket.IO clients into the live Voice Room
  const wave1 = await connectVoiceBatch(clientCount)
  await new Promise((r) => setTimeout(r, 350))

  // 2. Reaction spam throttle test + malformed signaling payload fuzzing
  // Use the last connected socket because signaling replaces older duplicate sockets for the same userId.
  const spammerEntry = wave1.sockets[wave1.sockets.length - 1]
  const spammer = spammerEntry?.sock
  if (spammer && spammerEntry?.actor) {
    for (let i = 0; i < 20; i++) {
      spammer.emit('reaction', { roomId, userId: spammerEntry.actor.id, emoji: '🔥', maliciousExtra: 'x'.repeat(5000) })
    }
    // Malformed / oversized WebRTC signaling payloads
    spammer.emit('offer', { roomId, to: 'non-existent', sdp: { type: 'offer', sdp: 'A'.repeat(200_000) } })
    spammer.emit('ice-candidate', { roomId, to: null, candidate: { malformed: true } })
    spammer.emit('mute-state', { roomId, isMuted: 'not-a-boolean' })
  }
  await new Promise((r) => setTimeout(r, 400))
  const preKillMetrics = await fetchJsonMetrics(signalingUrl, internalSecret, 'x-live-internal-token')

  // 3. Chaos: Hard kill Signaling Server while 40 clients are connected, restart, measure reconnect storm!
  wave1.sockets.forEach(({ sock }) => { try { sock.disconnect() } catch {} })
  await stopSignaling('SIGKILL')
  const restartStart = performance.now()
  await spawnSignaling()
  await waitForHttpOk(`${signalingUrl}/health`, 15_000)
  const restartRtoMs = Number((performance.now() - restartStart).toFixed(1))

  const wave2 = await connectVoiceBatch(clientCount)
  await new Promise((r) => setTimeout(r, 300))
  const postRecoverMetrics = await fetchJsonMetrics(signalingUrl, internalSecret, 'x-live-internal-token')
  wave2.sockets.forEach(({ sock }) => { try { sock.disconnect() } catch {} })

  return {
    passed:
      wave1.sockets.length === clientCount &&
      wave2.sockets.length === clientCount &&
      Number(preKillMetrics?.reactionThrottled || 0) > 0 &&
      Boolean(postRecoverMetrics),
    initialConnected: wave1.sockets.length,
    initialFailed: wave1.failed,
    initialConnectMs: wave1.connectMs,
    reactionThrottledObserved: Number(preKillMetrics?.reactionThrottled || 0),
    preKillVoiceSockets: preKillMetrics?.voiceSockets ?? null,
    restartRtoMs,
    reconnectStormConnected: wave2.sockets.length,
    reconnectStormFailed: wave2.failed,
    reconnectStormMs: wave2.connectMs,
    postRecoverMetrics,
  }
}

async function runBusinessInvariantsAndFuzzer({ apiUrl, internalSecret, users, chatIds, dbPool }) {
  const alice = users[0]
  const bob = users[1]
  const charlie = users[2]
  const dave = users[3]
  const aliceBobChat = chatIds[0]
  const invariants = []

  // INV-01: 5x parallel identical DM send with same clientMessageId -> 1x 201 + 4x 200, exact 1 DB row
  const idemId = crypto.randomUUID()
  const idemCalls = await Promise.all(
    Array.from({ length: 5 }, () =>
      timedFetch(`${apiUrl}/v1/data/messages/chats/${aliceBobChat}/messages`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${alice.token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ content: 'inv-01-idem', contentType: 'text', clientMessageId: idemId }),
      }),
    ),
  )
  const { rows: idemRows } = await dbPool.query('SELECT COUNT(*)::int AS c FROM fallback_messages WHERE id = $1::uuid', [idemId])
  const idemIds = new Set(idemCalls.map((r) => r.json?.message?.id).filter(Boolean))
  invariants.push({
    id: 'INV-01',
    name: 'Message idempotency under 5x parallel retry race',
    passed: idemCalls.every((r) => r.status === 200 || r.status === 201) && idemIds.size === 1 && idemRows[0].c === 1,
    observed: { statuses: idemCalls.map((r) => r.status), uniqueIds: idemIds.size, dbRowCount: idemRows[0].c },
  })

  // INV-02: BOLA — Bob & Charlie cannot edit or delete Alice's message
  const bobEdit = await timedFetch(`${apiUrl}/v1/data/messages/chats/${aliceBobChat}/messages/${idemId}`, {
    method: 'PATCH',
    headers: { Authorization: `Bearer ${bob.token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ content: 'hacked-by-bob' }),
  })
  const charlieDelete = await timedFetch(`${apiUrl}/v1/data/messages/chats/${aliceBobChat}/messages/${idemId}/delete`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${charlie.token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ scope: 'everyone' }),
  })
  const { rows: intactRows } = await dbPool.query('SELECT content FROM fallback_messages WHERE id = $1::uuid', [idemId])
  invariants.push({
    id: 'INV-02',
    name: 'BOLA message edit/delete blocked for non-authors and non-participants',
    passed: bobEdit.status === 403 && (charlieDelete.status === 403 || charlieDelete.status === 404) && intactRows[0]?.content === 'inv-01-idem',
    observed: { bobEditStatus: bobEdit.status, charlieDeleteStatus: charlieDelete.status, contentIntact: intactRows[0]?.content === 'inv-01-idem' },
  })

  // INV-03: Blocked user enforcement (Alice blocks Bob -> Bob blocked on DM -> Alice unblocks -> DM succeeds)
  const blockRes = await timedFetch(`${apiUrl}/v1/data/profiles/${bob.id}/block`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${alice.token}`, 'Content-Type': 'application/json' },
    body: '{}',
  })
  const blockedDm = await timedFetch(`${apiUrl}/v1/data/messages/chats/${aliceBobChat}/messages`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${bob.token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ content: 'blocked-attempt', contentType: 'text' }),
  })
  const unblockRes = await timedFetch(`${apiUrl}/v1/data/profiles/${bob.id}/block`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${alice.token}` },
  })
  const unblockedDm = await timedFetch(`${apiUrl}/v1/data/messages/chats/${aliceBobChat}/messages`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${bob.token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ content: 'after-unblock-ok', contentType: 'text' }),
  })
  invariants.push({
    id: 'INV-03',
    name: 'Blocked user cannot DM blocker; unblock restores access',
    passed: blockRes.ok && blockedDm.status === 403 && unblockRes.ok && unblockedDm.status === 201,
    observed: { blockStatus: blockRes.status, blockedDmStatus: blockedDm.status, unblockStatus: unblockRes.status, unblockedDmStatus: unblockedDm.status },
  })

  // INV-04: Deleted post disappears immediately from feed and direct lookup (zero stale cache resurrection)
  const createdPost = await timedFetch(`${apiUrl}/v1/data/feed/posts`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${alice.token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ content: 'ephemeral-delete-check-post', visibility: 'public' }),
  })
  const delPostId = createdPost.json?.post?.id
  const delPostRes = await timedFetch(`${apiUrl}/v1/data/feed/posts/${delPostId}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${alice.token}` },
  })
  const afterDelDirect = await timedFetch(`${apiUrl}/v1/data/feed/posts/${delPostId}`, {
    headers: { Authorization: `Bearer ${bob.token}` },
  })
  const afterDelFeed = await timedFetch(`${apiUrl}/v1/data/feed?limit=50`, {
    headers: { Authorization: `Bearer ${bob.token}` },
  })
  const stillInFeed = (afterDelFeed.json?.items || []).some((p) => p.id === delPostId)
  invariants.push({
    id: 'INV-04',
    name: 'Deleted post immediately absent from feed and direct read (no cache resurrection)',
    passed: Boolean(delPostId) && delPostRes.ok && afterDelDirect.status === 404 && !stillInFeed,
    observed: { createStatus: createdPost.status, deleteStatus: delPostRes.status, directReadStatus: afterDelDirect.status, stillInFeed },
  })

  // INV-05: Private group & password-protected private voice room deny unauthorized access
  const privGroupId = crypto.randomUUID()
  await dbPool.query(
    `INSERT INTO fallback_collection_rooms(id, room_kind, name, creator_id, is_public, created_at, updated_at)
     VALUES ($1::uuid, 'group', 'Private Secret Group', $2, FALSE, NOW(), NOW())`,
    [privGroupId, alice.id],
  )
  await dbPool.query(
    `INSERT INTO fallback_collection_members(room_kind, room_id, user_id, role, joined_at)
     VALUES ('group', $1, $2, 'admin', NOW())`,
    [privGroupId, alice.id],
  )
  const privGroupPost = await timedFetch(`${apiUrl}/v1/data/messages/groups/${privGroupId}/messages`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${charlie.token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ content: 'intruder-msg', contentType: 'text' }),
  })
  const privVoice = await timedFetch(`${apiUrl}/v1/data/voice/rooms`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${alice.token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: 'Locked Voice Room', isPublic: false, password: 'SecretRoomPass!2026' }),
  })
  const privVoiceId = privVoice.json?.room?.id || privVoice.json?.id
  const wrongPassJoin = await timedFetch(`${apiUrl}/v1/data/voice/rooms/${privVoiceId}/join`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${charlie.token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ password: 'WrongPassword123' }),
  })
  invariants.push({
    id: 'INV-05',
    name: 'Private group & locked voice room deny unauthorized users',
    passed: (privGroupPost.status === 403 || privGroupPost.status === 404) && Boolean(privVoiceId) && (wrongPassJoin.status === 403 || wrongPassJoin.status === 401),
    observed: { privGroupPostStatus: privGroupPost.status, privVoiceCreateStatus: privVoice.status, wrongPassJoinStatus: wrongPassJoin.status },
  })

  // INV-06: Multi-device call accept single-winner race
  const bobLogin2 = await timedFetch(`${apiUrl}/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: bob.email, password: bob.password }),
  })
  const bobToken2 = bobLogin2.json?.accessToken || ''
  const bobRefresh2 = bobLogin2.json?.refreshToken || ''
  const callId = crypto.randomUUID()
  await timedFetch(`${apiUrl}/v1/data/calls/prepare`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${alice.token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ callId, contextType: 'dm', contextId: aliceBobChat, inviteeIds: [bob.id] }),
  })
  const [acc1, acc2] = await Promise.all([
    timedFetch(`${apiUrl}/v1/data/calls/${callId}/accept`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${bob.token}`, 'Content-Type': 'application/json' },
      body: '{}',
    }),
    timedFetch(`${apiUrl}/v1/data/calls/${callId}/accept`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${bobToken2}`, 'Content-Type': 'application/json' },
      body: '{}',
    }),
  ])
  const callStatuses = [acc1.status, acc2.status].sort((a, b) => a - b)
  const callConflict = acc1.status === 409 ? acc1.json : acc2.json
  await timedFetch(`${apiUrl}/v1/data/calls/${callId}/leave`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${alice.token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ action: 'end' }),
  })
  invariants.push({
    id: 'INV-06',
    name: 'Multi-device simultaneous call accept yields atomic 200 + 409 CALL_ANSWERED_ELSEWHERE',
    passed: callStatuses[0] === 200 && callStatuses[1] === 409 && callConflict?.error === 'CALL_ANSWERED_ELSEWHERE',
    observed: { statuses: callStatuses, conflictError: callConflict?.error },
  })

  // INV-07: Instant session revocation (0ms staleness)
  const preLogout = await timedFetch(`${apiUrl}/v1/data/feed?limit=1`, { headers: { Authorization: `Bearer ${bobToken2}` } })
  const doLogout = await timedFetch(`${apiUrl}/v1/auth/logout`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${bobToken2}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken: bobRefresh2 }),
  })
  const postLogout = await timedFetch(`${apiUrl}/v1/data/feed?limit=1`, { headers: { Authorization: `Bearer ${bobToken2}` } })
  invariants.push({
    id: 'INV-07',
    name: 'Session logout immediately invalidates access token (0ms staleness)',
    passed: preLogout.status === 200 && doLogout.status === 200 && postLogout.status === 401 && postLogout.json?.error === 'WEB_SESSION_REVOKED',
    observed: { pre: preLogout.status, logout: doLogout.status, post: postLogout.status, error: postLogout.json?.error },
  })

  // INV-08: Mass Assignment Immunity on PATCH /v1/data/profile/me
  const massPatch = await timedFetch(`${apiUrl}/v1/data/profile/me`, {
    method: 'PATCH',
    headers: { Authorization: `Bearer ${alice.token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      bio: 'Bio legítima de certificación',
      is_verified: true,
      verified: true,
      is_admin: true,
      role: 'admin',
      followers_count: 999999,
      badges: [{ id: 'forged-admin', label: 'Staff' }],
    }),
  })
  const readProfile = await timedFetch(`${apiUrl}/v1/data/profiles/${alice.id}`, {
    headers: { Authorization: `Bearer ${bob.token}` },
  })
  const prof = readProfile.json?.profile || {}
  invariants.push({
    id: 'INV-08',
    name: 'Mass assignment cannot elevate is_verified, is_admin, badges, or followers_count',
    passed:
      massPatch.ok &&
      prof.bio === 'Bio legítima de certificación' &&
      prof.is_verified === false &&
      prof.is_admin === false &&
      Number(prof.followers_count || 0) === 0 &&
      (!Array.isArray(prof.badges) || prof.badges.length === 0),
    observed: {
      patchStatus: massPatch.status,
      is_verified: prof.is_verified,
      is_admin: prof.is_admin,
      followers_count: prof.followers_count,
      badgesCount: Array.isArray(prof.badges) ? prof.badges.length : 0,
    },
  })

  // INV-09: Unauthenticated / Guest Isolation on private endpoints
  const unauthPaths = ['/v1/data/feed', `/v1/data/messages/chats/${aliceBobChat}`, '/v1/data/notifications', '/v1/data/profile/me', '/v1/system/health']
  const unauthResults = await Promise.all(unauthPaths.map((p) => timedFetch(`${apiUrl}${p}`)))
  invariants.push({
    id: 'INV-09',
    name: 'Unauthenticated / guest requests blocked on all private endpoints',
    passed: unauthResults.every((r) => r.status === 401 || r.status === 403),
    observed: Object.fromEntries(unauthPaths.map((p, i) => [p, unauthResults[i].status])),
  })

  // INV-10: Concurrent post reaction toggle race (10 parallel toggles -> 0 5xx, 0 duplicate rows)
  const racePost = await timedFetch(`${apiUrl}/v1/data/feed/posts`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${alice.token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ content: 'reaction-concurrency-post', visibility: 'public' }),
  })
  const racePostId = racePost.json?.post?.id
  const reactCalls = await Promise.all(
    Array.from({ length: 10 }, () =>
      timedFetch(`${apiUrl}/v1/data/feed/posts/${racePostId}/reaction`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${bob.token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ reaction: 'like' }),
      }),
    ),
  )
  const { rows: reactCountRows } = await dbPool.query(
    'SELECT COUNT(*)::int AS c FROM fallback_post_reactions WHERE lower(post_id) = $1 AND lower(user_id) = $2',
    [String(racePostId).toLowerCase(), bob.id.toLowerCase()],
  )
  invariants.push({
    id: 'INV-10',
    name: 'Concurrent 10x post reaction race produces 0 5xx and <=1 persisted row',
    passed: reactCalls.every((r) => r.status < 500) && reactCountRows[0].c <= 1,
    observed: { statuses: reactCalls.map((r) => r.status), persistedRows: reactCountRows[0].c },
  })

  // INV-11: Instant Account Suspension Enforcement (0ms lag in consolidated auth query)
  const preSuspend = await timedFetch(`${apiUrl}/v1/data/feed?limit=1`, { headers: { Authorization: `Bearer ${dave.token}` } })
  await dbPool.query(
    `INSERT INTO fallback_profile_moderation(user_id, is_suspended, suspension_reason, updated_at)
     VALUES ($1, TRUE, 'Certificación suspensión instantánea', NOW())
     ON CONFLICT (user_id) DO UPDATE SET is_suspended = TRUE, suspension_reason = EXCLUDED.suspension_reason, updated_at = NOW()`,
    [dave.id],
  )
  const postSuspend = await timedFetch(`${apiUrl}/v1/data/feed?limit=1`, { headers: { Authorization: `Bearer ${dave.token}` } })
  await dbPool.query('UPDATE fallback_profile_moderation SET is_suspended = FALSE WHERE user_id = $1', [dave.id])
  invariants.push({
    id: 'INV-11',
    name: 'Account suspension blocks authenticated requests instantaneously (0ms lag)',
    passed: preSuspend.status === 200 && postSuspend.status === 403 && postSuspend.json?.error === 'ACCOUNT_SUSPENDED',
    observed: { pre: preSuspend.status, post: postSuspend.status, error: postSuspend.json?.error },
  })

  // INV-12: W3C traceparent + x-correlation-id round-trip preservation
  const traceId = '4bf92f3577b34da6a3ce929d0e0e4736'
  const parentSpan = '00f067aa0ba902b7'
  const traceRes = await timedFetch(`${apiUrl}/health`, {
    headers: {
      'x-correlation-id': 'cert-corr-trace-219',
      traceparent: `00-${traceId}-${parentSpan}-01`,
    },
  })
  const echoedTrace = traceRes.headers?.get('traceparent') || ''
  const traceParts = echoedTrace.split('-')
  invariants.push({
    id: 'INV-12',
    name: 'W3C traceparent preserves traceId with fresh child spanId & echoes x-correlation-id',
    passed:
      traceRes.headers?.get('x-correlation-id') === 'cert-corr-trace-219' &&
      traceParts.length === 4 &&
      traceParts[1] === traceId &&
      traceParts[2] !== parentSpan,
    observed: { correlationId: traceRes.headers?.get('x-correlation-id'), echoedTraceparent: echoedTrace },
  })

  // INV-13: Upload Smuggling & XSS Rejection (.svg, .html, spoofed .exe)
  const badUploads = []
  for (const [fname, mime, payload] of [
    ['xss.svg', 'image/svg+xml', '<svg onload="alert(1)"></svg>'],
    ['page.html', 'text/html', '<script>alert(1)</script>'],
    ['fake.png', 'image/png', 'MZ\x90\x00not-a-real-png'],
  ]) {
    const fd = new FormData()
    fd.append('bucket', 'post-media')
    fd.append('file', new Blob([payload], { type: mime }), fname)
    const up = await timedFetch(`${apiUrl}/v1/storage/upload`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${alice.token}` },
      body: fd,
    })
    badUploads.push({ fname, status: up.status, error: up.json?.error || null })
  }
  invariants.push({
    id: 'INV-13',
    name: 'Upload security rejects SVG XSS, HTML script, and spoofed PE/PNG magic bytes',
    passed: badUploads.every((u) => u.status >= 400 && u.status < 500),
    observed: badUploads,
  })

  // INV-14: Internal metrics protected (401 unauth, 200 auth with Prometheus novvin_api_load_shed_total)
  const unauthMetrics = await timedFetch(`${apiUrl}/internal/metrics`)
  const authMetrics = await timedFetch(`${apiUrl}/internal/metrics`, {
    headers: { Authorization: `Bearer ${internalSecret}` },
  })
  invariants.push({
    id: 'INV-14',
    name: 'Internal Prometheus metrics require secret and expose load-shedding counter',
    passed: unauthMetrics.status === 401 && authMetrics.status === 200 && authMetrics.text.includes('novvin_api_load_shed_total'),
    observed: { unauthStatus: unauthMetrics.status, authStatus: authMetrics.status },
  })

  // Automated Fuzzing Campaign (42 Adversarial Vectors -> ZERO HTTP 500 ALLOWED!)
  const deepObj = (() => {
    let cur = { leaf: 'x' }
    for (let i = 0; i < 60; i++) cur = { nested: cur }
    return cur
  })()
  const authHdr = { Authorization: `Bearer ${alice.token}`, 'Content-Type': 'application/json' }
  const fuzzVectors = [
    ['malformed_json_post', `${apiUrl}/v1/data/feed/posts`, { method: 'POST', headers: authHdr, body: '{"content": [broken' }],
    ['empty_body_post', `${apiUrl}/v1/data/feed/posts`, { method: 'POST', headers: authHdr, body: '' }],
    ['null_json_post', `${apiUrl}/v1/data/feed/posts`, { method: 'POST', headers: authHdr, body: 'null' }],
    ['array_body_post', `${apiUrl}/v1/data/feed/posts`, { method: 'POST', headers: authHdr, body: '[1,2,3]' }],
    ['proto_pollution_profile', `${apiUrl}/v1/data/profile/me`, { method: 'PATCH', headers: authHdr, body: '{"__proto__":{"isAdmin":true},"constructor":{"prototype":{"pwn":1}},"bio":"ok"}' }],
    ['deep_nested_profile', `${apiUrl}/v1/data/profile/me`, { method: 'PATCH', headers: authHdr, body: JSON.stringify({ bio: deepObj, social_links: deepObj }) }],
    ['null_byte_dm', `${apiUrl}/v1/data/messages/chats/${aliceBobChat}/messages`, { method: 'POST', headers: authHdr, body: JSON.stringify({ content: 'hello\u0000world', contentType: 'text' }) }],
    ['unicode_rtl_zalgo_dm', `${apiUrl}/v1/data/messages/chats/${aliceBobChat}/messages`, { method: 'POST', headers: authHdr, body: JSON.stringify({ content: '\u202E\u2066🔥🔥🔥\u0301\u0302test', contentType: 'text' }) }],
    ['wrong_types_dm', `${apiUrl}/v1/data/messages/chats/${aliceBobChat}/messages`, { method: 'POST', headers: authHdr, body: JSON.stringify({ content: ['arr'], contentType: { obj: 1 }, mediaSize: 'NaN', clientMessageId: 12345 }) }],
    ['oversized_string_dm', `${apiUrl}/v1/data/messages/chats/${aliceBobChat}/messages`, { method: 'POST', headers: authHdr, body: JSON.stringify({ content: 'X'.repeat(150_000), contentType: 'text' }) }],
    ['oversized_payload_2mb', `${apiUrl}/v1/data/feed/posts`, { method: 'POST', headers: authHdr, body: JSON.stringify({ content: 'A'.repeat(1_300_000) }) }],
    ['sqli_chat_id', `${apiUrl}/v1/data/messages/chats/${encodeURIComponent("1' OR '1'='1; DROP TABLE fallback_users;--")}/messages`, { method: 'POST', headers: authHdr, body: JSON.stringify({ content: 'x' }) }],
    ['sqli_profile_id', `${apiUrl}/v1/data/profiles/${encodeURIComponent("' UNION SELECT password_hash FROM fallback_users--")}`, { headers: authHdr }],
    ['sqli_post_id', `${apiUrl}/v1/data/feed/posts/${encodeURIComponent("00000000-0000-0000-0000-000000000000' OR 1=1--")}`, { headers: authHdr }],
    ['hpp_feed_limits', `${apiUrl}/v1/data/feed?limit=10&limit=-99999&offset=NaN&offset=Infinity&mode[]=discover&mode[]=invalid`, { headers: authHdr }],
    ['hpp_notifications', `${apiUrl}/v1/data/notifications?limit=-5&limit=999999999&unreadOnly=true&unreadOnly=false`, { headers: authHdr }],
    ['traversal_storage_1', `${apiUrl}/storage/v1/object/public/post-media/..%2F..%2F..%2Fetc%2Fpasswd`, { headers: authHdr }],
    ['traversal_storage_2', `${apiUrl}/storage/v1/object/public/post-media/%252e%252e%252fserver.js`, { headers: authHdr }],
    ['traversal_storage_null', `${apiUrl}/storage/v1/object/public/post-media/test.png%00.exe`, { headers: authHdr }],
    ['invalid_uuid_call_accept', `${apiUrl}/v1/data/calls/${encodeURIComponent('not-a-uuid; SELECT 1')}/accept`, { method: 'POST', headers: authHdr, body: '{}' }],
    ['call_prepare_wrong_types', `${apiUrl}/v1/data/calls/prepare`, { method: 'POST', headers: authHdr, body: JSON.stringify({ callId: {}, contextType: 123, contextId: [], inviteeIds: 'not-array' }) }],
    ['voice_create_huge_participants', `${apiUrl}/v1/data/voice/rooms`, { method: 'POST', headers: authHdr, body: JSON.stringify({ name: 'x'.repeat(2000), maxParticipants: 999999999, isPublic: 'yes' }) }],
    ['voice_join_sqli', `${apiUrl}/v1/data/voice/rooms/${encodeURIComponent("' OR 1=1--")}/join`, { method: 'POST', headers: authHdr, body: JSON.stringify({ password: null }) }],
    ['register_wrong_types', `${apiUrl}/v1/auth/register`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email: ['a@b.c'], password: { $ne: '' }, displayName: 42 }) }],
    ['login_nosql_object', `${apiUrl}/v1/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email: { $gt: '' }, password: { $gt: '' } }) }],
    ['login_huge_password', `${apiUrl}/v1/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email: alice.email, password: 'P'.repeat(10_000) }) }],
    ['invalid_bearer_jwt_garbage', `${apiUrl}/v1/data/feed`, { headers: { Authorization: 'Bearer not.a.real.jwt.token' } }],
    ['alg_none_jwt_forgery', `${apiUrl}/v1/data/feed`, { headers: { Authorization: `Bearer ${Buffer.from('{"alg":"none","typ":"JWT"}').toString('base64url')}.${Buffer.from(JSON.stringify({ sub: alice.id, sid: crypto.randomUUID(), iss: 'conectate-local-fallback', aud: 'conectate-clients' })).toString('base64url')}.` } }],
    ['notif_forged_system_type', `${apiUrl}/v1/data/notifications`, { method: 'POST', headers: authHdr, body: JSON.stringify({ recipientId: bob.id, type: 'message_deleted', targetId: aliceBobChat }) }],
    ['notif_wrong_types', `${apiUrl}/v1/data/notifications`, { method: 'POST', headers: authHdr, body: JSON.stringify({ recipientId: { bad: 1 }, type: ['a'], extraData: 'not-object' }) }],
    ['post_media_foreign_bucket', `${apiUrl}/v1/data/feed/posts`, { method: 'POST', headers: authHdr, body: JSON.stringify({ content: 'x', mediaUrl: 'https://evil.invalid/storage/v1/object/public/chat-media/stolen.png' }) }],
    ['post_media_15_items', `${apiUrl}/v1/data/feed/posts`, { method: 'POST', headers: authHdr, body: JSON.stringify({ content: 'x', mediaUrls: Array.from({ length: 15 }, (_, i) => `https://x.test/${i}.jpg`) }) }],
    ['group_send_reserved_system', `${apiUrl}/v1/data/messages/groups/${crypto.randomUUID()}/messages`, { method: 'POST', headers: authHdr, body: JSON.stringify({ content: 'spoof', contentType: 'system_membership' }) }],
    ['group_send_raffle_bypass', `${apiUrl}/v1/data/messages/groups/${crypto.randomUUID()}/messages`, { method: 'POST', headers: authHdr, body: JSON.stringify({ content: 'spoof', contentType: 'raffle' }) }],
    ['search_special_regex_chars', `${apiUrl}/v1/data/search?q=${encodeURIComponent('.*+?^${}()|[]\\%%%___')}`, { headers: authHdr }],
    ['feed_ambiguous_filter', `${apiUrl}/v1/data/feed?userId=${alice.id}&sharedBy=${bob.id}`, { headers: authHdr }],
    ['reaction_invalid_emoji_obj', `${apiUrl}/v1/data/feed/posts/${racePostId}/reaction`, { method: 'POST', headers: authHdr, body: JSON.stringify({ reaction: { evil: true } }) }],
    ['profile_null_username', `${apiUrl}/v1/data/profile/me`, { method: 'PATCH', headers: authHdr, body: JSON.stringify({ username: null }) }],
    ['profile_empty_patch', `${apiUrl}/v1/data/profile/me`, { method: 'PATCH', headers: authHdr, body: JSON.stringify({ unknownFieldOnly: 'ignored' }) }],
    ['dm_edit_empty_content', `${apiUrl}/v1/data/messages/chats/${aliceBobChat}/messages/${idemId}`, { method: 'PATCH', headers: authHdr, body: JSON.stringify({ content: '   ' }) }],
    ['comment_missing_post', `${apiUrl}/v1/data/feed/posts/${crypto.randomUUID()}/comments`, { method: 'POST', headers: authHdr, body: JSON.stringify({ content: 'orphan comment' }) }],
    ['bad_traceparent_header', `${apiUrl}/health`, { headers: { traceparent: '00-00000000000000000000000000000000-0000000000000000-01', 'x-correlation-id': '<script>alert(1)</script>' } }],
  ]

  const fuzzResults = []
  for (const [label, url, opts] of fuzzVectors) {
    const res = await timedFetch(url, opts)
    fuzzResults.push({
      vector: label,
      status: res.status,
      crashed5xx: res.status >= 500 || res.status === 0,
      ...(res.status >= 500 || res.status === 0 ? { body: res.text, error: res.error } : {}),
    })
  }
  const fuzz5xx = fuzzResults.filter((r) => r.crashed5xx)

  return {
    invariants,
    allInvariantsPassed: invariants.every((i) => i.passed),
    fuzzTotalVectors: fuzzResults.length,
    fuzzCrashes5xx: fuzz5xx.length,
    fuzzFailures: fuzz5xx,
    fuzzStatusSummary: fuzzResults.reduce((acc, r) => { acc[r.status] = (acc[r.status] || 0) + 1; return acc }, {}),
  }
}

async function main() {
  const soakSeconds = Math.max(15, Math.min(Number(process.env.NOVVIN_CERT_SOAK_SECONDS || 60), 300))
  const pgBin = resolvePgBin()
  const ext = process.platform === 'win32' ? '.exe' : ''
  const workDir = fs.mkdtempSync(path.join(os.tmpdir(), 'novvin-cert-'))
  const pgData = path.join(workDir, 'pgdata')
  const storageDir = path.join(workDir, 'storage')
  fs.mkdirSync(storageDir, { recursive: true })
  const logStream = fs.createWriteStream(path.join(workDir, 'services.log'))

  const pgPort = await freePort()
  const apiPort = await freePort()
  const realtimePort = await freePort()
  const signalingPort = await freePort()
  const jwtSecret = crypto.randomBytes(32).toString('hex')
  const internalSecret = crypto.randomBytes(32).toString('hex')
  const adminToken = crypto.randomBytes(32).toString('hex')
  const pgPass = 'novvin_cert_local_pw'
  const pwFile = path.join(workDir, 'pgpass.txt')
  fs.writeFileSync(pwFile, pgPass, 'utf8')

  let pgStarted = false
  let realtimeChild = null
  let apiChild = null
  let signalingChild = null
  let dbPool = null

  try {
    console.error(`[cert] Booting ephemeral PostgreSQL 17 on 127.0.0.1:${pgPort}...`)
    const initRes = spawnSync(
      path.join(pgBin, `initdb${ext}`),
      ['-D', pgData, '-U', 'postgres', '--auth=scram-sha-256', `--pwfile=${pwFile}`, '-E', 'UTF8', '--no-locale'],
      { encoding: 'utf8' },
    )
    if (initRes.status !== 0) throw new Error(`initdb failed: ${initRes.stderr || initRes.stdout}`)
    fs.appendFileSync(
      path.join(pgData, 'postgresql.conf'),
      `\nlisten_addresses = '127.0.0.1'\nport = ${pgPort}\nmax_connections = 150\nshared_buffers = 128MB\n`,
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

    const apiUrl = `http://127.0.0.1:${apiPort}`
    const realtimeUrl = `http://127.0.0.1:${realtimePort}`
    const signalingUrl = `http://127.0.0.1:${signalingPort}`

    const realtimeEnv = {
      NODE_ENV: 'test',
      PORT: String(realtimePort),
      REALTIME_LISTEN_HOST: '127.0.0.1',
      JWT_SECRET: jwtSecret,
      JWT_ISSUER: 'conectate-local-fallback',
      LOCAL_FALLBACK_API_URL: apiUrl,
      REALTIME_INTERNAL_SECRET: internalSecret,
      REALTIME_MAX_CONNECTIONS: '5000',
      REALTIME_MAX_CONNECTIONS_PER_IP: '3000',
    }
    const startRealtime = () => {
      realtimeChild = spawnNodeService(
        'realtime',
        path.join(repoRoot, 'infrastructure/local-fallback/realtime-gateway/src/server.js'),
        path.join(repoRoot, 'infrastructure/local-fallback/realtime-gateway'),
        realtimeEnv,
        logStream,
      )
    }
    startRealtime()
    await waitForHttpOk(`${realtimeUrl}/health`, 15_000)

    const apiEnv = {
      NODE_ENV: 'test',
      PORT: String(apiPort),
      DATABASE_URL: databaseUrl,
      DATABASE_POOL_MAX: '25',
      DB_POOL_LOAD_SHED_WAITING: '150',
      JWT_SECRET: jwtSecret,
      STORAGE_ROOT: storageDir,
      STORAGE_MIN_FREE_BYTES: '0',
      STORAGE_SIGNING_SECRET: crypto.randomBytes(32).toString('hex'),
      REALTIME_INTERNAL_URL: realtimeUrl,
      REALTIME_INTERNAL_SECRET: internalSecret,
      SIGNALING_INTERNAL_URL: signalingUrl,
      ADMIN_SYNC_TOKEN: adminToken,
      CORS_ORIGIN: 'http://127.0.0.1',
      AUTH_REGISTER_RATE_LIMIT_PER_HOUR: '100',
      MESSAGE_WRITE_RATE_LIMIT_PER_MINUTE: '5000',
      SOCIAL_WRITE_RATE_LIMIT_PER_MINUTE: '5000',
      CALL_INITIATE_RATE_LIMIT_PER_MINUTE: '30',
      PASSWORD_RESET_DELIVERY: 'disabled',
    }
    apiChild = spawnNodeService(
      'api',
      path.join(repoRoot, 'infrastructure/local-fallback/api/src/server.js'),
      path.join(repoRoot, 'infrastructure/local-fallback/api'),
      apiEnv,
      logStream,
    )
    await waitForHttpOk(`${apiUrl}/ready`, 30_000)

    const signalingEnv = {
      NODE_ENV: 'test',
      PORT: String(signalingPort),
      LOCAL_FALLBACK_API_URL: apiUrl,
      SIGNALING_ALLOWED_ORIGINS: 'http://127.0.0.1',
      SIGNALING_REDIS_REQUIRED: 'false',
      LIVE_INTERNAL_EVENTS_TOKEN: internalSecret,
    }
    const startSignaling = () => {
      signalingChild = spawnNodeService(
        'signaling',
        path.join(repoRoot, 'signaling-server/index.js'),
        path.join(repoRoot, 'signaling-server'),
        signalingEnv,
        logStream,
      )
    }
    startSignaling()
    await waitForHttpOk(`${signalingUrl}/health`, 15_000)

    console.error('[cert] Seeding 24 synthetic accounts, 10 DM chats, 2 groups, 10 posts...')
    const users = []
    for (let i = 0; i < 24; i++) {
      const email = `cert_user_${i}_${Date.now()}@novvin.test`
      const password = `CertPass!2026_${i}`
      const displayName = `Cert User ${i}`
      const reg = await timedFetch(`${apiUrl}/v1/auth/register`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password, displayName, username: `certu${i}_${String(Date.now()).slice(-4)}` }),
      })
      if (!reg.ok) throw new Error(`Register ${i} failed: ${reg.status} ${reg.text}`)
      users.push({ id: reg.json.user.id, email, password, displayName, token: reg.json.accessToken })
    }
    const chatIds = []
    for (let i = 0; i < 10; i++) {
      const created = await timedFetch(`${apiUrl}/v1/data/messages/chats`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${users[i * 2].token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ participantId: users[i * 2 + 1].id }),
      })
      chatIds.push(created.json?.chat?.id || created.json?.id)
    }
    const groupIds = []
    for (let g = 0; g < 2; g++) {
      const gid = crypto.randomUUID()
      await dbPool.query(
        `INSERT INTO fallback_collection_rooms(id, room_kind, name, creator_id, is_public, created_at, updated_at)
         VALUES ($1::uuid, 'group', $2, $3, TRUE, NOW(), NOW())`,
        [gid, `Cert Group ${g}`, users[g].id],
      )
      for (const m of users) {
        await dbPool.query(
          `INSERT INTO fallback_collection_members(room_kind, room_id, user_id, role, joined_at)
           VALUES ('group', $1, $2, $3, NOW()) ON CONFLICT DO NOTHING`,
          [gid, m.id, m.id === users[g].id ? 'admin' : 'member'],
        )
      }
      groupIds.push(gid)
    }
    for (let p = 0; p < 10; p++) {
      await timedFetch(`${apiUrl}/v1/data/feed/posts`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${users[p % users.length].token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ content: `Cert post #${p}`, visibility: 'public' }),
      })
    }

    // PART A: Breakpoint Ramp (10 -> 25 -> 50 -> 100 -> 250 -> 500 -> 1,000) + Post-Breakpoint Recovery
    console.error('[cert] PART A: Breakpoint Ramp (10 -> 25 -> 50 -> 100 -> 250 -> 500 -> 1,000)...')
    const breakpointRamp = []
    for (const stage of [
      { concurrency: 10, requestsPerUser: 10 },
      { concurrency: 25, requestsPerUser: 8 },
      { concurrency: 50, requestsPerUser: 6 },
      { concurrency: 100, requestsPerUser: 5 },
      { concurrency: 250, requestsPerUser: 4 },
      { concurrency: 500, requestsPerUser: 3 },
      { concurrency: 1000, requestsPerUser: 2 },
    ]) {
      const wave = await runWave({
        name: `bp_${stage.concurrency}`,
        concurrency: stage.concurrency,
        requestsPerUser: stage.requestsPerUser,
        users,
        apiUrl,
        chatIds,
        groupIds,
      })
      wave.apiMetrics = await fetchJsonMetrics(apiUrl, internalSecret)
      console.error(`[cert]   c=${stage.concurrency} rps=${wave.rps} p50=${wave.overall.p50Ms}ms p95=${wave.overall.p95Ms}ms 2xx=${wave.status2xx} shed503=${wave.loadShed503} unexp5xx=${wave.unexpected5xx} netErr=${wave.networkErrors}`)
      breakpointRamp.push(wave)
      if (wave.unexpected5xx > wave.totalRequests * 0.10 || wave.networkErrors > wave.totalRequests * 0.10) break
    }
    const postBpStart = performance.now()
    const postBreakpointRecovery = await runWave({
      name: 'bp_recovery_20',
      concurrency: 20,
      requestsPerUser: 5,
      users,
      apiUrl,
      chatIds,
      groupIds,
    })
    postBreakpointRecovery.recoveryWindowMs = Number((performance.now() - postBpStart).toFixed(1))
    postBreakpointRecovery.apiMetrics = await fetchJsonMetrics(apiUrl, internalSecret)

    // PART B: Extended Soak Test (60s @ 30 concurrent users)
    console.error(`[cert] PART B: Extended Soak (${soakSeconds}s @ 30 concurrent users)...`)
    const soakSamples = []
    const soakEnd = Date.now() + soakSeconds * 1000
    let iter = 0
    while (Date.now() < soakEnd) {
      iter += 1
      const w = await runWave({
        name: `soak_${iter}`,
        concurrency: 30,
        requestsPerUser: 3,
        users,
        apiUrl,
        chatIds,
        groupIds,
      })
      const apiM = await fetchJsonMetrics(apiUrl, internalSecret)
      const rtM = await fetchJsonMetrics(realtimeUrl, internalSecret)
      soakSamples.push({
        iteration: iter,
        rps: w.rps,
        p95Ms: w.overall.p95Ms,
        loadShed503: w.loadShed503,
        unexpected5xx: w.unexpected5xx,
        apiRssMB: apiM ? Number((apiM.memoryRssBytes / 1048576).toFixed(1)) : null,
        apiHeapMB: apiM ? Number((apiM.memoryHeapUsedBytes / 1048576).toFixed(1)) : null,
        dbPool: apiM?.dbPool || null,
        loopLagP95Ms: apiM?.eventLoopLagP95Ms ?? null,
        rtRssMB: rtM ? Number((rtM.memoryRssBytes / 1048576).toFixed(1)) : null,
      })
      await new Promise((r) => setTimeout(r, 1_500))
    }

    // PART C: Controlled Chaos Experiments (One by One)
    console.error('[cert] PART C: Chaos Experiments (#1 Realtime SIGKILL, #2 Postgres Stall, #3 Signaling Storm)...')
    // Chaos #1: Realtime Gateway SIGKILL
    await stopChild(realtimeChild, 'SIGKILL')
    const dmDuringRtOutage = await timedFetch(`${apiUrl}/v1/data/messages/chats/${chatIds[0]}/messages`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${users[0].token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: 'chaos1-rt-down-dm', contentType: 'text', clientMessageId: crypto.randomUUID() }),
    })
    const grpDuringRtOutage = await timedFetch(`${apiUrl}/v1/data/messages/groups/${groupIds[0]}/messages`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${users[0].token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: 'chaos1-rt-down-grp', contentType: 'text', clientMessageId: crypto.randomUUID() }),
    })
    const rtRestartStart = performance.now()
    startRealtime()
    await waitForHttpOk(`${realtimeUrl}/health`, 15_000)
    const rtRestartMs = Number((performance.now() - rtRestartStart).toFixed(1))
    const dmAfterRtRecover = await timedFetch(`${apiUrl}/v1/data/messages/chats/${chatIds[0]}/messages`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${users[0].token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: 'chaos1-rt-up-dm', contentType: 'text', clientMessageId: crypto.randomUUID() }),
    })

    // Chaos #2: PostgreSQL Stall / 12 Concurrent pg_sleep(2.5) Queries
    const stallClients = Array.from({ length: 12 }, () => dbPool.query('SELECT pg_sleep(2.5)'))
    await new Promise((r) => setTimeout(r, 250))
    const duringPgStall = await runWave({
      name: 'chaos2_during_pg_stall',
      concurrency: 20,
      requestsPerUser: 2,
      users,
      apiUrl,
      chatIds,
      groupIds,
    })
    await Promise.allSettled(stallClients)
    const afterPgStall = await runWave({
      name: 'chaos2_after_pg_stall',
      concurrency: 20,
      requestsPerUser: 2,
      users,
      apiUrl,
      chatIds,
      groupIds,
    })

    // Chaos #3: Real Socket.IO Signaling 40-Client Voice Room + Reaction Flood + SIGKILL + Reconnect Storm
    const signalingChaos = await runSignalingChaosAndStress({
      signalingUrl,
      internalSecret,
      apiUrl,
      users,
      spawnSignaling: async () => startSignaling(),
      stopSignaling: async (sig) => stopChild(signalingChild, sig),
    })

    // PART D: Real PostgreSQL 17 Backup & Restore Integrity Drill
    console.error('[cert] PART D: PostgreSQL 17 pg_dump -> pg_restore Integrity Drill...')
    const backupRestore = await runBackupRestoreDrill({
      pgBin,
      ext,
      pgPort,
      pgPass,
      workDir,
      primaryUrl: databaseUrl,
    })

    // PART E: 14 Live Business-Rule Invariants + 42-Vector Automated Fuzzer
    console.error('[cert] PART E: 14 Business-Rule Invariants + 42-Vector Automated Fuzzer...')
    const securityAndBusiness = await runBusinessInvariantsAndFuzzer({
      apiUrl,
      internalSecret,
      users,
      chatIds,
      dbPool,
    })

    const report = {
      executedAt: new Date().toISOString(),
      environment: {
        isolatedLoopback: true,
        hostPlatform: `${process.platform}-${process.arch}`,
        nodeVersion: process.version,
        cpuCount: os.cpus().length,
        totalRamGB: Number((os.totalmem() / 1073741824).toFixed(1)),
        databaseEngine: 'PostgreSQL 17 (isolated ephemeral cluster)',
        dbPoolMax: 25,
        dbPoolLoadShedWaiting: 150,
      },
      breakpointRamp,
      postBreakpointRecovery,
      soakTest: { durationSeconds: soakSeconds, sampleCount: soakSamples.length, samples: soakSamples },
      chaosExperiments: {
        exp1_realtimeHardKill: {
          passed: dmDuringRtOutage.status === 201 && grpDuringRtOutage.status === 201 && dmAfterRtRecover.status === 201,
          dmDuringOutageStatus: dmDuringRtOutage.status,
          dmDuringOutageMs: Number(dmDuringRtOutage.ms.toFixed(1)),
          groupDuringOutageStatus: grpDuringRtOutage.status,
          groupDuringOutageMs: Number(grpDuringRtOutage.ms.toFixed(1)),
          realtimeRestartRtoMs: rtRestartMs,
          dmAfterRecoveryStatus: dmAfterRtRecover.status,
          dmAfterRecoveryMs: Number(dmAfterRtRecover.ms.toFixed(1)),
        },
        exp2_postgresStallContention: {
          passed: duringPgStall.unexpected5xx === 0 && afterPgStall.unexpected5xx === 0,
          duringStall: { rps: duringPgStall.rps, p50Ms: duringPgStall.overall.p50Ms, p95Ms: duringPgStall.overall.p95Ms, unexpected5xx: duringPgStall.unexpected5xx },
          afterRecovery: { rps: afterPgStall.rps, p50Ms: afterPgStall.overall.p50Ms, p95Ms: afterPgStall.overall.p95Ms, unexpected5xx: afterPgStall.unexpected5xx },
        },
        exp3_signalingSocketIoStorm: signalingChaos,
      },
      backupRestoreDrill: backupRestore,
      securityAndBusiness,
    }

    const serialized = JSON.stringify(report, null, 2)
    if (process.env.NOVVIN_REPORT_FILE) {
      fs.writeFileSync(process.env.NOVVIN_REPORT_FILE, serialized, 'utf8')
    }
    console.log(serialized)

    const ok =
      backupRestore.passed &&
      signalingChaos.passed &&
      securityAndBusiness.allInvariantsPassed &&
      securityAndBusiness.fuzzCrashes5xx === 0 &&
      breakpointRamp.every((w) => w.unexpected5xx === 0)
    if (!ok) process.exitCode = 1
  } finally {
    if (dbPool) await dbPool.end().catch(() => {})
    await stopChild(signalingChild)
    await stopChild(apiChild)
    await stopChild(realtimeChild)
    if (pgStarted) {
      spawnSync(path.join(pgBin, `pg_ctl${ext}`), ['-D', pgData, '-m', 'fast', 'stop'], { stdio: 'ignore' })
    }
    logStream.end()
    try { fs.rmSync(workDir, { recursive: true, force: true }) } catch {}
  }
}

main().catch((error) => {
  console.error('[cert] FAILED:', error)
  process.exit(1)
})
