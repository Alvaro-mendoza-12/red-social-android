'use strict'

// Probe public DNS addresses explicitly: MagicDNS on the server/PC can otherwise
// make a broken Funnel look healthy by taking its private Tailscale route.
const https = require('node:https')
const net = require('node:net')
const ipaddr = require('ipaddr.js')

const resolvers = ['https://cloudflare-dns.com/dns-query', 'https://dns.google/resolve']
const routes = ['/health', '/ready', '/socket.io/?EIO=4&transport=polling']

function publicIpv4(value) {
  return net.isIP(value) === 4 && ipaddr.parse(value).range() === 'unicast'
}

function cleanOrigin(value) {
  const url = new URL(value)
  if (url.protocol !== 'https:' || url.username || url.password || url.port ||
      url.pathname !== '/' || url.search || url.hash || net.isIP(url.hostname)) {
    throw new Error('Expected an HTTPS hostname origin without credentials')
  }
  return url
}

function publicDnsAnswers(body, hostname) {
  const normalize = value => String(value || '').toLowerCase().replace(/\.$/, '')
  if (body?.Status !== 0 || body.TC === true || !body.Question?.some(
    question => question.type === 1 && normalize(question.name) === hostname,
  )) return []
  const owners = new Set([hostname])
  for (let hop = 0; hop < 8; hop++) {
    for (const answer of body.Answer || []) {
      if (answer.type === 5 && owners.has(normalize(answer.name))) owners.add(normalize(answer.data))
    }
  }
  return [...new Set((body.Answer || []).filter(answer => answer.type === 1 &&
    owners.has(normalize(answer.name)) && publicIpv4(answer.data)).map(answer => answer.data))]
}

function validBody(pathname, body) {
  try {
    if (pathname.startsWith('/socket.io/')) {
      if (!body.startsWith('0')) return false
      const handshake = JSON.parse(body.slice(1))
      return typeof handshake.sid === 'string' && handshake.sid.length > 0 &&
        Number.isFinite(handshake.pingInterval) && Number.isFinite(handshake.pingTimeout)
    }
    return JSON.parse(body)?.ok === true
  } catch { return false }
}

function probeAddress(origin, address, pathname, timeoutMs = 5_000) {
  // SNI and certificate validation still use the original hostname, never the IP.
  return new Promise(resolve => {
    const started = performance.now()
    let settled = false
    const finish = value => {
      if (settled) return
      settled = true
      resolve({ address, pathname, elapsedMs: Math.round(performance.now() - started), ...value })
    }
    const request = https.get(new URL(pathname, origin), {
      agent: false, family: 4, autoSelectFamily: false,
      servername: origin.hostname, rejectUnauthorized: true,
      signal: AbortSignal.timeout(timeoutMs),
      lookup: (_host, options, callback) => options.all
        ? callback(null, [{ address, family: 4 }]) : callback(null, address, 4),
    }, response => {
      const chunks = []
      let bytes = 0
      response.on('data', chunk => {
        bytes += chunk.length
        if (bytes > 65_536) request.destroy(new Error('RESPONSE_TOO_LARGE'))
        else chunks.push(chunk)
      })
      response.on('error', () => finish({ ok: false, error: 'BODY_READ_FAILED' }))
      response.on('end', () => finish({
        status: response.statusCode,
        ok: response.statusCode === 200 && validBody(pathname, Buffer.concat(chunks).toString('utf8')),
      }))
    })
    // Never print raw exception messages, response bodies, sessions or tokens.
    request.on('error', error => finish({ ok: false, error: String(error.code || 'CONNECTION_FAILED') }))
  })
}

async function verifyPublicIngress({ baseUrl, fetchImpl = fetch, probeImpl = probeAddress,
  dnsTimeoutMs = 4_000, probeTimeoutMs = 5_000 }) {
  const origin = cleanOrigin(baseUrl)
  const observations = await Promise.all(resolvers.map(async resolver => {
    const query = new URL(resolver)
    query.searchParams.set('name', origin.hostname)
    query.searchParams.set('type', 'A')
    try {
      const response = await fetchImpl(query, {
        headers: { accept: 'application/dns-json' }, redirect: 'error',
        signal: AbortSignal.timeout(dnsTimeoutMs),
      })
      const addresses = response.ok ? publicDnsAnswers(await response.json(), origin.hostname) : []
      return { resolver: query.hostname, addresses }
    } catch { return { resolver: query.hostname, addresses: [] } }
  }))
  const resolved = [...new Set(observations.flatMap(item => item.addresses))]
  const addresses = resolved.slice(0, 4)
  const checks = (await Promise.all(addresses.map(address => Promise.all(routes.map(
    pathname => probeImpl(origin, address, pathname, probeTimeoutMs),
  ))))).flat()
  const reachable = addresses.some(address => checks.filter(check => check.address === address).every(check => check.ok))
  return {
    ok: reachable && resolved.length <= 4 && checks.every(check => check.ok),
    publicReachable: reachable, target: origin.hostname,
    scope: 'Forced public IPv4, verified hostname TLS, HTTP health/readiness and signaling handshake; not authenticated flows or media',
    observations, uncheckedAddresses: Math.max(0, resolved.length - 4), checks,
  }
}

if (require.main === module) {
  verifyPublicIngress({ baseUrl: process.env.SMOKE_API_BASE_URL }).then(result => {
    console.log(JSON.stringify(result, null, 2))
    if (!result.ok) process.exitCode = 1
  }).catch(() => {
    console.error('Public ingress verification failed; check the HTTPS origin and public DNS.')
    process.exitCode = 1
  })
}

module.exports = { publicIpv4, cleanOrigin, publicDnsAnswers, validBody, verifyPublicIngress }
