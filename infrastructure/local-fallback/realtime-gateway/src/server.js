const express = require('express')
const http = require('http')
const helmet = require('helmet')
const jwt = require('jsonwebtoken')
const WebSocket = require('ws')
const crypto = require('crypto')

const serviceName = 'conectate-local-fallback-realtime-gateway'
const version = '0.1.0'
const isProduction = String(process.env.NODE_ENV || '').trim().toLowerCase() === 'production'

const port = Number(process.env.PORT || 3002)
const jwtSecret = String(process.env.JWT_SECRET || '')
const jwtIssuer = String(process.env.JWT_ISSUER || 'conectate-local-fallback')
const localFallbackApiUrl = String(process.env.LOCAL_FALLBACK_API_URL || 'http://api:3001').replace(/\/+$/, '')
const internalSecret = String(process.env.REALTIME_INTERNAL_SECRET || '').trim()

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
app.get('/', (_req, res) => res.json({ ok: true, service: serviceName, version }))
app.get('/health', (_req, res) => res.json({ ok: true, service: serviceName, version, time: new Date().toISOString() }))

const server = http.createServer(app)
const wss = new WebSocket.Server({ server, maxPayload: 32 * 1024, perMessageDeflate: false })
const MAX_SUBSCRIPTIONS = 32
const AUTH_RECHECK_MS = 60_000

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
    ['video-comments:', (id) => `/v1/data/videos/${encodeURIComponent(id)}`],
    ['live-comments:', (id) => `/v1/data/live/${encodeURIComponent(id)}`],
    ['live-collaboration:', (id) => `/v1/data/live/${encodeURIComponent(id)}`],
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

function send(ws, obj) {
  if (ws.readyState !== WebSocket.OPEN) return
  if (ws.bufferedAmount > 512 * 1024) return ws.close(4408, 'SLOW_CONSUMER')
  ws.send(JSON.stringify(obj))
}

async function revalidateSocket(ws) {
  if (ws._revalidating || !ws._authenticated || ws.readyState !== WebSocket.OPEN) return
  ws._revalidating = true
  try {
    const payload = verifyToken(ws._token)
    const ids = payload ? await resolveUserIds(ws._token, payload) : null
    if (!ids) return ws.close(4401, 'SESSION_EXPIRED')
    ws._userIds = ids
    for (const channel of [...ws._rooms]) {
      if (!(await maySubscribeToChannel(ws._token, ids, channel))) {
        ws._rooms.delete(channel)
        send(ws, { type: 'error', error: 'FORBIDDEN_CHANNEL', channel })
      }
    }
    ws._validatedUntil = Math.min(payload.exp * 1000, Date.now() + AUTH_RECHECK_MS + 10_000)
  } finally {
    ws._revalidating = false
  }
}

wss.on('connection', (ws, req) => {
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
    if (!ws._authenticated) ws.close(4401, 'AUTHENTICATION_TIMEOUT')
  }, 5_000)

  function authenticate(tokenValue) {
    if (ws._identityReady) return ws._identityReady
    const token = String(tokenValue || '').trim()
    const payload = verifyToken(token)
    if (!payload?.sub) {
      ws.close(4401, 'UNAUTHORIZED')
      return null
    }
    ws._token = token
    ws._sessionId = String(payload.sid || '')
    ws._userId = String(payload.sub)
    ws._identityReady = resolveUserIds(token, payload).then((ids) => {
    if (!ids || ws.readyState !== WebSocket.OPEN || !verifyToken(token)) {
      ws.close(4403, 'ACCOUNT_NOT_ACTIVE')
      return null
    }
    ws._userIds = ids
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
    if (++messagesInWindow > 100) return ws.close(4429, 'RATE_LIMITED')
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
        return send(ws, { type: 'error', error: 'SUBSCRIPTION_LIMIT' })
      }
      ws._pendingSubscriptions++
      try {
        if (!(await maySubscribeToChannel(ws._token, allowedUserIds, channel))) {
          return send(ws, { type: 'error', error: 'FORBIDDEN_CHANNEL' })
        }
        if (ws.readyState !== WebSocket.OPEN || Date.now() >= ws._validatedUntil) return
        ws._rooms.add(channel)
        return send(ws, { type: 'subscribed', channel })
      } finally { ws._pendingSubscriptions-- }
    }

    if (type === 'unsubscribe') {
      const channel = String(msg.channel || '').trim()
      if (!channel) return
      ws._rooms.delete(channel)
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
    ws._rooms.clear()
  })
  ws.on('error', () => { ws.close() })
})

function broadcast(channel, payload) {
  const message = {
    type: 'event',
    channel,
    payload,
    ts: new Date().toISOString(),
  }

  wss.clients.forEach((client) => {
    if (client.readyState !== WebSocket.OPEN) return
    if (!client._authenticated || Date.now() >= client._validatedUntil) return
    const targetUserId = channel.startsWith('user:')
      ? channel.slice('user:'.length).trim().toLowerCase()
      : ''
    const subscribedDirectly = Boolean(client._rooms?.has(channel))
    const matchesCanonicalUser = Boolean(targetUserId && client._userIds?.has(targetUserId))
    if (!subscribedDirectly && !matchesCanonicalUser) return
    send(client, message)
    // Revocation is committed in the API before this private event is sent.
    // Close this device only; other browser sessions of the same user stay open.
    if (matchesCanonicalUser && payload?.event === 'web_sessions_revoked' &&
        client._sessionId && Array.isArray(payload.sessionIds) && payload.sessionIds.includes(client._sessionId)) {
      client._authenticated = false
      client.close(4401, 'WEB_SESSION_REVOKED')
    }
  })
}

/**
 * MVP: allow API to trigger events via HTTP:
 * POST /internal/publish { channel, payload }
 * This avoids DB triggers for now.
 */
app.use(express.json({ limit: '256kb' }))
app.post('/internal/publish', (req, res) => {
  const supplied = String(req.headers.authorization || '').replace(/^Bearer\s+/i, '').trim()
  const expected = internalSecret
  const authorized = !expected && !isProduction || (
    Buffer.byteLength(supplied) === Buffer.byteLength(expected) &&
    supplied.length > 0 &&
    crypto.timingSafeEqual(Buffer.from(supplied), Buffer.from(expected))
  )
  if (!authorized) return res.status(401).json({ ok: false, error: 'UNAUTHORIZED' })
  const channel = String(req.body?.channel || '').trim()
  if (!channel || channel.length > 256) return res.status(400).json({ ok: false, error: 'CHANNEL_REQUIRED' })

  broadcast(channel, req.body?.payload ?? {})
  return res.json({ ok: true })
})

if (require.main === module) {
  server.listen(port, '0.0.0.0', () => {
    console.log(`[realtime] ${serviceName} ${version} listening on ${port}`)
  })
}

module.exports = {
  app,
  maySubscribeToChannel,
  server,
  subscriptionAuthorizationUrl,
  verifyToken,
  revalidateSocket,
  wss,
}
