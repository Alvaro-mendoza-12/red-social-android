const assert = require('node:assert/strict')
const { randomBytes, randomUUID } = require('node:crypto')
const { once } = require('node:events')
const http = require('node:http')
const test = require('node:test')
const jwt = require('jsonwebtoken')
const WebSocket = require('ws')

test('a temporary membership service outage allows a device to reconnect and receive its chat', async (t) => {
  const user = randomUUID()
  const chat = randomUUID()
  const channel = 'chat:' + chat
  let membershipStatus = 200
  const api = http.createServer((req, res) => {
    if (req.url === '/v1/auth/me') {
      res.writeHead(200, { 'content-type': 'application/json' })
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
  const next = once(restored, 'message', { signal: AbortSignal.timeout(5000) })
  assert.equal(broadcastLocal(channel, { text: 'after recovery' }), 1)
  assert.equal(JSON.parse((await next)[0]).payload.text, 'after recovery')

  // A confirmed end of membership still removes only this subscription.
  membershipStatus = 404
  const revoked = once(restored, 'message', { signal: AbortSignal.timeout(3000) })
  await revalidateSocket([...wss.clients].find(socket => socket._rooms.has(channel)))
  assert.deepEqual(JSON.parse((await revoked)[0]), { type: 'error', error: 'FORBIDDEN_CHANNEL', channel })
  assert.equal(restored.readyState, WebSocket.OPEN)
  assert.equal(broadcastLocal(channel, { text: 'after leaving' }), 0)
})
