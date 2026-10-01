'use strict'

// Isolated account, bounded reads, forced public IPv4 and normal TLS/SNI.
// Never print credentials, URLs of personal objects, or returned profile data.
const https = require('node:https')
const crypto = require('node:crypto')
const fs = require('node:fs')
const assert = require('node:assert/strict')
const { performance } = require('node:perf_hooks')
const origin = new URL(process.env.MOBILE_AUDIT_ORIGIN || 'https://conectate.tail9b267c.ts.net')
assert.equal(origin.protocol, 'https:')
assert.equal(origin.username + origin.password + origin.search + origin.hash, '')
assert.equal(origin.pathname, '/')

async function publicAddress() {
  const answer = await fetch(`https://cloudflare-dns.com/dns-query?name=${encodeURIComponent(origin.hostname)}&type=A`,
    { headers: { accept: 'application/dns-json' }, signal: AbortSignal.timeout(8000) }).then(r => r.json())
  const addresses = (answer.Answer || []).filter(r => r.type === 1).map(r => r.data).filter(ip => {
    const p = ip.split('.').map(Number)
    return p.length === 4 && p.every(n => Number.isInteger(n) && n >= 0 && n <= 255) &&
      p[0] !== 0 && p[0] !== 10 && p[0] !== 127 && p[0] < 224 &&
      !(p[0] === 192 && p[1] === 168) && !(p[0] === 172 && p[1] >= 16 && p[1] <= 31) &&
      !(p[0] === 169 && p[1] === 254) && !(p[0] === 100 && p[1] >= 64 && p[1] <= 127)
  })
  assert(addresses.length, 'No public IPv4 for canonical origin')
  return addresses[0]
}

async function main() {
  const address = await publicAddress()
  const agent = new https.Agent({ keepAlive: true, maxSockets: 2, lookup: (_host, opts, cb) => {
    if (opts.all) cb(null, [{ address, family: 4 }]); else cb(null, address, 4)
  } })
  const checks = []
  async function request(path, token, body, expected = 200, name = path) {
    const started = performance.now()
    const response = await new Promise((resolve, reject) => {
      const payload = body == null ? null : JSON.stringify(body)
      const req = https.request(new URL(path, origin), { agent, timeout: 10_000,
        method: payload == null ? 'GET' : 'POST', headers: {
          ...(token ? { authorization: `Bearer ${token}` } : {}),
          ...(payload ? { 'content-type': 'application/json', 'content-length': Buffer.byteLength(payload) } : {}),
        },
      }, res => {
        let text = ''
        res.setEncoding('utf8')
        res.on('data', chunk => { text += chunk; if (text.length > 4 * 1024 * 1024) req.destroy(Error('Response exceeds audit bound')) })
        res.on('end', () => {
          const appMs = Number.parseFloat(String(res.headers['x-response-time'] || ''))
          try { resolve({ status: res.statusCode, body: text ? JSON.parse(text) : {}, appMs: Number.isFinite(appMs) ? appMs : null }) }
          catch { resolve({ status: res.statusCode, body: {}, invalidJson: true }) }
        })
        res.on('error', reject)
      })
      req.on('timeout', () => req.destroy(Error('Audit request timeout')))
      req.on('error', reject)
      req.end(payload)
    })
    checks.push({ name, status: response.status, expected, ms: Math.round(performance.now() - started),
      ok: response.status === expected && !response.invalidJson, appMs: response.appMs ?? null,
      ...(response.invalidJson ? { invalidJson: true } : {}),
      ...(response.status >= 400 && /^[A-Z][A-Z0-9_:]{1,79}$/.test(response.body?.error || '') ? { errorCode: response.body.error } : {}) })
    return response.body
  }
  let token
  let cleanupVerified = false
  let failure = false
  try {
    const auth = await request('/v1/auth/register', null, {
      email: `module-fluency.${crypto.randomUUID()}@example.invalid`,
      password: `QA-${crypto.randomUUID()}-only`, displayName: 'Auditoría temporal',
    }, 201, 'isolated-registration')
    token = auth.accessToken
    assert(token, 'Isolated registration did not issue token')
    const paths = [
      ['health', '/health'], ['ready', '/ready'], ['account', '/v1/auth/me'],
      ['profile', '/v1/data/profile/me'], ['profile-search', '/v1/data/profiles/search?q=auditoria&limit=5'],
      ['notifications', '/v1/data/notifications?limit=10'], ['friends', '/v1/data/friends'],
      ['messages-inbox', '/v1/data/messages/inbox'], ['saved-messages', '/v1/data/messages/saved'],
      ['communities', '/v1/data/messages/communities/explore?limit=5'],
      ['social-feed', '/v1/data/feed?limit=5'], ['moments', '/v1/data/moments/feed?limit=5'],
      ['saved-collections', '/v1/data/saved-collections'],
      ['videos', '/v1/data/videos?limit=5'], ['video-collections', '/v1/data/videos/collections'],
      ['video-comment-search', '/v1/data/videos?q=musica&limit=5'],
      ['ranking-followers', '/v1/data/profiles/ranking?metric=followers&limit=100'],
      ['ranking-posts', '/v1/data/profiles/ranking?metric=posts&limit=100'],
      ['watch-later', '/v1/data/videos/watch-later/list'], ['video-history', '/v1/data/videos/history/list'],
      ['voice-rooms', '/v1/data/voice/rooms'], ['live-capabilities', '/v1/data/live/capabilities'],
      ['live-directory', '/v1/data/live'], ['live-replays', '/v1/data/live/replays'],
      ['challenge-directory', '/v1/data/challenges/active'], ['owned-challenges', '/v1/data/challenges/master/mine'],
      ['games-capabilities', '/v1/data/playmatch/capabilities'], ['games-catalog', '/v1/data/playmatch/catalog'],
      ['games-connections', '/v1/data/playmatch/connections'], ['games-matches', '/v1/data/playmatch/matches'],
      ['socialization-capabilities', '/v1/data/socialization/capabilities'],
      ['algorithm-capabilities', '/v1/data/algorithm/capabilities'], ['algorithm-profiles', '/v1/data/algorithm/profiles'],
      ['creator-analytics', '/v1/data/creator/analytics/content'], ['music-categories', '/v1/data/music/catalog/categories'],
      ['music-library', '/v1/data/music/my'], ['notification-digest', '/v1/data/notification-digest/preferences'],
    ]
    for (const [name, path] of paths) await request(path, token, null, 200, name)
    const ranking = await request('/v1/data/profiles/ranking?metric=following&limit=100', token, null, 200, 'ranking-own-position')
    assert(Array.isArray(ranking.items) && ranking.items.length <= 100)
    assert(Number.isInteger(ranking.myRank) && ranking.myRank > 0 && ranking.myRank <= ranking.total)
    assert(ranking.myProfile?.id, 'Ranking did not include the isolated viewer position')
    const capabilities = await request('/v1/data/socialization/capabilities', token, null, 200, 'socialization-feature-state')
    const socializationEnabled = capabilities.capabilities?.enabled === true
    await request('/v1/data/socialization/preferences', token, null, socializationEnabled ? 200 : 404,
      socializationEnabled ? 'socialization-preferences' : 'socialization-disabled-as-configured')
    await request('/v1/data/admin/users', token, null, 403, 'admin-access-denied')
    await request('/v1/data/messages/inbox', null, null, 401, 'messages-require-auth')
    await request('/v1/data/live/00000000-0000-4000-8000-000000000001/gifts', null, null, 401, 'live-gifts-require-auth')
    // No actual mail: unknown account and invalid address retain generic responses.
    await request('/v1/auth/password-reset/request', null, { email: `unknown-${crypto.randomUUID()}@example.invalid`, codeFormat: 'numeric' }, 200, 'unknown-email-recovery')
    await request('/v1/auth/password-reset/request', null, { email: 'invalid', codeFormat: 'numeric' }, 200, 'invalid-email-recovery')
  } catch { failure = true }
  finally {
    if (token) {
      try {
        await request('/rest/v1/rpc/delete_own_account', token, {}, 200, 'fixture-delete')
        await request('/v1/auth/me', token, null, 401, 'deleted-session-invalid')
        cleanupVerified = checks.slice(-2).every(c => c.ok)
      } catch { failure = true }
    }
    agent.destroy()
  }
  const durations = checks.filter(c => c.expected === 200 && !c.name.includes('recovery')).map(c => c.ms).sort((a, b) => a - b)
  const report = { ok: !failure && cleanupVerified && checks.every(c => c.ok), cleanupVerified,
    scope: 'Forced public HTTPS/SNI, HTTP/1.1 reads with one isolated account; not every UI option or capacity certification',
    checks, p95Ms: durations[Math.max(0, Math.ceil(durations.length * .95) - 1)],
    overOneSecond: checks.filter(c => c.ms > 1000).map(c => ({ name: c.name, ms: c.ms })),
    actualRecoveryEmailsSent: 0,
  }
  if (process.env.MOBILE_AUDIT_OUTPUT) fs.writeFileSync(process.env.MOBILE_AUDIT_OUTPUT, JSON.stringify(report, null, 2))
  console.log(JSON.stringify(report, null, 2))
  if (!report.ok) process.exitCode = 1
}
main().catch(() => { console.error('Module fluency audit failed; credentials and returned content withheld'); process.exitCode = 1 })
