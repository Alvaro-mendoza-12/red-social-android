import test from 'node:test'
import assert from 'node:assert/strict'
import { once } from 'node:events'
import { WebSocket, WebSocketServer } from 'ws'
import { createPublicSocketServer } from '../api/novvin-socket.js'

test('public socket forwards authentication frames without placing credentials in its URL', async () => {
  const upstream = new WebSocketServer({ port: 0, host: '127.0.0.1' })
  await once(upstream, 'listening')
  let destination
  const server = createPublicSocketServer((target, options) => {
    destination = target.toString()
    return new WebSocket(`ws://127.0.0.1:${upstream.address().port}`, options)
  })
  upstream.on('connection', socket => socket.on('message', data => socket.send(data)))
  server.listen(0, '127.0.0.1'); await once(server, 'listening')
  const client = new WebSocket(`ws://127.0.0.1:${server.address().port}/api/novvin-socket?channel=realtime`, { headers: { Origin: 'https://red-social-android.vercel.app' } })
  try {
    await once(client, 'open')
    const received = once(client, 'message')
    client.send(JSON.stringify({ type: 'authenticate', token: 'synthetic-token-for-relay' }))
    const [data] = await received
    assert.deepEqual(JSON.parse(data.toString()), { type: 'authenticate', token: 'synthetic-token-for-relay' })
    assert.equal(destination, 'wss://conectate.tail9b267c.ts.net/realtime/')
    assert.ok(!destination.includes('synthetic-token'))
  } finally {
    client.terminate(); for (const socket of upstream.clients) socket.terminate()
    await Promise.all([new Promise(resolve => upstream.close(resolve)), new Promise(resolve => server.close(resolve))])
  }
})

test('untrusted origins and arbitrary upstream channels cannot open a relay', async () => {
  let calls = 0
  const server = createPublicSocketServer(() => { calls++; throw Error('Unexpected upstream') })
  server.listen(0, '127.0.0.1'); await once(server, 'listening')
  try {
    for (const [origin, channel] of [['https://attacker.test', 'realtime'], ['https://red-social-android.vercel.app', 'https://attacker.test']]) {
      const client = new WebSocket(`ws://127.0.0.1:${server.address().port}/api/novvin-socket?channel=${encodeURIComponent(channel)}`, { headers: { Origin: origin } })
      const [error] = await once(client, 'error')
      assert.match(error.message, /403/)
    }
    assert.equal(calls, 0)
    const response = await fetch(`http://127.0.0.1:${server.address().port}/api/novvin-socket`)
    assert.equal(response.status, 426)
  } finally { await new Promise(resolve => server.close(resolve)) }
})

test('server revocation codes reach the browser so revoked sessions are not reconnected', async () => {
  const upstream = new WebSocketServer({ port: 0, host: '127.0.0.1' }); await once(upstream, 'listening')
  const server = createPublicSocketServer((_target, options) => new WebSocket(`ws://127.0.0.1:${upstream.address().port}`, options))
  upstream.on('connection', socket => socket.close(4003))
  server.listen(0, '127.0.0.1'); await once(server, 'listening')
  const client = new WebSocket(`ws://127.0.0.1:${server.address().port}/api/novvin-socket?channel=realtime`, { headers: { Origin: 'https://red-social-android.vercel.app' } })
  try { const [code] = await once(client, 'close'); assert.equal(code, 4003) }
  finally { client.terminate(); for (const socket of upstream.clients) socket.terminate(); await Promise.all([new Promise(resolve => upstream.close(resolve)), new Promise(resolve => server.close(resolve))]) }
})
