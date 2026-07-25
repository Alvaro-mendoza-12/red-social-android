const assert = require('assert/strict')
const fs = require('fs')
const os = require('os')
const path = require('path')
const express = require('express')
const sharp = require('sharp')
const moduleStorageRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'conectate-storage-http-'))
process.env.STORAGE_ROOT = moduleStorageRoot
const {
  allowedBuckets,
  createImageVariant,
  etagMatches,
  inferContentType,
  registerStorageRoutes,
  requestedImageVariant,
  storageCacheControl,
  validateUpload,
} = require('../src/storage-routes')

async function expectRejected(promise, expectedMessage) {
  try {
    await promise
    assert.fail(`Expected rejection: ${expectedMessage}`)
  } catch (error) {
    assert.equal(error.message, expectedMessage)
  }
}

async function run() {
  assert.equal(allowedBuckets.has('chat-media'), true)
  assert.equal(allowedBuckets.has('report-evidence'), true)
  assert.equal(allowedBuckets.has('arbitrary-user-bucket'), false)

  const gif = Buffer.from('474946383961000000000000', 'hex')
  assert.equal(inferContentType(gif, 'sticker.gif'), 'image/gif')
  assert.equal(
    (await validateUpload({
      bucket: 'chat-media',
      objectPath: 'user/sticker.gif',
      buffer: gif,
      contentType: 'image/gif',
    })).contentType,
    'image/gif',
  )

  const pdf = Buffer.from('%PDF-1.7\nhello', 'ascii')
  assert.equal(
    (await validateUpload({
      bucket: 'group-media',
      objectPath: 'group/file.pdf',
      buffer: pdf,
      contentType: 'application/octet-stream',
    })).contentType,
    'application/pdf',
  )

  const docx = Buffer.from('504b030414000000000000000000', 'hex')
  assert.equal(
    (await validateUpload({
      bucket: 'community-media',
      objectPath: 'community/file.docx',
      buffer: docx,
      contentType: 'application/octet-stream',
    })).contentType,
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  )

  const m4a = Buffer.from('00000018667479704d34412000000000', 'hex')
  assert.equal(
    (await validateUpload({
      bucket: 'group-audio',
      objectPath: 'group/voice.m4a',
      buffer: m4a,
      contentType: 'audio/mp4',
    })).contentType,
    'audio/mp4',
  )

  const webm = Buffer.from('1a45dfa30000000000000000', 'hex')
  assert.equal(
    (await validateUpload({
      bucket: 'group-audio',
      objectPath: 'group/voice.webm',
      buffer: webm,
      contentType: 'audio/webm',
    })).contentType,
    'audio/webm',
  )

  const png = Buffer.from('89504e470d0a1a0a00000000', 'hex')
  assert.equal(
    (await validateUpload({
      bucket: 'report-evidence',
      objectPath: 'reports/user/evidence.png',
      buffer: png,
      contentType: 'image/png',
    })).contentType,
    'image/png',
  )
  const wav = Buffer.from('524946462400000057415645666d74201000000001000100401f0000401f0000010008006461746100000000', 'hex')
  assert.equal(
    (await validateUpload({
      bucket: 'post-comments-media',
      objectPath: 'comments/user/voice.wav',
      buffer: wav,
      contentType: 'audio/wav',
    })).contentType,
    'audio/wav',
  )
  assert.equal(
    (await validateUpload({
      bucket: 'video-comments-media',
      objectPath: 'comments/user/voice.wav',
      buffer: wav,
      contentType: 'audio/wav',
    })).contentType,
    'audio/wav',
  )
  await expectRejected(
    validateUpload({
      bucket: 'post-comments-media',
      objectPath: 'comments/user/image.png',
      buffer: png,
      contentType: 'image/png',
    }),
    'UNSUPPORTED_OR_MISMATCHED_MEDIA_TYPE',
  )
  await expectRejected(
    validateUpload({
      bucket: 'report-evidence',
      objectPath: 'reports/user/evidence.gif',
      buffer: gif,
      contentType: 'image/gif',
    }),
    'UNSUPPORTED_OR_MISMATCHED_MEDIA_TYPE',
  )
  await expectRejected(
    validateUpload({
      bucket: 'report-evidence',
      objectPath: 'reports/user/evidence.png',
      buffer: png,
      sizeBytes: 10 * 1024 * 1024 + 1,
      contentType: 'image/png',
    }),
    'UPLOAD_TOO_LARGE',
  )

  await expectRejected(
    validateUpload({
      bucket: 'arbitrary-user-bucket',
      objectPath: 'file.jpg',
      buffer: Buffer.from('ffd8ff00', 'hex'),
      contentType: 'image/jpeg',
    }),
    'BUCKET_NOT_ALLOWED',
  )
  await expectRejected(
    validateUpload({
      bucket: 'chat-media',
      objectPath: 'payload.pdf',
      buffer: Buffer.from('4d5a9000', 'hex'),
      contentType: 'application/pdf',
    }),
    'EXECUTABLE_UPLOAD_BLOCKED',
  )
  await expectRejected(
    validateUpload({
      bucket: 'chat-media',
      objectPath: 'fake.pdf',
      buffer: Buffer.from('ffd8ff000000000000000000', 'hex'),
      contentType: 'application/pdf',
    }),
    'UNSUPPORTED_OR_MISMATCHED_MEDIA_TYPE',
  )

  assert.deepEqual(
    requestedImageVariant({ width: '720', quality: '76', format: 'webp' }, 'image/jpeg'),
    { width: 720, quality: 76, format: 'webp' },
  )
  assert.equal(requestedImageVariant({ width: '720' }, 'video/mp4'), null)
  await expectRejected(
    Promise.resolve().then(() => requestedImageVariant({ width: '9000' }, 'image/jpeg')),
    'INVALID_IMAGE_VARIANT',
  )
  assert.match(storageCacheControl('user/7c3936b9-3ba7-4773-a0d0-f66bcfafbd72.jpg'), /31536000/)
  assert.match(storageCacheControl('user/avatar.jpg'), /must-revalidate/)
  assert.equal(etagMatches('W/"old", "current"', '"current"'), true)

  const tempDirectory = await fs.promises.mkdtemp(path.join(os.tmpdir(), 'conectate-image-variant-'))
  try {
    const inputPath = path.join(tempDirectory, 'original.png')
    const outputPath = path.join(tempDirectory, 'preview.webp')
    await sharp({
      create: {
        width: 1600,
        height: 900,
        channels: 3,
        background: { r: 40, g: 90, b: 160 },
      },
    }).png().toFile(inputPath)
    await createImageVariant(inputPath, outputPath, { width: 720, quality: 78, format: 'webp' })
    const metadata = await sharp(await fs.promises.readFile(outputPath)).metadata()
    assert.equal(metadata.format, 'webp')
    assert.equal(metadata.width, 720)
    assert.equal(metadata.height, 405)
  } finally {
    await fs.promises.rm(tempDirectory, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
  }

  const objectName = 'user/7c3936b9-3ba7-4773-a0d0-f66bcfafbd72.png'
  const objectPath = path.join(moduleStorageRoot, 'post-media', ...objectName.split('/'))
  await fs.promises.mkdir(path.dirname(objectPath), { recursive: true })
  await sharp({
    create: {
      width: 1200,
      height: 800,
      channels: 3,
      background: { r: 25, g: 75, b: 140 },
    },
  }).png().toFile(objectPath)
  const objectStat = await fs.promises.stat(objectPath)
  const row = {
    bucket_id: 'post-media',
    name: objectName,
    content_type: 'image/png',
    local_path: objectPath,
    size_bytes: objectStat.size,
    updated_at: objectStat.mtime,
  }
  const pool = {
    async query(sql) {
      if (/SELECT \*/.test(sql) && /fallback_storage_objects/.test(sql)) return { rows: [row] }
      throw new Error(`Unexpected storage test query: ${sql}`)
    },
  }
  const app = express()
  registerStorageRoutes(app, {
    pool,
    authRequired(req, _res, next) {
      req.auth = { sub: 'test-user' }
      next()
    },
  })
  app.use((error, _req, res, _next) => res.status(error.status || 500).json({ error: error.message }))
  const server = await new Promise((resolve) => {
    const listening = app.listen(0, '127.0.0.1', () => resolve(listening))
  })
  try {
    const address = server.address()
    const url = `http://127.0.0.1:${address.port}/storage/v1/object/public/post-media/${objectName}`
    const previewResponse = await fetch(`${url}?width=720&quality=78&format=webp`)
    const previewFailureBody = previewResponse.status === 200 ? '' : await previewResponse.text()
    assert.equal(previewResponse.status, 200, previewFailureBody)
    assert.equal(previewResponse.headers.get('content-type'), 'image/webp')
    assert.match(previewResponse.headers.get('cache-control'), /31536000/)
    assert.equal(previewResponse.headers.get('accept-ranges'), 'bytes')
    const previewEtag = previewResponse.headers.get('etag')
    assert.ok(previewEtag)
    const previewMetadata = await sharp(Buffer.from(await previewResponse.arrayBuffer())).metadata()
    assert.equal(previewMetadata.width, 720)
    assert.equal(previewMetadata.height, 480)

    const notModifiedResponse = await fetch(`${url}?width=720&quality=78&format=webp`, {
      headers: { 'If-None-Match': previewEtag },
    })
    assert.equal(notModifiedResponse.status, 304)
    assert.equal(notModifiedResponse.headers.get('etag'), previewEtag)
    assert.match(notModifiedResponse.headers.get('cache-control'), /31536000/)

    const rangeResponse = await fetch(url, { headers: { Range: 'bytes=0-3' } })
    assert.equal(rangeResponse.status, 206)
    assert.match(rangeResponse.headers.get('content-range'), /^bytes 0-3\//)
    assert.equal((await rangeResponse.arrayBuffer()).byteLength, 4)
  } finally {
    await new Promise((resolve, reject) => server.close((error) => (error ? reject(error) : resolve())))
  }

  console.log('storage-routes tests passed')
}

run().catch((error) => {
  console.error(error)
  process.exitCode = 1
}).finally(async () => {
  await fs.promises.rm(moduleStorageRoot, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
})
