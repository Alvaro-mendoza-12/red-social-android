const assert = require('assert/strict')
const fs = require('fs')
const os = require('os')
const path = require('path')
const {
  collectSystemHealth,
  normalizedBaseUrl,
  releaseMetadataFromText,
} = require('../src/system-health')

async function run() {
  assert.equal(normalizedBaseUrl(' https://api.example.test/// '), 'https://api.example.test')
  assert.equal(normalizedBaseUrl('not-a-url'), '')
  assert.deepEqual(
    releaseMetadataFromText('release=novvin-42\ncommit=abc123\ndeployed_at_utc=2026-07-26T00:00:00Z\n'),
    {
      release: 'novvin-42',
      commit: 'abc123',
      deployed_at_utc: '2026-07-26T00:00:00Z',
    },
  )

  const storageRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'novvin-health-'))
  const metadataPath = path.join(storageRoot, 'release')
  fs.writeFileSync(metadataPath, 'release=novvin-test\ncommit=2b2c81d\n')
  const fetchImpl = async (url) => ({
    ok: true,
    status: 200,
    async json() {
      if (String(url).includes('signaling')) {
        return {
          ok: true,
          turnConfigured: true,
          namespaces: { voice: '/', call: '/call', live: '/live' },
        }
      }
      return { ok: true }
    },
  })

  try {
    const healthy = await collectSystemHealth({
      pool: { async query() { return { rows: [{ healthy: 1 }] } } },
      storageRoot,
      realtimeUrl: 'http://realtime:3002',
      signalingUrl: 'http://signaling:3003',
      fetchImpl,
      releaseMetadataPath: metadataPath,
      now: () => Date.parse('2026-07-26T12:00:00.000Z'),
    })
    assert.equal(healthy.ok, true)
    assert.equal(healthy.status, 'operational')
    assert.equal(healthy.release.release, 'novvin-test')
    assert.equal(healthy.release.commit, '2b2c81d')
    assert.deepEqual(Object.keys(healthy.components), [
      'api',
      'database',
      'storage',
      'realtime',
      'voice',
      'live',
    ])
    for (const component of Object.values(healthy.components)) {
      assert.equal(component.ok, true)
      assert.equal(typeof component.latencyMs, 'number')
    }

    const degraded = await collectSystemHealth({
      pool: { async query() { return { rows: [{ healthy: 1 }] } } },
      storageRoot,
      realtimeUrl: 'http://realtime:3002',
      signalingUrl: 'http://signaling:3003',
      fetchImpl: async (url) => ({
        ok: true,
        status: 200,
        async json() {
          return String(url).includes('signaling')
            ? { ok: true, turnConfigured: false, namespaces: { voice: '/', live: '/live' } }
            : { ok: true }
        },
      }),
      releaseMetadataPath: metadataPath,
    })
    assert.equal(degraded.ok, false)
    assert.equal(degraded.status, 'degraded')
    assert.equal(degraded.components.voice.status, 'turn_not_configured')
    assert.equal(degraded.components.live.status, 'turn_not_configured')
    assert.equal(degraded.components.database.ok, true)
  } finally {
    fs.rmSync(storageRoot, { recursive: true, force: true })
  }

  console.log('system health contract tests passed')
}

run().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
