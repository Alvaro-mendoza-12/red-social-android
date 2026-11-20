const express = require('express')
const http = require('http')
const helmet = require('helmet')
const jwt = require('jsonwebtoken')
const WebSocket = require('ws')
const crypto = require('crypto')
const { monitorEventLoopDelay } = require('perf_hooks')
const { createRedisRespClient } = require('./redis-resp-client')

const serviceName = 'conectate-local-fallback-realtime-gateway'
const version = '0.1.0'
const instanceId = crypto.randomUUID()
const isProduction = String(process.env.NODE_ENV || '').trim().toLowerCase() === 'production'

const port = Number(process.env.PORT || 3002)
const jwtSecret = String(process.env.JWT_SECRET || '')
const jwtIssuer = String(process.env.JWT_ISSUER || 'conectate-local-fallback')
const localFallbackApiUrl = String(process.env.LOCAL_FALLBACK_API_URL || 'http://api:3001').replace(/\/+$/, '')
const internalSecret = String(process.env.REALTIME_INTERNAL_SECRET || '').trim()
const MAX_TOTAL_CONNECTIONS = Math.max(100, Math.min(Number(process.env.REALTIME_MAX_CONNECTIONS || 10_000) || 10_000, 100_000))
const MAX_CONNECTIONS_PER_IP = Math.max(5, Math.min(Number(process.env.REALTIME_MAX_CONNECTIONS_PER_IP || 200) || 200, 10_000))
const realtimeRedisUrl = String(process.env.REALTIME_REDIS_URL || '').trim()
const realtimeRedisStreamName = String(process.env.REALTIME_REDIS_STREAM_NAME || 'novvin:realtime:events').trim() || 'novvin:realtime:events'
const realtimeRedisStreamMaxLen = Math.max(1_000, Math.min(Number(process.env.REALTIME_REDIS_STREAM_MAXLEN || 20_000) || 20_000, 200_000))
const realtimeRedisBlockMs = Math.max(250, Math.min(Number(process.env.REALTIME_REDIS_BLOCK_MS || 1_500) || 1_500, 10_000))

const eventLoopMonitor = monitorEventLoopDelay({ resolution: 20 })
eventLoopMonitor.enable()

const gatewayMetrics = {
  startedAtMs: Date.now(),
  eventsPublished: 0,
  batchRequests: 0,
  messagesDelivered: 0,
  slowConsumerDrops: 0,
  rateLimitDrops: 0,
  connectionLimitRejections: 0,
  authFailures: 0,
  streamEventsPublished: 0,
  streamEventsReceived: 0,
  streamDuplicatesSkipped: 0,
  streamPublishErrors: 0,
}

if (!jwtSecret || jwtSecret.length < 32) {
  if (isProduction) throw new Error('JWT_SECRET must be at least 32 characters in production')
  console.warn('[realtime] JWT_SECRET missing or too short; gateway will still start but will reject auth')
}
if (isProduction && internalSecret.length < 32) {
  throw new Error('REALTIME_INTERNAL_SECRET must be at least 32 characters in production')
}

const app = express()
app.disable('x-powered-by')
app.use(helmet())
app.use((req, res, next) => {
  const supplied = String(req.headers['x-correlation-id'] || req.headers['x-request-id'] || '').trim()
  const correlationId = /^[a-zA-Z0-9._:-]{8,80}$/.test(supplied) ? supplied : crypto.randomUUID()
  const rawTrace = String(req.headers.traceparent || '').trim()
  const traceMatch = /^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$/i.exec(rawTrace)
  const traceparent = traceMatch && !/^0+$/.test(traceMatch[1]) && !/^0+$/.test(traceMatch[2])
    ? `00-${traceMatch[1].toLowerCase()}-${crypto.randomBytes(8).toString('hex')}-${traceMatch[3].toLowerCase()}`
    : `00-${crypto.randomBytes(16).toString('hex')}-${crypto.randomBytes(8).toString('hex')}-01`
  req.correlationId = correlationId
  req.traceparent = traceparent
  res.set('x-correlation-id', correlationId)
  res.set('x-request-id', correlationId)
  res.set('traceparent', traceparent)
  next()
})
app.get('/', (_req, res) => res.json({ ok: true, service: serviceName, version, instanceId }))
app.get('/health', (_req, res) => res.json({
  ok: true,
  service: serviceName,
  version,
  instanceId,
  redisBusMode: redisBus ? 'redis-streams' : 'local',
  redisConnected: Boolean(redisBus && redisBus.isReady()),
  time: new Date().toISOString(),
}))

const server = http.createServer(app)
const wss = new WebSocket.Server({ server, maxPayload: 32 * 1024, perMessageDeflate: false })
const MAX_SUBSCRIPTIONS = 32
const AUTH_RECHECK_MS = 60_000

// O(1) fan-out indexes avoiding O(N_total_clients) full scans on every broadcast.
const channelSubscribers = new Map()
const userSockets = new Map()
const ipConnections = new Map()

function indexAdd(map, key, ws) {
  if (!key) return
  let set = map.get(key)
  if (!set) {
    set = new Set()
    map.set(key, set)
  }
  set.add(ws)
}

function indexRemove(map, key, ws) {
  if (!key) return
  const set = map.get(key)
  if (!set) return
  set.delete(ws)
  if (set.size === 0) map.delete(key)
}

function normalizeClientIp(req) {
  const peer = String(req.socket?.remoteAddress || '').trim().toLowerCase().replace(/^::ffff:/, '')
  const trustedProxy = peer === '127.0.0.1' || peer === '::1' || peer.startsWith('10.') || peer.startsWith('192.168.') || /^172\.(1[6-9]|2\d|3[01])\./.test(peer)
  if (trustedProxy) {
    const cf = String(req.headers?.['cf-connecting-ip'] || '').trim().toLowerCase().replace(/^::ffff:/, '')
    if (cf) return cf.slice(0, 120)
  }
  return (peer || 'unknown').slice(0, 120)
}

function setSocketUserIds(ws, nextIds) {
  const previous = ws._userIds || new Set()
  for (const oldId of previous) {
    if (!nextIds.has(oldId)) indexRemove(userSockets, oldId, ws)
  }
  for (const nextId of nextIds) {
    indexAdd(userSockets, nextId, ws)
  }
  ws._userIds = nextIds
}

function addSocketChannel(ws, channel) {
  ws._rooms.add(channel)
  indexAdd(channelSubscribers, channel, ws)
}

function removeSocketChannel(ws, channel) {
  ws._rooms.delete(channel)
  indexRemove(channelSubscribers, channel, ws)
}

function clearSocketIndexes(ws) {
  if (ws._rooms) {
    for (const channel of ws._rooms) indexRemove(channelSubscribers, channel, ws)
    ws._rooms.clear()
  }
  if (ws._userIds) {
    for (const uid of ws._userIds) indexRemove(userSockets, uid, ws)
    ws._userIds.clear()
  }
}

/**
 * Auth model:
 * - client sends: Authorization: Bearer <token> during WS handshake as header
 * - token is verified with JWT_SECRET and issuer/audience used by local-fallback/api.
 *
 * We intentionally keep this MVP simple: once authed, we maintain:
 * - user rooms: user:{userId}
 * - chat rooms: chat:{chatId}
 */
function parseBearer(headers) {
  const auth = headers?.authorization || ''
  const match = String(auth).match(/^Bearer\s+(.+)$/i)
  return match?.[1] || ''
}

function verifyToken(token) {
  if (!token) return null
  if (!jwtSecret || jwtSecret.length < 32) return null
  try {
    const payload = jwt.verify(token, jwtSecret, {
      issuer: jwtIssuer,
      audience: 'conectate-clients',
      algorithms: ['HS256'],
    })
    return Number.isFinite(payload.exp) ? payload : null
  } catch {
    return null
  }
}

async function resolveUserIds(token, payload) {
  const ids = new Set([String(payload?.sub || '').trim().toLowerCase()].filter(Boolean))
  try {
    const response = await fetch(`${localFallbackApiUrl}/v1/auth/me`, {
      headers: { Authorization: `Bearer ${token}` },
      signal: AbortSignal.timeout(5_000),
    })
    if (!response.ok) return null

    const body = await response.json()
    const user = body?.user || {}
    const userIds = [user.id, user.supabaseUserId]
    userIds.forEach((value) => {
      const id = String(value || '').trim().toLowerCase()
      if (id) ids.add(id)
    })
    return ids
  } catch (error) {
    console.warn('[realtime] canonical user identity could not be resolved:', error.message)
    return null
  }
}

function subscriptionAuthorizationUrl(channel) {
  const mappings = [
    ['profile-counts:', (id) => `/v1/data/profiles/${encodeURIComponent(id)}`],
    ['profile-music:', (id) => `/v1/data/profiles/${encodeURIComponent(id)}/music/reactions`],
    ['chat:room:group:', (id) => `/v1/data/messages/markers/group/${encodeURIComponent(id)}/access`],
    ['chat:room:community:', (id) => `/v1/data/messages/markers/community/${encodeURIComponent(id)}/access`],
    ['chat:', (id) => `/v1/data/messages/chats/${encodeURIComponent(id)}`],
    ['random-chat:', (id) => `/v1/data/proximity/random/sessions/${encodeURIComponent(id)}`],
    ['typing-chat:', (id) => `/v1/data/messages/chats/${encodeURIComponent(id)}`],
    ['typing-group:', (id) => `/v1/data/messages/groups/${encodeURIComponent(id)}`],
    ['typing-community:', (id) => `/v1/data/messages/communities/${encodeURIComponent(id)}`],
    ['message-markers:chat:', (id) => `/v1/data/messages/markers/chat/${encodeURIComponent(id)}/access`],
    ['message-markers:group:', (id) => `/v1/data/messages/markers/group/${encodeURIComponent(id)}/access`],
    ['message-markers:community:', (id) => `/v1/data/messages/markers/community/${encodeURIComponent(id)}/access`],
    ['raffles:group:', (id) => `/v1/data/raffles/group/${encodeURIComponent(id)}/access`],
    ['raffles:community:', (id) => `/v1/data/raffles/community/${encodeURIComponent(id)}/access`],
    ['post-comments:', (id) => `/v1/data/feed/posts/${encodeURIComponent(id)}`],
    ['video-comments:', (id) => `/v1/data/videos/${encodeURIComponent(id)}`],
    ['live-comments:', (id) => `/v1/data/live/${encodeURIComponent(id)}`],
    ['live-audience:', (id) => `/v1/data/live/${encodeURIComponent(id)}/audience`],
    ['live-collaboration:', (id) => `/v1/data/live/${encodeURIComponent(id)}`],
    ['live-game:', (id) => `/v1/data/live/${encodeURIComponent(id)}/games/draw-guess/access`],
    ['live-moderation:', (id) => `/v1/data/live/${encodeURIComponent(id)}`],
  ]
  for (const [prefix, resolvePath] of mappings) {
    if (channel.startsWith(prefix)) {
      const id = channel.slice(prefix.length).trim()
      return id ? resolvePath(id) : null
    }
  }
  if (channel === 'admin:live') return '/v1/data/admin/access'
  return null
}

async function maySubscribeToChannel(token, allowedUserIds, channel) {
  for (const prefix of ['user:', 'random-chat-user:']) {
    if (channel.startsWith(prefix)) {
      return allowedUserIds.has(channel.slice(prefix.length).trim().toLowerCase())
    }
  }
  if (new Set(['challenge:live', 'feed:live', 'moments:live', 'voice-rooms:live']).has(channel)) return true
  const authorizationPath = subscriptionAuthorizationUrl(channel)
  if (!authorizationPath) return false
  try {
    const response = await fetch(`${localFallbackApiUrl}${authorizationPath}`, {
      method: authorizationPath.startsWith('/v1/data/messages/chats/') ? 'HEAD' : 'GET',
      headers: { Authorization: `Bearer ${token}` },
      signal: AbortSignal.timeout(5_000),
    })
    return response.ok
  } catch (error) {
    console.warn('[realtime] channel authorization failed:', error.message)
    return false
  }
}

function safeJsonParse(text) {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

function sendRaw(ws, serialized) {
  if (ws.readyState !== WebSocket.OPEN) return false
  if (ws.bufferedAmount > 512 * 1024) {
    gatewayMetrics.slowConsumerDrops += 1
    ws.close(4408, 'SLOW_CONSUMER')
    return false
  }
  ws.send(serialized)
  return true
}

function send(ws, obj) {
  return sendRaw(ws, JSON.stringify(obj))
}

async function revalidateSocket(ws) {
  if (ws._revalidating || !ws._authenticated || ws.readyState !== WebSocket.OPEN) return
  ws._revalidating = true
  try {
    const payload = verifyToken(ws._token)
    const ids = payload ? await resolveUserIds(ws._token, payload) : null
    if (!ids) return ws.close(4401, 'SESSION_EXPIRED')
    setSocketUserIds(ws, ids)
    for (const channel of [...ws._rooms]) {
      if (!(await maySubscribeToChannel(ws._token, ids, channel))) {
        removeSocketChannel(ws, channel)
        send(ws, { type: 'error', error: 'FORBIDDEN_CHANNEL', channel })
      }
    }
    ws._validatedUntil = Math.min(payload.exp * 1000, Date.now() + AUTH_RECHECK_MS + 10_000)
  } finally {
    ws._revalidating = false
  }
}

wss.on('connection', (ws, req) => {
  const clientIp = normalizeClientIp(req)
  const ipCount = ipConnections.get(clientIp) || 0
  if (wss.clients.size > MAX_TOTAL_CONNECTIONS || ipCount >= MAX_CONNECTIONS_PER_IP) {
    gatewayMetrics.connectionLimitRejections += 1
    ws.close(4429, 'CONNECTION_LIMIT')
    return
  }
  ipConnections.set(clientIp, ipCount + 1)

  ws._clientIp = clientIp
  ws._rooms = new Set()
  ws._userId = null
  ws._userIds = new Set()
  ws._token = ''
  ws._identityReady = null
  ws._authenticated = false
  ws._validatedUntil = 0
  ws._pendingSubscriptions = 0
  let messagesInWindow = 0
  let windowStartedAt = Date.now()
  let expiryTimer
  let recheckTimer

  const authenticationTimeout = setTimeout(() => {
    if (!ws._authenticated) {
      gatewayMetrics.authFailures += 1
      ws.close(4401, 'AUTHENTICATION_TIMEOUT')
    }
  }, 5_000)

  function authenticate(tokenValue) {
    if (ws._identityReady) return ws._identityReady
    const token = String(tokenValue || '').trim()
    const payload = verifyToken(token)
    if (!payload?.sub) {
      gatewayMetrics.authFailures += 1
      ws.close(4401, 'UNAUTHORIZED')
      return null
    }
    ws._token = token
    ws._sessionId = String(payload.sid || '')
    ws._userId = String(payload.sub)
    ws._identityReady = resolveUserIds(token, payload).then((ids) => {
    if (!ids || ws.readyState !== WebSocket.OPEN || !verifyToken(token)) {
      gatewayMetrics.authFailures += 1
      ws.close(4403, 'ACCOUNT_NOT_ACTIVE')
      return null
    }
    setSocketUserIds(ws, ids)
    ws._authenticated = true
    clearTimeout(authenticationTimeout)
    ws._validatedUntil = Math.min(payload.exp * 1000, Date.now() + AUTH_RECHECK_MS + 10_000)
    const expire = () => {
      const remaining = payload.exp * 1000 - Date.now()
      if (remaining <= 0) return ws.close(4401, 'SESSION_EXPIRED')
      expiryTimer = setTimeout(expire, Math.min(remaining, 2_147_483_647))
      expiryTimer.unref()
    }
    expire()
    recheckTimer = setInterval(() => { void revalidateSocket(ws) }, AUTH_RECHECK_MS)
    recheckTimer.unref()
    send(ws, { type: 'connected', userId: ws._userId, service: serviceName, version })
    return ids
    })
    return ws._identityReady
  }

  const headerToken = parseBearer(req.headers)
  if (headerToken) authenticate(headerToken)

  ws.on('message', async (raw) => {
    if (Date.now() - windowStartedAt >= 10_000) { windowStartedAt = Date.now(); messagesInWindow = 0 }
    if (++messagesInWindow > 100) {
      gatewayMetrics.rateLimitDrops += 1
      return ws.close(4429, 'RATE_LIMITED')
    }
    const msg = safeJsonParse(raw.toString('utf8'))
    if (!msg || typeof msg !== 'object') return

    const type = String(msg.type || '').trim()

    if (type === 'authenticate') {
      authenticate(msg.token)
      return
    }

    if (!ws._identityReady) return send(ws, { type: 'error', error: 'AUTHENTICATION_REQUIRED' })
    const allowedUserIds = await ws._identityReady
    if (!allowedUserIds || !ws._authenticated || ws.readyState !== WebSocket.OPEN) return
    if (Date.now() >= ws._validatedUntil) return ws.close(4401, 'SESSION_EXPIRED')

    if (type === 'subscribe') {
      const channel = String(msg.channel || '').trim()
      if (!channel || channel.length > 256) return send(ws, { type: 'error', error: 'INVALID_CHANNEL' })
      if (ws._pendingSubscriptions >= 4 || (!ws._rooms.has(channel) && ws._rooms.size + ws._pendingSubscriptions >= MAX_SUBSCRIPTIONS)) {
        return send(ws, { type: 'error', error: 'SUBSCRIPTION_LIMIT', channel })
      }
      ws._pendingSubscriptions++
      try {
        if (!(await maySubscribeToChannel(ws._token, allowedUserIds, channel))) {
          return send(ws, { type: 'error', error: 'FORBIDDEN_CHANNEL', channel })
        }
        if (ws.readyState !== WebSocket.OPEN || Date.now() >= ws._validatedUntil) return
        addSocketChannel(ws, channel)
        return send(ws, { type: 'subscribed', channel })
      } finally { ws._pendingSubscriptions-- }
    }

    if (type === 'unsubscribe') {
      const channel = String(msg.channel || '').trim()
      if (!channel) return
      removeSocketChannel(ws, channel)
      return send(ws, { type: 'unsubscribed', channel })
    }

    if (type === 'ping') {
      return send(ws, { type: 'pong', t: Date.now() })
    }

    return send(ws, { type: 'error', error: 'UNKNOWN_MESSAGE_TYPE' })
  })

  ws.on('close', () => {
    clearTimeout(authenticationTimeout)
    clearTimeout(expiryTimer)
    clearInterval(recheckTimer)
    ws._authenticated = false
    ws._token = ''
    clearSocketIndexes(ws)
    const remaining = (ipConnections.get(clientIp) || 1) - 1
    if (remaining <= 0) ipConnections.delete(clientIp)
    else ipConnections.set(clientIp, remaining)
  })
  ws.on('error', () => { ws.close() })
})

const SEEN_EVENT_IDS_MAX = 10_000
const seenEventIds = new Set()

function rememberEventId(eventId) {
  const id = String(eventId || '').trim()
  if (!id) return true
  if (seenEventIds.has(id)) return false
  seenEventIds.add(id)
  if (seenEventIds.size > SEEN_EVENT_IDS_MAX) {
    const oldest = seenEventIds.values().next().value
    if (oldest) seenEventIds.delete(oldest)
  }
  return true
}

function broadcastLocal(channel, payload, options = {}) {
  const excludeSessionId = String(options?.excludeSessionId || '').trim()
  const targetUserId = channel.startsWith('user:')
    ? channel.slice('user:'.length).trim().toLowerCase()
    : ''
  const directSet = channelSubscribers.get(channel)
  const userSet = targetUserId ? userSockets.get(targetUserId) : null
  if ((!directSet || directSet.size === 0) && (!userSet || userSet.size === 0)) return 0

  const now = Number.isFinite(Number(options?.ts)) ? Number(options.ts) : Date.now()
  const serialized = JSON.stringify({
    type: 'event',
    channel,
    payload,
    ...(options?.eventId ? { eventId: String(options.eventId) } : {}),
    ts: new Date(now).toISOString(),
  })

  const recipients = directSet && userSet
    ? new Set([...directSet, ...userSet])
    : (directSet || userSet)

  let delivered = 0
  for (const client of recipients) {
    if (client.readyState !== WebSocket.OPEN) continue
    if (!client._authenticated || now >= client._validatedUntil) continue
    if (excludeSessionId && client._sessionId && client._sessionId === excludeSessionId) continue
    const matchesCanonicalUser = Boolean(targetUserId && client._userIds?.has(targetUserId))
    if (sendRaw(client, serialized)) {
      delivered += 1
      gatewayMetrics.messagesDelivered += 1
    }
    // Revocation is committed in the API before this private event is sent.
    // Close this device only; other browser sessions of the same user stay open.
    if (matchesCanonicalUser && payload?.event === 'web_sessions_revoked' &&
        client._sessionId && Array.isArray(payload.sessionIds) && payload.sessionIds.includes(client._sessionId)) {
      client._authenticated = false
      client.close(4401, 'WEB_SESSION_REVOKED')
    }
  }
  return delivered
}

function fieldsArrayToObject(fields) {
  const out = {}
  if (!Array.isArray(fields)) return out
  for (let i = 0; i + 1 < fields.length; i += 2) {
    out[String(fields[i])] = fields[i + 1]
  }
  return out
}

function createRedisStreamBus() {
  if (!realtimeRedisUrl) return null
  const sharedConfig = {
    url: realtimeRedisUrl,
    password: process.env.REALTIME_REDIS_PASSWORD || process.env.REDIS_PASSWORD || '',
    passwordFile: process.env.REALTIME_REDIS_PASSWORD_FILE || process.env.SIGNALING_REDIS_PASSWORD_FILE || '',
    connectTimeoutMs: 3_000,
    commandTimeoutMs: 1_500,
  }
  const pubClient = createRedisRespClient(sharedConfig)
  const subClient = createRedisRespClient({
    ...sharedConfig,
    commandTimeoutMs: realtimeRedisBlockMs + 4_000,
  })
  if (!pubClient || !subClient) return null

  let stopped = false
  let loopRunning = false
  let lastStreamId = null

  pubClient.on('error', (error) => {
    console.warn('[realtime-redis-pub] degraded:', error?.code || error?.message || 'REDIS_ERROR')
  })
  subClient.on('error', (error) => {
    console.warn('[realtime-redis-sub] degraded:', error?.code || error?.message || 'REDIS_ERROR')
  })

  async function initCursorIfNeeded() {
    if (lastStreamId !== null) return
    const latest = await subClient.sendCommand(['XREVRANGE', realtimeRedisStreamName, '+', '-', 'COUNT', '1'])
    if (Array.isArray(latest) && latest.length > 0 && Array.isArray(latest[0]) && latest[0][0]) {
      lastStreamId = String(latest[0][0])
    } else {
      lastStreamId = '0-0'
    }
  }

  async function readLoop() {
    if (loopRunning || stopped) return
    loopRunning = true
    try {
      while (!stopped && subClient.isReady()) {
        await initCursorIfNeeded()
        const response = await subClient.sendCommand(
          ['XREAD', 'COUNT', '100', 'BLOCK', String(realtimeRedisBlockMs), 'STREAMS', realtimeRedisStreamName, lastStreamId],
          { timeoutMs: realtimeRedisBlockMs + 4_000 },
        )
        if (!Array.isArray(response)) continue
        for (const streamEntry of response) {
          const entries = Array.isArray(streamEntry?.[1]) ? streamEntry[1] : []
          for (const entry of entries) {
            if (!Array.isArray(entry) || entry.length < 2) continue
            const entryId = String(entry[0] || '')
            if (entryId) lastStreamId = entryId
            const fields = fieldsArrayToObject(entry[1])
            if (fields.origin === instanceId) continue
            const eventId = String(fields.eventId || entryId || '')
            if (!rememberEventId(eventId)) {
              gatewayMetrics.streamDuplicatesSkipped += 1
              continue
            }
            const channel = String(fields.channel || '').trim()
            if (!channel || channel.length > 256) continue
            const payload = safeJsonParse(fields.payload || '{}') ?? {}
            const excludeSessionId = String(fields.excludeSessionId || '').trim()
            const ts = Number(fields.ts || Date.now())
            gatewayMetrics.streamEventsReceived += 1
            broadcastLocal(channel, payload, { excludeSessionId, eventId, ts })
          }
        }
      }
    } catch (error) {
      if (!stopped) {
        console.warn('[realtime-redis-sub] read loop interrupted:', error?.code || error?.message || 'REDIS_ERROR')
      }
    } finally {
      loopRunning = false
    }
  }

  subClient.on('ready', () => {
    void readLoop()
  })

  return {
    async start() {
      await Promise.all([pubClient.connect(), subClient.connect()])
      if (subClient.isReady()) void readLoop()
    },
    isReady() {
      return pubClient.isReady() && subClient.isReady()
    },
    async publish({ eventId, channel, payload, excludeSessionId, ts }) {
      if (!pubClient.isReady()) {
        gatewayMetrics.streamPublishErrors += 1
        return false
      }
      try {
        await pubClient.sendCommand(
          [
            'XADD',
            realtimeRedisStreamName,
            'MAXLEN',
            '~',
            String(realtimeRedisStreamMaxLen),
            '*',
            'origin',
            instanceId,
            'eventId',
            String(eventId || ''),
            'channel',
            String(channel),
            'excludeSessionId',
            String(excludeSessionId || ''),
            'ts',
            String(ts || Date.now()),
            'payload',
            JSON.stringify(payload ?? {}),
          ],
          { timeoutMs: 1_500 },
        )
        gatewayMetrics.streamEventsPublished += 1
        return true
      } catch (error) {
        gatewayMetrics.streamPublishErrors += 1
        console.warn('[realtime-redis-pub] XADD failed:', error?.code || error?.message || 'REDIS_ERROR')
        return false
      }
    },
    async close() {
      stopped = true
      await Promise.all([pubClient.close(), subClient.close()])
    },
  }
}

const redisBus = createRedisStreamBus()
if (redisBus) {
  void redisBus.start()
}

function broadcast(channel, payload, options = {}) {
  gatewayMetrics.eventsPublished += 1
  const eventId = String(options?.eventId || crypto.randomUUID())
  const ts = Date.now()
  rememberEventId(eventId)
  const delivered = broadcastLocal(channel, payload, {
    excludeSessionId: options?.excludeSessionId,
    eventId,
    ts,
  })
  if (redisBus) {
    void redisBus.publish({
      eventId,
      channel,
      payload,
      excludeSessionId: options?.excludeSessionId,
      ts,
    })
  }
  return delivered
}

function isInternalRequestAuthorized(req) {
  const supplied = String(req.headers.authorization || '').replace(/^Bearer\s+/i, '').trim()
  const expected = internalSecret
  return (!expected && !isProduction) || (
    Buffer.byteLength(supplied) === Buffer.byteLength(expected) &&
    supplied.length > 0 &&
    crypto.timingSafeEqual(Buffer.from(supplied), Buffer.from(expected))
  )
}

/**
 * MVP: allow API to trigger events via HTTP:
 * POST /internal/publish { channel, payload, excludeSessionId? }
 * POST /internal/publish-batch { events: [{ channel, payload, excludeSessionId? }] }
 * This avoids DB triggers for now.
 */
app.use(express.json({ limit: '512kb' }))
app.post('/internal/publish', (req, res) => {
  if (!isInternalRequestAuthorized(req)) return res.status(401).json({ ok: false, error: 'UNAUTHORIZED' })
  const channel = String(req.body?.channel || '').trim()
  if (!channel || channel.length > 256) return res.status(400).json({ ok: false, error: 'CHANNEL_REQUIRED' })

  broadcast(channel, req.body?.payload ?? {}, { excludeSessionId: req.body?.excludeSessionId })
  return res.json({ ok: true })
})

app.post('/internal/publish-batch', (req, res) => {
  if (!isInternalRequestAuthorized(req)) return res.status(401).json({ ok: false, error: 'UNAUTHORIZED' })
  const events = Array.isArray(req.body?.events) ? req.body.events : null
  if (!events || events.length === 0 || events.length > 500) {
    return res.status(400).json({ ok: false, error: 'EVENTS_BATCH_INVALID' })
  }
  gatewayMetrics.batchRequests += 1
  let delivered = 0
  for (const item of events) {
    const channel = String(item?.channel || '').trim()
    if (!channel || channel.length > 256) continue
    broadcast(channel, item?.payload ?? {}, { excludeSessionId: item?.excludeSessionId })
    delivered += 1
  }
  return res.json({ ok: true, delivered })
})

app.get('/internal/metrics', (req, res) => {
  if (!isInternalRequestAuthorized(req)) return res.status(401).json({ ok: false, error: 'UNAUTHORIZED' })
  const mem = process.memoryUsage()
  let authenticatedConnections = 0
  for (const client of wss.clients) {
    if (client.readyState === WebSocket.OPEN && client._authenticated) authenticatedConnections += 1
  }
  const snapshot = {
    service: serviceName,
    instanceId,
    redisBusMode: redisBus ? 'redis-streams' : 'local',
    redisConnected: Boolean(redisBus && redisBus.isReady()),
    uptimeSeconds: Math.round((Date.now() - gatewayMetrics.startedAtMs) / 1000),
    activeConnections: wss.clients.size,
    authenticatedConnections,
    indexedUsers: userSockets.size,
    indexedChannels: channelSubscribers.size,
    eventsPublished: gatewayMetrics.eventsPublished,
    batchRequests: gatewayMetrics.batchRequests,
    messagesDelivered: gatewayMetrics.messagesDelivered,
    streamEventsPublished: gatewayMetrics.streamEventsPublished,
    streamEventsReceived: gatewayMetrics.streamEventsReceived,
    streamDuplicatesSkipped: gatewayMetrics.streamDuplicatesSkipped,
    streamPublishErrors: gatewayMetrics.streamPublishErrors,
    slowConsumerDrops: gatewayMetrics.slowConsumerDrops,
    rateLimitDrops: gatewayMetrics.rateLimitDrops,
    connectionLimitRejections: gatewayMetrics.connectionLimitRejections,
    authFailures: gatewayMetrics.authFailures,
    eventLoopLagP95Ms: Number((eventLoopMonitor.percentile(95) / 1e6).toFixed(2)),
    memoryRssBytes: mem.rss,
    memoryHeapUsedBytes: mem.heapUsed,
  }
  if (String(req.query?.format || '').toLowerCase() === 'json') {
    return res.json({ ok: true, metrics: snapshot })
  }
  const lines = [
    '# TYPE novvin_realtime_active_connections gauge',
    `novvin_realtime_active_connections ${snapshot.activeConnections}`,
    '# TYPE novvin_realtime_authenticated_connections gauge',
    `novvin_realtime_authenticated_connections ${snapshot.authenticatedConnections}`,
    '# TYPE novvin_realtime_indexed_users gauge',
    `novvin_realtime_indexed_users ${snapshot.indexedUsers}`,
    '# TYPE novvin_realtime_indexed_channels gauge',
    `novvin_realtime_indexed_channels ${snapshot.indexedChannels}`,
    '# TYPE novvin_realtime_events_published_total counter',
    `novvin_realtime_events_published_total ${snapshot.eventsPublished}`,
    '# TYPE novvin_realtime_messages_delivered_total counter',
    `novvin_realtime_messages_delivered_total ${snapshot.messagesDelivered}`,
    '# TYPE novvin_realtime_stream_events_published_total counter',
    `novvin_realtime_stream_events_published_total ${snapshot.streamEventsPublished}`,
    '# TYPE novvin_realtime_stream_events_received_total counter',
    `novvin_realtime_stream_events_received_total ${snapshot.streamEventsReceived}`,
    '# TYPE novvin_realtime_stream_duplicates_skipped_total counter',
    `novvin_realtime_stream_duplicates_skipped_total ${snapshot.streamDuplicatesSkipped}`,
    '# TYPE novvin_realtime_redis_connected gauge',
    `novvin_realtime_redis_connected ${snapshot.redisConnected ? 1 : 0}`,
    '# TYPE novvin_realtime_slow_consumer_drops_total counter',
    `novvin_realtime_slow_consumer_drops_total ${snapshot.slowConsumerDrops}`,
    '# TYPE novvin_realtime_rate_limit_drops_total counter',
    `novvin_realtime_rate_limit_drops_total ${snapshot.rateLimitDrops}`,
    '# TYPE novvin_realtime_connection_limit_rejections_total counter',
    `novvin_realtime_connection_limit_rejections_total ${snapshot.connectionLimitRejections}`,
    '# TYPE novvin_realtime_event_loop_lag_p95_ms gauge',
    `novvin_realtime_event_loop_lag_p95_ms ${snapshot.eventLoopLagP95Ms}`,
    '# TYPE novvin_realtime_memory_rss_bytes gauge',
    `novvin_realtime_memory_rss_bytes ${snapshot.memoryRssBytes}`,
  ]
  res.setHeader('Content-Type', 'text/plain; version=0.0.4; charset=utf-8')
  return res.send(`${lines.join('\n')}\n`)
})

async function shutdownRealtime() {
  try {
    await redisBus?.close()
  } catch {
    // best-effort shutdown
  }
  wss.close()
  server.close(() => process.exit(0))
  setTimeout(() => process.exit(0), 2_000).unref()
}

if (require.main === module) {
  process.on('SIGTERM', () => { void shutdownRealtime() })
  process.on('SIGINT', () => { void shutdownRealtime() })
  server.listen(port, process.env.REALTIME_LISTEN_HOST || '0.0.0.0', () => {
    console.log(`[realtime] ${serviceName} ${version} (${instanceId.slice(0, 8)}) listening on ${port} (bus=${redisBus ? 'redis-streams' : 'local'})`)
  })
}

module.exports = {
  app,
  broadcast,
  broadcastLocal,
  maySubscribeToChannel,
  redisBus,
  server,
  subscriptionAuthorizationUrl,
  verifyToken,
  revalidateSocket,
  wss,
}
