'use strict'

// Authenticated diagnostics only: no publishing, messages, money, room joins,
// scheduled-challenge creation, or personal content in the printed evidence.
const { withReadPathToken, cleanBaseUrl, responseItems } = require('./verify-social-read-paths')

async function verifyServiceReadPaths({ baseUrl, token, fetchImpl = fetch }) {
  const checks = []
  async function check(name, pathname, expectedStatus = 200, authenticated = true) {
    const start = performance.now()
    const response = await fetchImpl(`${baseUrl}${pathname}`, {
      headers: authenticated ? { Authorization: `Bearer ${token}` } : {},
      signal: AbortSignal.timeout(12_000),
    })
    const body = await response.json()
    const elapsedMs = Math.round(performance.now() - start)
    checks.push({ name, status: response.status, elapsedMs, items: responseItems(body).length,
      ok: response.status === expectedStatus && elapsedMs <= 3_000 })
    return body
  }
  await check('moments-feed', '/v1/data/moments/feed')
  await check('voice-rooms-list', '/v1/data/voice/rooms')
  await check('challenge-owned-catalog', '/v1/data/challenges/master/mine')
  await check('games-catalog', '/v1/data/playmatch/catalog')
  await check('live-capabilities', '/v1/data/live/capabilities')
  const lives = await check('live-directory', '/v1/data/live')
  await check('gift-catalog', '/rest/v1/virtual_gifts?select=id,name,price_diamonds&limit=50')
  await check('live-gifts-require-auth', '/v1/data/live/00000000-0000-4000-8000-000000000001/gifts', 401, false)
  await check('live-gifts-invalid-id', '/v1/data/live/not-a-uuid/gifts', 400)
  const live = responseItems(lives)[0]
  if (live?.id) await check('active-live-gift-history', `/v1/data/live/${encodeURIComponent(live.id)}/gifts`)
  return {
    ok: checks.every(item => item.ok), target: new URL(baseUrl).hostname,
    scope: 'HTTP reads and access controls; not two-device media or delivery certification',
    giftHistory: live ? 'active-live-read' : 'no-active-live; data recovery covered by isolated tests', checks,
  }
}

if (require.main === module) {
  ;(async () => {
    await withReadPathToken(async token => {
      const evidence = await verifyServiceReadPaths({ baseUrl: cleanBaseUrl(process.env.SMOKE_API_BASE_URL), token })
      console.log(JSON.stringify(evidence, null, 2))
      if (!evidence.ok) process.exitCode = 1
    })
  })().catch(() => {
    // Exception messages can contain credential-bearing URLs. Keep logs safe.
    console.error('Service read-path verification failed; inspect the protected server logs.')
    process.exitCode = 1
  })
}

module.exports = { verifyServiceReadPaths }
