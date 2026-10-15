'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const crypto = require('node:crypto')
const jwt = require('jsonwebtoken')
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'novvin-guest-http-'))
process.env.STORAGE_ROOT = root
process.env.STORAGE_SCAN_ENFORCE = 'true'
process.env.STORAGE_READ_ENFORCE = 'true'
process.env.STORAGE_READ_ORIGINS = 'https://api.example.invalid'
process.env.PUBLIC_BASE_URL = 'https://api.example.invalid'
const express = require('express')
const { registerStorageRoutes } = require('../src/storage-routes')
const { revisionFilePath } = require('../src/storage-scan-coordinator')
const { createStorageObjectReadGuard } = require('../src/storage-read-access')
const SECRET = 'isolated-guest-http-fixture-not-production'
const ISSUER = 'conectate-local-fallback'
const AUTHOR = '11111111-1111-4111-8111-111111111111'
const CONTENT = '22222222-2222-4222-8222-222222222222'
const ORIGIN = 'https://api.example.invalid'

test('real HTTP guest GET, HEAD and Range deliver admitted clean public bytes and revoke immediately', async () => {
  const bytes = Buffer.from('clean public media fixture; not a real user file')
  const revision = crypto.randomUUID()
  const file = revisionFilePath(root, revision)
  fs.mkdirSync(path.dirname(file), { recursive: true })
  fs.writeFileSync(file, bytes)
  const row = { bucket_id: 'posts', name: AUTHOR + '/public.txt', owner_id: AUTHOR,
    content_type: 'text/plain', size_bytes: bytes.length, local_path: file,
    object_revision: revision, scan_state: 'clean',
    scan_sha256: crypto.createHash('sha256').update(bytes).digest('hex'), scan_bytes: bytes.length,
    scan_engine: 'synthetic-controller-fixture', scan_signature_version: '1',
    scan_at: new Date().toISOString(), scan_policy_sha256: 'a'.repeat(64) }
  const post = { id: CONTENT, user_id: AUTHOR, visibility: 'public', is_sensitive: false,
    media_url: ORIGIN + '/storage/v1/object/public/posts/' + row.name }
  let acquired = 0, released = 0
  const db = { async query(sql) {
    if (/^(BEGIN|COMMIT|ROLLBACK|SET LOCAL)/.test(sql)) return { rows: [] }
    if (sql.includes('fallback_storage_private_bindings') || sql.includes('fallback_storage_private_history')) return { rows: [] }
    if (sql.includes('fallback_storage_content_bindings')) return { rows: [{ source_schema: 'public', source_table: 'fallback_posts', record_id: CONTENT, access_kind: 'post' }] }
    if (sql.includes('FROM public."fallback_posts"')) return { rows: [{ row: post }] }
    if (sql.includes('fallback_storage_content_history')) return { rows: [] }
    if (sql.includes('FROM fallback_users')) return { rows: [{ id: AUTHOR, profile: {}, override_user_id: AUTHOR }] }
    if (sql.includes('fallback_deleted_identities') || sql.includes('fallback_profile_moderation') ||
        sql.includes('fallback_user_blocks') || sql.includes('fallback_follows')) return { rows: [] }
    if (sql.includes('to_regclass')) return { rows: [{ relation: null }] }
    throw Error('Unexpected SQL fixture query')
  }, release() { released += 1 } }
  const pool = { async query(sql) {
    assert.ok(sql.includes('fallback_storage_objects')); return { rows: [row] }
  }, async connect() { acquired += 1; return db } }
  const authRequired = async (_req, res) => res.status(401).json({ error: 'AUTH_REQUIRED' })
  const guard = createStorageObjectReadGuard({ pool, authRequired, origins: [ORIGIN],
    guestJwtSecret: SECRET, guestJwtIssuer: ISSUER })
  const app = express()
  registerStorageRoutes(app, { pool, authRequired, storageObjectReadGuard: guard,
    storageScanCoordinator: { enforced: true } })
  app.use((error, _req, res, _next) => res.status(error.status || 500).json({ error: error.code || 'TEST_DENIED' }))
  const server = await new Promise(resolve => {
    const instance = app.listen(0, '127.0.0.1', () => resolve(instance))
  })
  const token = jwt.sign({ sub: 'guest_12345678-1234-4321-8765-123456789abc', role: 'guest' }, SECRET,
    { issuer: ISSUER, audience: 'novvin-guests', expiresIn: '1h' })
  const headers = { authorization: 'Bearer ' + token }
  const url = `http://127.0.0.1:${server.address().port}/storage/v1/object/public/posts/${row.name}`
  try {
    const get = await fetch(url, { headers })
    assert.equal(get.status, 200)
    assert.deepEqual(Buffer.from(await get.arrayBuffer()), bytes)
    assert.equal(get.headers.get('cache-control'), 'private, no-store')
    const head = await fetch(url, { headers, method: 'HEAD' })
    assert.equal(head.status, 200); assert.equal((await head.arrayBuffer()).byteLength, 0)
    const range = await fetch(url, { headers: { ...headers, range: 'bytes=0-5' } })
    assert.equal(range.status, 206)
    assert.deepEqual(Buffer.from(await range.arrayBuffer()), bytes.subarray(0, 6))
    assert.equal((await fetch(url)).status, 401)
    assert.equal((await fetch(url, { headers: { authorization: 'Bearer invalid-token' } })).status, 401)
    post.visibility = 'private'
    assert.equal((await fetch(url, { headers: { ...headers, range: 'bytes=0-5' } })).status, 404)
    post.visibility = 'public'; post.is_deleted = true
    assert.equal((await fetch(url, { headers })).status, 404)
    post.is_deleted = false; row.scan_state = 'rejected'
    assert.equal((await fetch(url, { headers })).status, 404)
    assert.equal(acquired, released)
  } finally {
    await new Promise(resolve => server.close(resolve))
    assert.equal(path.dirname(path.resolve(root)), path.resolve(os.tmpdir()))
    assert.ok(path.basename(root).startsWith('novvin-guest-http-'))
    fs.rmSync(root, { recursive: true, force: true })
  }
})
