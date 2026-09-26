const { test } = require('node:test')
const assert = require('node:assert/strict')
const { verifyServiceReadPaths } = require('../scripts/verify-service-read-paths')

test('service smoke never mutates data or prints identities/tokens', async () => {
  const paths = []
  const result = await verifyServiceReadPaths({ baseUrl: 'https://example.test', token: 'private-token',
    fetchImpl: async (url, options) => {
      paths.push(new URL(url).pathname)
      assert.equal(options.method, undefined)
      const status = url.endsWith('/gifts') ? (options.headers.Authorization ? 400 : 401) : 200
      return { status, json: async () => url.includes('/moments/') ? { items: [{ id: 'private-content' }] } : { items: [] } }
    },
  })
  assert.equal(result.ok, true)
  assert.equal(result.checks.length, 9)
  assert.equal(paths.includes('/v1/data/challenges/active'), false)
  assert.equal(JSON.stringify(result).includes('private-content'), false)
  assert.equal(JSON.stringify(result).includes('private-token'), false)
})

test('an HTTP failure makes the readiness evidence fail', async () => {
  const result = await verifyServiceReadPaths({ baseUrl: 'https://example.test', token: 'private-token',
    fetchImpl: async () => ({ status: 503, json: async () => ({ error: 'SERVICE_UNAVAILABLE' }) }),
  })
  assert.equal(result.ok, false)
})
