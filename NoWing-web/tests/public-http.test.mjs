import test from 'node:test'
import assert from 'node:assert/strict'
import { once } from 'node:events'
import { request } from 'node:http'
import { createSeoCandidateServer } from '../scripts/serve-seo-candidate.mjs'
import { publicRoutes, site } from '../scripts/public-seo.mjs'

test('public brand graphics allow email embedding without relaxing page isolation', async () => {
  const server = createSeoCandidateServer().listen(0, '127.0.0.1')
  await once(server, 'listening')
  const base = 'http://127.0.0.1:' + server.address().port
  try {
    for (const asset of ['/novvin-logo.png', '/novvin-share.png']) {
      const response = await fetch(base + asset)
      assert.equal(response.status, 200)
      assert.equal(response.headers.get('cross-origin-resource-policy'), 'cross-origin')
    }
    for (const route of ['/', '/app/messages']) {
      assert.equal((await fetch(base + route)).headers.get('cross-origin-resource-policy'), 'same-origin')
    }
  } finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)) }
})

test('all built public routes and canonical redirects work over isolated HTTP', async () => {
  const server = createSeoCandidateServer().listen(0, '127.0.0.1')
  await once(server, 'listening')
  const base = 'http://127.0.0.1:' + server.address().port
  try {
    for (const [route, file] of publicRoutes) {
      const response = await fetch(base + route), html = await response.text()
      assert.equal(response.status, 200, route)
      assert.ok(!response.headers.get('x-robots-tag')?.includes('noindex'), route)
      assert.ok(html.includes('rel="canonical" href="' + site + route + '"'), route)
      const duplicate = await fetch(base + '/' + file, { redirect: 'manual' })
      assert.equal(duplicate.status, 308); assert.equal(duplicate.headers.get('location'), route)
      if (route !== '/') {
        const trailing = await fetch(base + route + '/', { redirect: 'manual' })
        assert.equal(trailing.status, 308); assert.equal(trailing.headers.get('location'), route)
        const canonical = await fetch(base + route, { redirect: 'manual' })
        assert.equal(canonical.status, 200, 'The redirect must never loop: ' + route)
      }
    }
    const trailing = await fetch(base + '/mensajes/?utm_source=qa', { redirect: 'manual' })
    assert.equal(trailing.headers.get('location'), '/mensajes?utm_source=qa')
    assert.equal((await fetch(base + '/unknown-qa')).status, 404)
    assert.equal((await fetch(base + '/v1/data/profile/me')).status, 401)
    const shell = await fetch(base + '/app/messages')
    assert.match(shell.headers.get('x-robots-tag'), /noindex/)
    assert.match(await shell.text(), /type="module"/)
    const profileAlias = await fetch(base + '/p/example')
    assert.match(profileAlias.headers.get('x-robots-tag'), /noindex/)
    assert.match(await profileAlias.text(), /type="module"/)
    const robots = await fetch(base + '/robots.txt')
    assert.match(await robots.text(), /Sitemap:/)
    const legacy = path => new Promise((resolve, reject) => {
      const req = request(base + path, { headers: { Host: 'red-social-android.vercel.app' } }, res => {
        res.resume(); resolve({ status: res.statusCode, location: res.headers.location })
      }); req.on('error', reject); req.end()
    })
    const migrated = await legacy('/mensajes?utm_source=qa')
    assert.equal(migrated.status, 308)
    assert.equal(migrated.location, site + '/mensajes?utm_source=qa')
    const legacyApi = await legacy('/v1/data/profile/me')
    assert.equal(legacyApi.status, 401)
    assert.equal(legacyApi.location, undefined)
  } finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)) }
})
