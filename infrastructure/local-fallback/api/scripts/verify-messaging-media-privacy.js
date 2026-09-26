// Isolated access-control gate. No production connection or user data is involved.
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const assert = require('node:assert/strict')
const express = require('express')

async function main() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'novvin-media-privacy-check-'))
  // Set before loading routes so every file operation stays in this private test root.
  process.env.STORAGE_ROOT = root
  process.env.STORAGE_ACCEL_PREFIX = ''
  process.env.NODE_ENV = 'test'
  const { objectFilePath, registerStorageRoutes } = require('../src/storage-routes')
  const app = express()
  app.use(express.json())
  const rows = new Map()
  const payload = Buffer.from('synthetic-offline-media-privacy-probe')
  const buckets = ['chat-media', 'group-media', 'community-media', 'group-audio']
  const objectName = 'audit-fixture/never-a-real-user.bin'
  let server
  try {
    for (const bucket of buckets) {
      const localPath = objectFilePath(bucket, objectName)
      assert.ok(localPath.startsWith(root + path.sep))
      fs.mkdirSync(path.dirname(localPath), { recursive: true })
      fs.writeFileSync(localPath, payload)
      rows.set(bucket, {
        bucket_id: bucket, name: objectName, owner_id: 'synthetic-owner',
        local_path: localPath, content_type: 'application/octet-stream',
      })
    }
    registerStorageRoutes(app, {
      pool: {
        async query(sql, params) {
          assert.match(sql, /FROM fallback_storage_objects/)
          assert.equal(params[1], objectName)
          return { rows: rows.has(params[0]) ? [rows.get(params[0])] : [] }
        },
      },
      authRequired(_req, res) { res.status(401).json({ error: 'TEST_UNAUTHENTICATED' }) },
    })
    app.use((_error, _req, res, _next) => res.status(500).json({ error: 'TEST_ROUTE_ERROR' }))
    server = await new Promise(resolve => {
      const listening = app.listen(0, '127.0.0.1', () => resolve(listening))
    })
    const results = []
    for (const bucket of buckets) {
      for (const prefix of ['public/', '']) {
        const response = await fetch(
          `http://127.0.0.1:${server.address().port}/storage/v1/object/${prefix}${bucket}/${objectName}`,
          { signal: AbortSignal.timeout(5_000) },
        )
        const body = Buffer.from(await response.arrayBuffer())
        const exposed = response.ok && body.equals(payload)
        const denied = [401, 403, 404].includes(response.status)
        results.push({ bucket, route: prefix ? 'public' : 'legacy', status: response.status, exposed, passed: denied })
      }
    }
    const passed = results.every(result => result.passed)
    console.log(JSON.stringify({ scope: 'isolated synthetic files; no production access', passed, results }, null, 2))
    if (!passed) process.exitCode = 1
  } finally {
    if (server) {
      server.closeAllConnections()
      await new Promise(resolve => server.close(resolve))
    }
    // Only the exact directory created by mkdtemp in this invocation is disposable.
    const resolved = fs.realpathSync(root)
    assert.equal(path.dirname(resolved), fs.realpathSync(os.tmpdir()))
    assert.ok(path.basename(resolved).startsWith('novvin-media-privacy-check-'))
    fs.rmSync(resolved, { recursive: true, force: true })
  }
}

main().catch(() => {
  console.error('MEDIA_PRIVACY_CHECK_ERROR: synthetic probe could not complete')
  process.exitCode = 1
})
