import { test } from 'node:test'
import assert from 'node:assert/strict'
import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import express from '../api/node_modules/express/index.js'
import { deliver } from './worker.mjs'

test('edge delivery uses the actual storage route, preserves access and falls back to Ubuntu', async t => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'novvin-r2-edge-'))
  t.after(() => fs.rm(root, { recursive: true, force: true }))
  process.env.STORAGE_ROOT = root
  process.env.STORAGE_READ_ENFORCE = 'false'
  const { registerStorageRoutes } = (await import('../api/src/storage-routes.js')).default
  const { createR2EdgeDelivery, edgeConfiguration } = (await import('../api/src/storage-r2-edge.js')).default
  const { revisionFilePath } = (await import('../api/src/storage-scan-coordinator.js')).default
  const bytes = Buffer.from('video delivery fixture')
  const revision = crypto.randomUUID()
  const file = revisionFilePath(root, revision)
  await fs.mkdir(path.dirname(file), { recursive: true })
  await fs.writeFile(file, bytes)
  const row = {
    id: crypto.randomUUID(), bucket_id: 'chat-media', name: 'owner/movie.mp4', content_type: 'video/mp4', size_bytes: bytes.length,
    local_path: file, object_revision: revision, scan_state: 'clean', scan_sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
    scan_bytes: bytes.length, scan_engine: 'fixture', scan_signature_version: '1', scan_at: new Date().toISOString(), scan_policy_sha256: 'a'.repeat(64),
  }
  const secret = 'b'.repeat(64)
  const config = edgeConfiguration({ R2_EDGE_SECRET: secret, R2_EDGE_TARGET: `https://${'c'.repeat(32)}.r2.cloudflarestorage.com/novvin-media` })
  let lookup = 0, hits = 0, absent = false, revoked = false, databaseFailure = false
  const pool = {
    async query(query) {
      if (typeof query === 'string') return { rows: [row] }
      lookup++
      assert.match(query.text, /state='ready'/)
      assert.equal(query.values[0], config.target)
      if (databaseFailure) throw new Error('fixture: DB unavailable')
      return { rows: [{ ready: 1 }] }
    },
  }
  const app = express()
  registerStorageRoutes(app, {
    pool, authRequired: (_req, _res, next) => next(), storageScanCoordinator: { enforced: true },
    storageObjectReadGuard: async req => ({
      allowed: !revoked && req.headers.authorization === 'Bearer fixture', private: true,
      ...(req.query.lease ? { viewOnce: true, byteStart: 0, byteEnd: 3 } : {}),
    }),
    storageR2EdgeDelivery: createR2EdgeDelivery(config),
  })
  app.use((error, _req, res, _next) => res.status(error.status || 500).json({ error: error.message }))
  const server = await new Promise(resolve => { const listener = app.listen(0, '127.0.0.1', () => resolve(listener)) })
  t.after(() => new Promise(resolve => server.close(resolve)))
  const url = `http://127.0.0.1:${server.address().port}/storage/v1/object/public/chat-media/owner/movie.mp4`
  const headers = { authorization: 'Bearer fixture' }
  const metadata = { size: bytes.length, customMetadata: { sha256: row.scan_sha256 } }
  const env = {
    R2_EDGE_SECRET: secret,
    MEDIA: {
      async get(_key, options) {
        hits++
        if (absent) return null
        const range = options?.range
        const body = range ? bytes.subarray(range.offset, range.offset + range.length) : bytes
        return { ...metadata, range, body: new Blob([body]).stream() }
      },
    },
  }
  const download = (init, suffix = '') => deliver(new Request(url + suffix, init), env)

  const anonymous = await download({ headers: { 'x-novvin-r2-probe': secret } })
  assert.equal(anonymous.status, 404)
  assert.equal(lookup, 0)
  assert.equal(hits, 0)
  await anonymous.text()

  const direct = await fetch(url, { headers: { ...headers, 'x-novvin-r2-probe': 'e'.repeat(64) } })
  assert.deepEqual(Buffer.from(await direct.arrayBuffer()), bytes)
  assert.equal(lookup, 0)

  const original = await download({ headers })
  assert.equal(original.status, 200)
  assert.equal(original.headers.get('content-type'), 'video/mp4')
  assert.equal(original.headers.get('cache-control'), 'private, no-store')
  assert.equal(original.headers.get('x-novvin-r2-key'), null)
  assert.deepEqual(Buffer.from(await original.arrayBuffer()), bytes)
  assert.equal(hits, 1)

  const head = await download({ method: 'HEAD', headers })
  assert.equal(head.status, 200)
  assert.equal(head.headers.get('content-length'), String(bytes.length))
  assert.equal((await head.arrayBuffer()).byteLength, 0)
  const range = await download({ headers: { ...headers, range: 'bytes=-4' } })
  assert.equal(range.status, 206)
  assert.equal(range.headers.get('content-range'), `bytes ${bytes.length - 4}-${bytes.length - 1}/${bytes.length}`)
  assert.deepEqual(Buffer.from(await range.arrayBuffer()), bytes.subarray(-4))
  assert.equal(hits, 2)

  const conditional = await download({ headers: { ...headers, 'if-none-match': original.headers.get('etag') } })
  assert.equal(conditional.status, 304)
  const invalidRange = await download({ headers: { ...headers, range: 'bytes=9000-' } })
  assert.equal(invalidRange.status, 416)
  await invalidRange.text()
  const once = await download({ headers: { ...headers, range: 'bytes=0-3' } }, '?lease=fixture')
  assert.equal(once.status, 206)
  assert.deepEqual(Buffer.from(await once.arrayBuffer()), bytes.subarray(0, 4))
  assert.equal(hits, 2)

  absent = true
  const missing = await download({ headers })
  assert.equal(missing.status, 200)
  assert.deepEqual(Buffer.from(await missing.arrayBuffer()), bytes)
  assert.equal(hits, 3)

  databaseFailure = true
  const databaseFallback = await download({ headers })
  assert.equal(databaseFallback.status, 200)
  assert.deepEqual(Buffer.from(await databaseFallback.arrayBuffer()), bytes)
  revoked = true
  const blocked = await download({ headers })
  assert.equal(blocked.status, 404)
  await blocked.text()
  assert.equal(hits, 3)
})
