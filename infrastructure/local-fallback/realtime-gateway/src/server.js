const express = require('express')
const http = require('http')
const helmet = require('helmet')
const jwt = require('jsonwebtoken')
const WebSocket = require('ws')

const serviceName = 'conectate-local-fallback-realtime-gateway'
const version = '0.1.0'

const port = Number(process.env.PORT || 3002)
const jwtSecret = String(process.env.JWT_SECRET || '')
const jwtIssuer = String(process.env.JWT_ISSUER || 'conectate-local-fallback')

if (!jwtSecret || jwtSecret.length < 32) {
  console.warn('[realtime] JWT_SECRET missing or too short; gateway will still start but will reject auth')
}

const app = express()
app.disable('x-powered-by')
app.use(helmet())
app.get('/', (_req, res) => res.json({ ok: true, service: serviceName, version }))
app.get('/health', (_req, res) => res.json({ ok: true, service: serviceName, version, time: new Date().toISOString() }))

const server = http.createServer(app)
const wss = new WebSocket.Server({ server })

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
    })
    return payload
  } catch {
    return null
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
  ws.send(JSON.stringify(obj))
}

wss.on('connection', (ws, req) => {
  ws._rooms = new Set()
  ws._userId = null

  let queryToken = ''
  try {
    const requestUrl = new URL(req.url || '/', 'http://realtime-gateway.local')
    queryToken = String(requestUrl.searchParams.get('token') || '').trim()
  } catch {
    queryToken = ''
  }

  const token = parseBearer(req.headers) || queryToken
  const payload = verifyToken(token)

  if (!payload?.sub) {
    // close with policy violation style
    ws.close(4401, 'UNAUTHORIZED')
    return
  }

  ws._userId = String(payload.sub)

  send(ws, { type: 'connected', userId: ws._userId, service: serviceName, version })

  ws.on('message', (raw) => {
    const msg = safeJsonParse(raw.toString('utf8'))
    if (!msg || typeof msg !== 'object') return

    const type = String(msg.type || '').trim()

    if (type === 'subscribe') {
      const channel = String(msg.channel || '').trim()
      if (!channel) return

      // Basic allowlist semantics: prevent subscribing to arbitrary channels.
      // Allowed:
      // - user:{self}
      // - chat:{chatId}
      // - room:{roomId} (future)
      // - groups/communities later
      const isUserRoom = channel.startsWith('user:')
      if (isUserRoom) {
        const targetUserId = channel.slice('user:'.length)
        if (targetUserId !== ws._userId) {
          return send(ws, { type: 'error', error: 'FORBIDDEN_USER_ROOM' })
        }
      }

      const isChatRoom = channel.startsWith('chat:')
      const isRoomChannel = channel.startsWith('room:')
      const isChallengeRoom = channel === 'challenge:live'
      const isFeedRoom = channel === 'feed:live'
      const isMomentsRoom = channel === 'moments:live'
      const isAdminRoom = channel === 'admin:live'
      if (!isUserRoom && !isChatRoom && !isRoomChannel && !isChallengeRoom && !isFeedRoom && !isMomentsRoom && !isAdminRoom) {
        return send(ws, { type: 'error', error: 'FORBIDDEN_CHANNEL' })
      }

      ws._rooms.add(channel)
      return send(ws, { type: 'subscribed', channel })
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
    ws._rooms.clear()
  })
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
    if (!client._rooms || !client._rooms.has(channel)) return
    send(client, message)
  })
}

/**
 * MVP: allow API to trigger events via HTTP:
 * POST /internal/publish { channel, payload }
 * This avoids DB triggers for now.
 */
app.use(express.json({ limit: '256kb' }))
app.post('/internal/publish', (req, res) => {
  const channel = String(req.body?.channel || '').trim()
  if (!channel) return res.status(400).json({ ok: false, error: 'CHANNEL_REQUIRED' })

  broadcast(channel, req.body?.payload ?? {})
  return res.json({ ok: true })
})

server.listen(port, '0.0.0.0', () => {
  console.log(`[realtime] ${serviceName} ${version} listening on ${port}`)
})
