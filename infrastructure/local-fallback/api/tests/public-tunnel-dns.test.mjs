import assert from 'node:assert/strict'
import test from 'node:test'
import { inspectTunnelDns } from '../../scripts/public-tunnel-dns.mjs'

const origin = 'https://expired-example.trycloudflare.com'
const hostname = new URL(origin).hostname
const reply = (status, name = hostname) => ({
  Status: status,
  Question: [{ name: name + '.', type: 1 }],
  ...(status === 0 ? { Answer: [{ type: 1, data: '192.0.2.1' }] } : {}),
})
const response = body => ({ ok: true, json: async () => body })

test('both independent resolvers must confirm the missing published hostname', async () => {
  const requested = []
  const result = await inspectTunnelDns(origin, { fetchImpl: async url => {
    requested.push(url)
    return response(reply(3))
  } })
  assert.equal(result.removed, true)
  assert.equal(new Set(requested.map(url => url.host)).size, 2)
  assert.ok(requested.every(url => url.searchParams.get('name') === hostname))
})

test('one live answer preserves the connector during DNS propagation', async () => {
  const result = await inspectTunnelDns(origin, {
    fetchImpl: async url => response(reply(url.host === 'dns.google' ? 3 : 0)),
  })
  assert.equal(result.removed, false)
})

test('timeouts and network errors never imply that the tunnel was removed', async () => {
  const result = await inspectTunnelDns(origin, {
    fetchImpl: async url => {
      if (url.host === 'dns.google') return response(reply(3))
      throw new Error('temporary connectivity failure')
    },
  })
  assert.equal(result.removed, false)
})

test('HTTP errors and SERVFAIL do not trigger a new public URL', async () => {
  for (const fetchImpl of [
    async () => ({ ok: false }),
    async () => response(reply(2)),
    async () => response({ Status: 0, Question: reply(0).Question }),
  ]) {
    assert.equal((await inspectTunnelDns(origin, { fetchImpl })).removed, false)
  }
})

test('malformed or unrelated resolver responses are unknown', async () => {
  for (const body of [{}, reply(3, 'different.trycloudflare.com'), { ...reply(3), TC: true }]) {
    assert.equal((await inspectTunnelDns(origin, { fetchImpl: async () => response(body) })).removed, false)
  }
})

test('a hung resolver is bounded and preserves the existing endpoint', async () => {
  const result = await inspectTunnelDns(origin, {
    timeoutMs: 30,
    fetchImpl: async (_url, { signal }) => new Promise((resolve, reject) => {
      const timer = setTimeout(() => resolve(response(reply(3))), 500)
      signal.addEventListener('abort', () => { clearTimeout(timer); reject(signal.reason) }, { once: true })
    }),
  })
  assert.equal(result.removed, false)
  assert.ok(result.observations.every(result => result.state === 'unknown'))
})

test('non-tunnel origins and credentials are rejected before any request', async () => {
  let calls = 0
  for (const invalid of ['http://localhost', 'https://example.com', origin + '/private', origin + '?token=value', 'https://user:secret@expired-example.trycloudflare.com']) {
    await assert.rejects(inspectTunnelDns(invalid, { fetchImpl: async () => { calls++ } }))
  }
  assert.equal(calls, 0)
})
