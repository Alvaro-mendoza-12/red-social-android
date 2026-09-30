const assert = require('node:assert/strict')
const test = require('node:test')
const { publicIpv4, cleanOrigin, publicDnsAnswers, validBody, verifyPublicIngress } = require('../scripts/verify-public-ingress')
const baseUrl = 'https://probe.tail123456.ts.net'
const hostname = new URL(baseUrl).hostname
const reply = addresses => ({ Status: 0, Question: [{ name: hostname + '.', type: 1 }],
  Answer: addresses.map(data => ({ name: hostname + '.', type: 1, data })) })
const fetchImpl = async () => ({ ok: true, json: async () => reply(['1.1.1.1', '8.8.8.8']) })
const probeImpl = async (_origin, address, pathname) => ({ address, pathname, status: 200, ok: true })

test('private, carrier-grade, loopback, reserved and malformed addresses cannot substitute for public ingress', () => {
  for (const ip of ['100.91.84.2', '192.168.1.1', '10.0.0.1', '172.16.0.1', '127.0.0.1',
    '0.0.0.0', '169.254.1.1', '203.0.113.1', '224.1.1.1', '::1', 'not-an-ip']) assert.equal(publicIpv4(ip), false, ip)
  assert.equal(publicIpv4('1.1.1.1'), true)
})

test('origins with credentials, paths, query, IPs or non-TLS schemes are rejected', () => {
  assert.equal(cleanOrigin(baseUrl).origin, baseUrl)
  for (const value of ['http://example.test', 'https://user:secret@example.test', baseUrl + '/private',
    baseUrl + '?token=secret', baseUrl + '#fragment', 'https://127.0.0.1', baseUrl + ':8443']) assert.throws(() => cleanOrigin(value))
})

test('DNS replies must answer the requested name and contain public addresses', () => {
  assert.deepEqual(publicDnsAnswers(reply(['100.91.84.2', '1.1.1.1', '1.1.1.1']), hostname), ['1.1.1.1'])
  for (const body of [{}, { ...reply(['1.1.1.1']), TC: true }, { ...reply(['1.1.1.1']), Status: 3 },
    { ...reply(['1.1.1.1']), Question: [{ name: 'unrelated.test', type: 1 }] }]) assert.deepEqual(publicDnsAnswers(body, hostname), [])
  assert.deepEqual(publicDnsAnswers({ ...reply([]), Answer: [{ name: 'unrelated.test', type: 1, data: '1.1.1.1' }] }, hostname), [])
})

test('CNAME chains are accepted only when connected to the requested hostname', () => {
  const body = reply([])
  body.Answer = [{ name: hostname, type: 5, data: 'public-edge.test.' },
    { name: 'public-edge.test.', type: 1, data: '1.1.1.1' }, { name: 'other.test', type: 1, data: '8.8.8.8' }]
  assert.deepEqual(publicDnsAnswers(body, hostname), ['1.1.1.1'])
})

test('health requires an OK body and signaling requires an actual handshake', () => {
  assert.equal(validBody('/health', '{"ok":true}'), true)
  assert.equal(validBody('/ready', '{"ok":false}'), false)
  assert.equal(validBody('/health', '<html>landing page</html>'), false)
  assert.equal(validBody('/socket.io/', '0{"sid":"not-logged","pingInterval":25000,"pingTimeout":20000}'), true)
  assert.equal(validBody('/socket.io/', '{"ok":true}'), false)
  assert.equal(validBody('/socket.io/', '0{"sid":"missing-pings"}'), false)
})

test('both public addresses and all three routes are checked without system DNS', async () => {
  const result = await verifyPublicIngress({ baseUrl, fetchImpl, probeImpl })
  assert.equal(result.ok, true)
  assert.equal(result.publicReachable, true)
  assert.equal(result.checks.length, 6)
  assert.ok(result.checks.every(check => publicIpv4(check.address)))
})

test('one broken public ingress is degraded even when another is reachable', async () => {
  const result = await verifyPublicIngress({ baseUrl, fetchImpl,
    probeImpl: async (origin, address, pathname) => ({ ...await probeImpl(origin, address, pathname), ok: address === '1.1.1.1' }) })
  assert.equal(result.ok, false)
  assert.equal(result.publicReachable, true)
})

test('private-only or unavailable DNS never falls back to a private host route', async () => {
  for (const resolver of [async () => ({ ok: true, json: async () => reply(['100.91.84.2']) }),
    async () => { throw new Error('unavailable resolver') }]) {
    const result = await verifyPublicIngress({ baseUrl, fetchImpl: resolver,
      probeImpl: async () => { assert.fail('must not reach private/system DNS') } })
    assert.equal(result.ok, false)
    assert.equal(result.publicReachable, false)
    assert.equal(result.checks.length, 0)
  }
})

test('one resolver failure is tolerated if the other returns public answers', async () => {
  const result = await verifyPublicIngress({ baseUrl, probeImpl, fetchImpl: async url => {
    if (url.hostname === 'dns.google') throw new Error('unavailable')
    return fetchImpl()
  } })
  assert.equal(result.ok, true)
})

test('oversized DNS answers remain bounded and do not claim complete verification', async () => {
  const result = await verifyPublicIngress({ baseUrl, probeImpl,
    fetchImpl: async () => ({ ok: true, json: async () => reply(['1.1.1.1', '8.8.8.8', '9.9.9.9', '8.8.4.4', '1.0.0.1']) }) })
  assert.equal(result.ok, false)
  assert.equal(result.uncheckedAddresses, 1)
  assert.equal(result.checks.length, 12)
})
