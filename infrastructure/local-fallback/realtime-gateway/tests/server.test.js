const assert = require('assert/strict')
const jwt = require('jsonwebtoken')
const WebSocket = require('ws')

process.env.NODE_ENV = 'test'
process.env.JWT_SECRET = 'realtime-test-jwt-secret-at-least-32-characters'
process.env.JWT_ISSUER = 'conectate-local-fallback'
process.env.REALTIME_INTERNAL_SECRET = 'realtime-test-internal-secret-at-least-32-characters'
process.env.LOCAL_FALLBACK_API_URL = 'http://api.invalid'

const originalFetch = global.fetch
let accountActive = true
let chatAllowed = true
let raffleAllowed = true
global.fetch = async (url) => {
  if (String(url).endsWith('/v1/auth/me')) {
    return { ok: accountActive, json: async () => ({ user: { id: 'user-a' } }) }
  }
  if (String(url).includes('/messages/chats/')) return { ok: chatAllowed }
  if (String(url).includes('/data/raffles/')) return { ok: raffleAllowed }
  return { ok: true, json: async () => ({}) }
}

const {
  maySubscribeToChannel,
  server,
  subscriptionAuthorizationUrl,
  revalidateSocket,
  verifyToken,
  wss,
} = require('../src/server')

assert.equal(subscriptionAuthorizationUrl('chat:chat-1'), '/v1/data/messages/chats/chat-1')
assert.equal(subscriptionAuthorizationUrl('message-markers:chat:chat-1'), '/v1/data/messages/markers/chat/chat-1/access')
assert.equal(subscriptionAuthorizationUrl('message-markers:group:group-1'), '/v1/data/messages/markers/group/group-1/access')
assert.equal(subscriptionAuthorizationUrl('random-chat:session-1'), '/v1/data/proximity/random/sessions/session-1')
assert.equal(subscriptionAuthorizationUrl('video-comments:video-1'), '/v1/data/videos/video-1')
assert.equal(subscriptionAuthorizationUrl('live-comments:live-1'), '/v1/data/live/live-1')
assert.equal(subscriptionAuthorizationUrl('admin:live'), '/v1/data/admin/access')
assert.equal(subscriptionAuthorizationUrl('raffles:group:group-1'), '/v1/data/raffles/group/group-1/access')
assert.equal(subscriptionAuthorizationUrl('raffles:community:community-1'), '/v1/data/raffles/community/community-1/access')
assert.equal(subscriptionAuthorizationUrl('room:private-room'), null)
assert.equal(subscriptionAuthorizationUrl('unknown:value'), null)

async function waitForMessage(socket, expectedType) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error(`Timed out waiting for ${expectedType}`)), 3_000)
    socket.on('message', (raw) => {
      const message = JSON.parse(raw.toString('utf8'))
      if (message.type !== expectedType) return
      clearTimeout(timeout)
      resolve(message)
    })
    socket.once('error', reject)
  })
}

async function run() {
  const undatedToken = jwt.sign({ sub: 'user-a' }, process.env.JWT_SECRET, {
    issuer: process.env.JWT_ISSUER, audience: 'conectate-clients',
  })
  assert.equal(verifyToken(undatedToken), null, 'tokens must expire')
  assert.equal(await maySubscribeToChannel('token', new Set(['user-a']), 'user:user-a'), true)
  assert.equal(await maySubscribeToChannel('token', new Set(['user-a']), 'random-chat-user:user-b'), false)
  assert.equal(await maySubscribeToChannel('token', new Set(['user-a']), 'live-comments:live-1'), true)
  assert.equal(await maySubscribeToChannel('token', new Set(['user-a']), 'chat:chat-1'), true)
  assert.equal(await maySubscribeToChannel('token', new Set(['user-a']), 'feed:live'), true)
  assert.equal(await maySubscribeToChannel('token', new Set(['user-a']), 'unknown:value'), false)
  assert.equal(await maySubscribeToChannel('token', new Set(['user-a']), 'raffles:group:group-1'), true)
  raffleAllowed = false
  assert.equal(await maySubscribeToChannel('token', new Set(['user-a']), 'raffles:group:group-1'), false)
  assert.equal(await maySubscribeToChannel('token', new Set(['user-a']), 'raffles:community:community-1'), false)
  raffleAllowed = true

  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  const address = server.address()
  const baseUrl = `http://127.0.0.1:${address.port}`
  const wsUrl = `ws://127.0.0.1:${address.port}`

  const unauthorizedPublish = await originalFetch(`${baseUrl}/internal/publish`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ channel: 'feed:live', payload: {} }),
  })
  assert.equal(unauthorizedPublish.status, 401)
  const unicodeSecret = await originalFetch(`${baseUrl}/internal/publish`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${'é'.repeat(process.env.REALTIME_INTERNAL_SECRET.length)}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ channel: 'feed:live' }),
  })
  assert.equal(unicodeSecret.status, 401, 'byte-length mismatch must not throw')

  const token = jwt.sign({ sub: 'user-a' }, process.env.JWT_SECRET, {
    issuer: process.env.JWT_ISSUER,
    audience: 'conectate-clients',
    expiresIn: '5m',
  })
  const socket = new WebSocket(wsUrl)
  await new Promise((resolve, reject) => {
    socket.once('open', resolve)
    socket.once('error', reject)
  })
  socket.send(JSON.stringify({ type: 'authenticate', token }))
  await waitForMessage(socket, 'connected')
  socket.send(JSON.stringify({ type: 'subscribe', channel: 'feed:live' }))
  const feedSubscribed = await waitForMessage(socket, 'subscribed')
  assert.equal(feedSubscribed.channel, 'feed:live')
  const feedEventPromise = waitForMessage(socket, 'event')
  const publishResponse = await originalFetch(`${baseUrl}/internal/publish`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${process.env.REALTIME_INTERNAL_SECRET}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      channel: 'feed:live',
      payload: { event: 'video_feed', action: 'deleted', videoId: 'video-1' },
    }),
  })
  assert.equal(publishResponse.status, 200)
  const feedEvent = await feedEventPromise
  assert.equal(feedEvent.channel, 'feed:live')
  assert.equal(feedEvent.payload.action, 'deleted')
  assert.equal(feedEvent.payload.videoId, 'video-1')
  socket.send(JSON.stringify({ type: 'subscribe', channel: 'random-chat-user:user-a' }))
  const subscribed = await waitForMessage(socket, 'subscribed')
  assert.equal(subscribed.channel, 'random-chat-user:user-a')
  socket.send(JSON.stringify({ type: 'subscribe', channel: 'chat:chat-1' }))
  await waitForMessage(socket, 'subscribed')
  const serverSocket = [...wss.clients].find((client) => client._userId === 'user-a')
  chatAllowed = false
  const revokedChannel = waitForMessage(socket, 'error')
  await revalidateSocket(serverSocket)
  assert.equal((await revokedChannel).error, 'FORBIDDEN_CHANNEL')
  assert.equal(serverSocket._rooms.has('chat:chat-1'), false)
  accountActive = false
  const revokedClose = waitForClose(socket)
  await revalidateSocket(serverSocket)
  assert.equal(await revokedClose, 4401, 'revoked accounts are disconnected')
  accountActive = true

  const shortToken = jwt.sign({ sub: 'user-a' }, process.env.JWT_SECRET, {
    issuer: process.env.JWT_ISSUER, audience: 'conectate-clients', expiresIn: 2,
  })
  const expiringSocket = new WebSocket(wsUrl, { headers: { Authorization: `Bearer ${shortToken}` } })
  const expiredClose = waitForClose(expiringSocket)
  await waitForMessage(expiringSocket, 'connected')
  assert.equal(await expiredClose, 4401, 'idle clients disconnect at JWT expiry')

  const oversizedSocket = new WebSocket(wsUrl)
  const oversizedClose = waitForClose(oversizedSocket)
  await new Promise((resolve) => oversizedSocket.once('open', resolve))
  oversizedSocket.send('x'.repeat(40 * 1024))
  assert.equal(await oversizedClose, 1009, 'oversized frames are rejected')

  console.log('realtime gateway authentication and authorization tests passed')
}

function waitForClose(socket) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Timed out waiting for close')), 4_000)
    socket.once('close', (code) => { clearTimeout(timeout); resolve(code) })
    socket.once('error', (error) => { clearTimeout(timeout); reject(error) })
  })
}

run().catch((error) => {
  console.error(error)
  process.exitCode = 1
}).finally(async () => {
  global.fetch = originalFetch
  for (const socket of wss.clients) socket.terminate()
  if (server.listening) await new Promise((resolve) => server.close(resolve))
})
