const assert = require('node:assert/strict')
const { randomBytes, randomUUID } = require('node:crypto')
const { once } = require('node:events')
const http = require('node:http')
const test = require('node:test')
const jwt = require('jsonwebtoken')
const WebSocket = require('ws')

test('a device can reconnect after a temporary identity or membership lookup failure', async (t) => {
  const user = randomUUID()
  const chat = randomUUID()
  const channel = 'chat:' + chat
  let membershipStatus = 200
  let identityStatus = 200
  const api = http.createServer((req, res) => {
    if (req.url === '/v1/auth/me') {
      res.writeHead(identityStatus, { 'content-type': 'application/json' })
      return res.end(JSON.stringify({ user: { id: user } }))
    }
    res.writeHead(req.method === 'HEAD' && req.url === '/v1/data/messages/chats/' + chat
      ? membershipStatus
      : 404)
    res.end()
  })
  api.listen(0, '127.0.0.1')
  await once(api, 'listening')
  t.after(() => api.close())

  process.env.LOCAL_FALLBACK_API_URL = 'http://127.0.0.1:' + api.address().port
  process.env.JWT_SECRET = randomBytes(32).toString('hex')
  process.env.JWT_ISSUER = 'realtime-channel-fixture'
  process.env.REALTIME_INTERNAL_SECRET = randomBytes(32).toString('hex')
  const { server, wss, revalidateSocket, broadcastLocal } = require('../src/server')
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  t.after(() => server.close())

  const token = jwt.sign({ sub: user }, process.env.JWT_SECRET, {
    issuer: process.env.JWT_ISSUER, audience: 'conectate-clients', expiresIn: '5m',
  })
  const url = 'ws://127.0.0.1:' + server.address().port
  async function connect() {
    const socket = new WebSocket(url, { headers: { Authorization: 'Bearer ' + token } })
    t.after(() => socket.terminate())
    const [hello] = await once(socket, 'message', { signal: AbortSignal.timeout(5000) })
    assert.equal(JSON.parse(hello).type, 'connected')
    return socket
  }
  async function subscribe() {
    const socket = await connect()
    const ack = once(socket, 'message', { signal: AbortSignal.timeout(5000) })
    socket.send(JSON.stringify({ type: 'subscribe', channel }))
    assert.deepEqual(JSON.parse((await ack)[0]), { type: 'subscribed', channel })
    return socket
  }

  const first = await subscribe()
  membershipStatus = 503
  const closed = once(first, 'close', { signal: AbortSignal.timeout(3000) })
  await revalidateSocket([...wss.clients][0])
  assert.equal((await closed)[0], 1013)
  assert.equal(broadcastLocal(channel, { text: 'while unavailable' }), 0)

  const retry = await connect()
  const retryClosed = once(retry, 'close', { signal: AbortSignal.timeout(3000) })
  retry.send(JSON.stringify({ type: 'subscribe', channel }))
  assert.equal((await retryClosed)[0], 1013)

  membershipStatus = 200
  const restored = await subscribe()
  // This authorized device uses its gateway's clock, even if the sender's
  // clock is ahead. Keep the sender timestamp for client-side ordering.
  const senderTime = Date.now() + 10 * 60 * 1000
  const next = once(restored, 'message', { signal: AbortSignal.timeout(5000) })
  assert.equal(broadcastLocal(channel, { text: 'after recovery' }, { ts: senderTime }), 1)
  const event = JSON.parse((await next)[0])
  assert.equal(event.payload.text, 'after recovery')
  assert.equal(event.ts, new Date(senderTime).toISOString())

  // A confirmed end of membership still removes only this subscription.
  membershipStatus = 404
  const revoked = once(restored, 'message', { signal: AbortSignal.timeout(3000) })
  await revalidateSocket([...wss.clients].find(socket => socket._rooms.has(channel)))
  assert.deepEqual(JSON.parse((await revoked)[0]), { type: 'error', error: 'FORBIDDEN_CHANNEL', channel })
  assert.equal(restored.readyState, WebSocket.OPEN)
  assert.equal(broadcastLocal(channel, { text: 'after leaving' }), 0)

  identityStatus = 503
  const identityClosed = once(restored, 'close', { signal: AbortSignal.timeout(3000) })
  await revalidateSocket([...wss.clients].find(client => client._authenticated))
  assert.equal((await identityClosed)[0], 1013)
  const duringOutage = new WebSocket(url, { headers: { Authorization: 'Bearer ' + token } })
  assert.equal((await once(duringOutage, 'close', { signal: AbortSignal.timeout(3000) }))[0], 1013)

  identityStatus = 200
  membershipStatus = 200
  const afterIdentityRecovery = await subscribe()
  identityStatus = 401
  const sessionClosed = once(afterIdentityRecovery, 'close', { signal: AbortSignal.timeout(3000) })
  await revalidateSocket([...wss.clients].find(client => client._rooms.has(channel)))
  assert.equal((await sessionClosed)[0], 4401)
  const revokedLogin = new WebSocket(url, { headers: { Authorization: 'Bearer ' + token } })
  assert.equal((await once(revokedLogin, 'close', { signal: AbortSignal.timeout(3000) }))[0], 4403)
})
