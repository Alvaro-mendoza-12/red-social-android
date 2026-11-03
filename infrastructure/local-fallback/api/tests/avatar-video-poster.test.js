'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const path = require('node:path')
const os = require('node:os')
const crypto = require('node:crypto')
const { promisify } = require('node:util')
const execFile = promisify(require('node:child_process').execFile)
const sharp = require('sharp')

test('a video avatar produces a bounded WebP poster without altering its playable original', async t => {
  try { await execFile('ffmpeg', ['-version']) }
  catch (error) { if (error.code === 'ENOENT') return t.skip('ffmpeg required for the media integration test'); throw error }
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'novvin-avatar-poster-'))
  t.after(() => {
    assert.ok(path.resolve(directory).startsWith(path.resolve(os.tmpdir()) + path.sep + 'novvin-avatar-poster-'))
    return fs.rm(directory, { recursive: true, force: true })
  })
  process.env.STORAGE_ROOT = directory
  process.env.STORAGE_READ_ENFORCE = 'false'
  const { createImageVariant, requestedImageVariant, registerStorageRoutes } = require('../src/storage-routes')
  const video = path.join(directory, 'avatar.mp4')
  const poster = path.join(directory, 'poster.webp')
  await execFile('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i',
    'color=c=blue:s=320x480:r=10', '-t', '0.5', '-c:v', 'libx264', '-pix_fmt', 'yuv420p',
    '-threads', '1', '-movflags', '+faststart', video], { timeout: 10000 })
  const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex')
  const before = hash(await fs.readFile(video))
  const variant = requestedImageVariant({ width: 160, poster: '1' }, 'video/mp4', 'avatars')
  assert.equal(variant.poster, true)
  await createImageVariant(video, poster, variant)
  const metadata = await sharp(poster).metadata()
  assert.equal(metadata.format, 'webp')
  assert.equal(metadata.width, 160)
  assert.equal(metadata.height, 240)
  assert.equal(hash(await fs.readFile(video)), before)
  assert.equal(requestedImageVariant({ width: 160, poster: '1' }, 'video/mp4', 'chat-media'), null)

  const revision = crypto.randomUUID()
  const { revisionFilePath } = require('../src/storage-scan-coordinator')
  const stored = revisionFilePath(directory, revision)
  await fs.mkdir(path.dirname(stored), { recursive: true })
  await fs.copyFile(video, stored)
  const bytes = await fs.readFile(stored)
  const row = { id: crypto.randomUUID(), bucket_id: 'avatars', name: 'owner/avatar.mp4',
    object_revision: revision, local_path: stored, content_type: 'video/mp4', size_bytes: bytes.length,
    scan_state: 'clean', scan_sha256: before, scan_bytes: bytes.length, scan_engine: 'fixture',
    scan_signature_version: '1', scan_at: new Date().toISOString(), scan_policy_sha256: 'a'.repeat(64) }
  const app = require('express')()
  registerStorageRoutes(app, {
    pool: { query: async () => ({ rows: [row] }) },
    authRequired: (_req, _res, next) => next(),
    storageScanCoordinator: { enforced: true },
    storageObjectReadGuard: async req => ({ allowed: req.headers.authorization === 'Bearer fixture', private: true }),
  })
  app.use((error, _req, res, _next) => res.status(error.status || 500).json({ error: error.message }))
  const server = await new Promise(resolve => { const listener = app.listen(0, '127.0.0.1', () => resolve(listener)) })
  t.after(() => new Promise(resolve => server.close(resolve)))
  const url = `http://127.0.0.1:${server.address().port}/storage/v1/object/public/avatars/${row.name}`
  const query = '?width=160&quality=78&format=webp&poster=1'
  const denied = await fetch(url + query)
  assert.equal(denied.status, 404)
  await denied.body.cancel()
  const headers = { Authorization: 'Bearer fixture' }
  const response = await fetch(url + query, { headers })
  assert.equal(response.status, 200)
  assert.equal(response.headers.get('content-type'), 'image/webp')
  assert.equal((await sharp(Buffer.from(await response.arrayBuffer())).metadata()).width, 160)
  const original = await fetch(url, { headers: { ...headers, Range: 'bytes=0-3' } })
  assert.equal(original.status, 206)
  assert.equal(original.headers.get('content-type'), 'video/mp4')
  assert.deepEqual(Buffer.from(await original.arrayBuffer()), bytes.subarray(0, 4))

  const gif = path.join(directory, 'animated.gif')
  const animation = path.join(directory, 'animated.webp')
  await execFile('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i',
    'testsrc=size=320x240:rate=2', '-t', '1', '-threads', '1', gif], { timeout: 10000 })
  const animatedVariant = requestedImageVariant({ width: 160, animated: '1' }, 'image/gif', 'avatars')
  assert.equal(animatedVariant.animated, true)
  await createImageVariant(gif, animation, animatedVariant)
  const animatedMetadata = await sharp(animation, { animated: true }).metadata()
  assert.equal(animatedMetadata.width, 160)
  assert.ok(animatedMetadata.pages > 1)
  assert.equal(animatedMetadata.pageHeight, 120)
  assert.deepEqual(animatedMetadata.delay, (await sharp(gif, { animated: true }).metadata()).delay)
  assert.equal(requestedImageVariant({ width: 160, animated: '1' }, 'image/gif', 'chat-media'), null)

  const legacyJpeg = path.join(directory, 'legacy.jpg')
  const jpegPreview = path.join(directory, 'legacy.webp')
  const encoded = await sharp({ create: { width: 320, height: 240, channels: 3, background: '#4080c0' } }).jpeg({ progressive: false }).toBuffer()
  const scan = encoded.indexOf(Buffer.from([0xff, 0xda]))
  assert.ok(scan > 0)
  encoded[scan + 2 + encoded.readUInt16BE(scan + 2) - 3] = 1
  await fs.writeFile(legacyJpeg, encoded)
  await assert.rejects(sharp(legacyJpeg).resize(160).toBuffer(), /Invalid SOS parameters/)
  await createImageVariant(legacyJpeg, jpegPreview, { width: 160, quality: 78, format: 'webp' })
  assert.equal((await sharp(jpegPreview).metadata()).width, 160)
  assert.equal(hash(await fs.readFile(legacyJpeg)), hash(encoded))
})
