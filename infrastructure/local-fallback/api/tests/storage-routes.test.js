const assert = require('assert/strict')
const fs = require('fs')
const os = require('os')
const path = require('path')
const express = require('express')
const sharp = require('sharp')
const JSZip = require('jszip')
const moduleStorageRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'conectate-storage-http-'))
process.env.STORAGE_ROOT = moduleStorageRoot
process.env.STORAGE_SIGNING_SECRET = 'storage-test-secret-'.repeat(3)
const {
  allowedBuckets,
  createImageVariant,
  delegateStoredObjectToEdge,
  etagMatches,
  inferContentType,
  objectFilePath,
  parseNonNegativeInteger,
  parseTusMetadata,
  registerStorageRoutes,
  parseStorageObjectUrl,
  parseFrameRate,
  progressiveMp4RemuxArgs,
  requestedImageVariant,
  resolveImageVariantResponse,
  signStoredObjectReference,
  signedObjectUrl,
  storedObjectFilePath,
  storageAccelRedirect,
  storageCacheControl,
  resumableUploadFilePath,
  upsertStoredObject,
  validateUpload,
  validateVideoDeliveryMetadata,
  verifyStorageObjectToken,
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
  assert.equal(allowedBuckets.has('live-replays'), true)
  assert.equal(allowedBuckets.has('profile-audio'), true)
  assert.equal(allowedBuckets.has('arbitrary-user-bucket'), false)
  assert.equal(parseNonNegativeInteger('0'), 0)
  assert.equal(parseNonNegativeInteger('6291456'), 6291456)
  assert.equal(parseNonNegativeInteger('-1'), null)
  assert.equal(parseNonNegativeInteger('1.5'), null)
  assert.deepEqual(
    parseTusMetadata('bucketName dmlkZW9z,objectName dXNlci9jbGlwLm1wNA==,contentType dmlkZW8vbXA0'),
    { bucketname: 'videos', objectname: 'user/clip.mp4', contenttype: 'video/mp4' },
  )
  assert.throws(() => parseTusMetadata('bucketName !!!'), /INVALID_TUS_METADATA/)
  assert.throws(() => parseTusMetadata('bucketName dmlkZW9z,bucketName dmlkZW9z'), /INVALID_TUS_METADATA/)
  const resumableId = '11111111-1111-4111-8111-111111111111'
  assert.match(resumableUploadFilePath(resumableId), /resumable[\\/]11111111-1111-4111-8111-111111111111\.part$/)
  assert.throws(() => resumableUploadFilePath('../../host-secret'), /INVALID_UPLOAD_ID/)
  const guardedObjectPath = 'user/guarded.mp4'
  const guardedExpectedPath = objectFilePath('videos', guardedObjectPath)
  assert.equal(
    storedObjectFilePath({ local_path: guardedExpectedPath }, 'videos', guardedObjectPath),
    guardedExpectedPath,
  )
  assert.throws(
    () => storedObjectFilePath({ local_path: path.join(moduleStorageRoot, '..', 'host-secret') }, 'videos', guardedObjectPath),
    /STORAGE_OBJECT_PATH_MISMATCH/,
  )
  assert.throws(
    () => storedObjectFilePath({ local_path: objectFilePath('videos', 'another.mp4') }, 'videos', guardedObjectPath),
    /STORAGE_OBJECT_PATH_MISMATCH/,
  )
  assert.equal(
    storageAccelRedirect(guardedExpectedPath, '/_novvin_storage', moduleStorageRoot),
    '/_novvin_storage/videos/user/guarded.mp4',
  )
  assert.throws(
    () => storageAccelRedirect(path.join(moduleStorageRoot, '..', 'host-secret'), '/_novvin_storage', moduleStorageRoot),
    /STORAGE_ACCEL_PATH_OUTSIDE_ROOT/,
  )
  const delegatedHeaders = new Map([['Content-Length', '1234']])
  let delegatedStatus = 0
  let delegatedEnded = false
  const delegated = delegateStoredObjectToEdge({
    removeHeader(name) { delegatedHeaders.delete(name) },
    setHeader(name, value) { delegatedHeaders.set(name, value) },
    status(code) { delegatedStatus = code; return this },
    end() { delegatedEnded = true },
  }, guardedExpectedPath, '/_novvin_storage', moduleStorageRoot)
  assert.equal(delegated, true)
  assert.equal(delegatedHeaders.has('Content-Length'), false)
  assert.equal(delegatedHeaders.get('X-Accel-Redirect'), '/_novvin_storage/videos/user/guarded.mp4')
  assert.equal(delegatedStatus, 200)
  assert.equal(delegatedEnded, true)
  assert.equal(parseFrameRate('30000/1001').toFixed(2), '29.97')
  const progressiveArgs = progressiveMp4RemuxArgs('/tmp/input.mp4', '/tmp/output.mp4')
  assert.deepEqual(progressiveArgs.slice(-4), ['copy', '-movflags', '+faststart', '/tmp/output.mp4'])
  assert.equal(progressiveArgs.includes('/tmp/input.mp4'), true)
  assert.equal(
    validateVideoDeliveryMetadata({
      durationSeconds: 16,
      width: 1080,
      height: 1920,
      fps: 30,
      codec: 'h264',
      sizeBytes: 6 * 1024 * 1024,
    }).codec,
    'h264',
  )
  assert.throws(
    () => validateVideoDeliveryMetadata({
      durationSeconds: 16,
      width: 1080,
      height: 1920,
      fps: 30,
      codec: 'h264',
      sizeBytes: 95 * 1024 * 1024,
    }),
    /VIDEO_BITRATE_REQUIRES_TRANSCODING/,
  )
  assert.throws(
    () => validateVideoDeliveryMetadata({
      durationSeconds: 8,
      width: 3840,
      height: 2160,
      fps: 59.94,
      codec: 'h264',
      sizeBytes: 45 * 1024 * 1024,
    }),
    /VIDEO_RESOLUTION_REQUIRES_TRANSCODING/,
  )

  const request = {
    headers: { host: 'api.example.test', 'x-forwarded-proto': 'https' },
    protocol: 'https',
    get: () => 'api.example.test',
  }

  const rollbackOwner = '11111111-1111-4111-8111-111111111111'
  const rollbackObject = `${rollbackOwner}/rollback.wav`
  const rollbackPath = objectFilePath('group-audio', rollbackObject)
  const rollbackWav = Buffer.from('524946462400000057415645666d74201000000001000100401f0000401f0000010008006461746100000000', 'hex')
  const originalAudio = Buffer.concat([rollbackWav, Buffer.from('original')])
  const replacementAudio = Buffer.concat([rollbackWav, Buffer.from('replacement')])
  await fs.promises.mkdir(path.dirname(rollbackPath), { recursive: true })
  await fs.promises.writeFile(rollbackPath, originalAudio)
  let rollbackQueryCount = 0
  const rollbackPool = {
    async query() {
      rollbackQueryCount += 1
      if (rollbackQueryCount === 1) return { rows: [{ owner_id: rollbackOwner }] }
      throw new Error('SIMULATED_DATABASE_FAILURE')
    },
  }
  await expectRejected(
    upsertStoredObject(rollbackPool, request, {
      bucket: 'group-audio',
      objectPath: rollbackObject,
      ownerId: rollbackOwner,
      buffer: replacementAudio,
      contentType: 'audio/wav',
      allowOverwrite: true,
    }),
    'SIMULATED_DATABASE_FAILURE',
  )
  assert.deepEqual(await fs.promises.readFile(rollbackPath), originalAudio)
  assert.deepEqual(
    (await fs.promises.readdir(path.dirname(rollbackPath))).filter((name) => name.includes('replace-backup')),
    [],
  )
  const ownerlessObject = `${rollbackOwner}/ownerless.wav`
  const ownerlessPath = objectFilePath('group-audio', ownerlessObject)
  let ownerlessQueries = 0
  await expectRejected(
    upsertStoredObject({
      async query() {
        ownerlessQueries += 1
        return { rows: [{ owner_id: null }] }
      },
    }, request, {
      bucket: 'group-audio',
      objectPath: ownerlessObject,
      ownerId: rollbackOwner,
      buffer: replacementAudio,
      contentType: 'audio/wav',
      allowOverwrite: true,
    }),
    'STORAGE_OBJECT_OWNER_UNKNOWN',
  )
  assert.equal(ownerlessQueries, 1)
  assert.equal(fs.existsSync(ownerlessPath), false)
  const signedEvidenceUrl = signedObjectUrl(request, 'report-evidence', 'owner/report.png', 120)
  const parsedEvidence = parseStorageObjectUrl(signedEvidenceUrl)
  assert.deepEqual(parsedEvidence, { bucket: 'report-evidence', objectPath: 'owner/report.png' })
  const token = new URL(signedEvidenceUrl).searchParams.get('token')
  assert.equal(verifyStorageObjectToken('report-evidence', 'owner/report.png', token), true)
  assert.equal(verifyStorageObjectToken('report-evidence', 'owner/other.png', token), false)
  assert.match(
    signStoredObjectReference(
      request,
      'https://api.example.test/storage/v1/object/public/report-evidence/owner/report.png',
    ),
    /\/storage\/v1\/object\/sign\/report-evidence\/owner\/report\.png\?token=/,
  )

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

  const legacyXls = Buffer.from('d0cf11e0a1b11ae100000000', 'hex')
  assert.equal(inferContentType(legacyXls, 'report.xls'), 'application/vnd.ms-excel')
  assert.equal(
    (await validateUpload({
      bucket: 'chat-media',
      objectPath: 'user/report.xls',
      buffer: legacyXls,
      contentType: 'application/octet-stream',
    })).contentType,
    'application/vnd.ms-excel',
  )
  await expectRejected(
    validateUpload({ bucket: 'chat-media', objectPath: 'user/bad.xls', buffer: Buffer.from('010203040506', 'hex'), contentType: 'application/vnd.ms-excel' }),
    'UNSUPPORTED_OR_MISMATCHED_MEDIA_TYPE',
  )
  const modernXls = Buffer.from('504b030414000000000000000000', 'hex')
  assert.equal(
    (await validateUpload({
      bucket: 'group-media',
      objectPath: 'group/report.xlsx',
      buffer: modernXls,
      contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    })).contentType,
    'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  )
  const renamedWorkbook = new JSZip()
  renamedWorkbook.file('xl/workbook.xml', '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheets/></workbook>')
  renamedWorkbook.file('[Content_Types].xml', '<Types><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/></Types>')
  const renamedWorkbookBytes = await renamedWorkbook.generateAsync({ type: 'nodebuffer', compression: 'DEFLATE' })
  const correctedWorkbook = await validateUpload({
    bucket: 'chat-media', objectPath: 'user/report.xls', buffer: renamedWorkbookBytes,
    contentType: 'application/vnd.ms-excel',
  })
  assert.equal(correctedWorkbook.contentType, 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet')
  const renamedWorkbookFile = path.join(moduleStorageRoot, 'renamed-workbook.xls')
  fs.writeFileSync(renamedWorkbookFile, renamedWorkbookBytes)
  assert.equal((await validateUpload({
    bucket: 'chat-media', objectPath: 'user/report.xls', sourcePath: renamedWorkbookFile,
    sizeBytes: renamedWorkbookBytes.length, contentType: 'application/vnd.ms-excel',
  })).contentType, correctedWorkbook.contentType)
  const invalidWorkbook = new JSZip()
  invalidWorkbook.file('xl/workbook.xml', 'not a workbook')
  invalidWorkbook.file('[Content_Types].xml', '<Types><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/></Types>')
  await expectRejected(validateUpload({
    bucket: 'chat-media', objectPath: 'user/forged.xls',
    buffer: await invalidWorkbook.generateAsync({ type: 'nodebuffer', compression: 'DEFLATE' }),
    contentType: 'application/vnd.ms-excel',
  }), 'UNSUPPORTED_OR_MISMATCHED_MEDIA_TYPE')
  await expectRejected(validateUpload({
    bucket: 'chat-media', objectPath: 'user/fake.xls', buffer: modernXls,
    contentType: 'application/vnd.ms-excel',
  }), 'UNSUPPORTED_OR_MISMATCHED_MEDIA_TYPE')
  const opaqueFile = Buffer.from('010203040506', 'hex')
  assert.equal(
    (await validateUpload({ bucket: 'chat-media', objectPath: 'user/design.psd', buffer: opaqueFile, contentType: 'application/octet-stream' })).contentType,
    'application/octet-stream',
  )
  await expectRejected(
    validateUpload({ bucket: 'chat-media', objectPath: 'user/page.html', buffer: opaqueFile, contentType: 'application/octet-stream' }),
    'UNSUPPORTED_OR_MISMATCHED_MEDIA_TYPE',
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
  const flac = Buffer.from('664c614300000022', 'hex')
  assert.equal(
    (await validateUpload({
      bucket: 'profile-audio',
      objectPath: 'user/profile-song.flac',
      buffer: flac,
      contentType: 'audio/flac',
    })).contentType,
    'audio/flac',
  )
  assert.equal(
    (await validateUpload({
      bucket: 'community-music',
      objectPath: 'user/profile-song.flac',
      buffer: flac,
      contentType: 'audio/x-flac',
    })).contentType,
    'audio/flac',
  )
  const aac = Buffer.from('fff15080001ffc0000000000', 'hex')
  assert.equal(inferContentType(aac, 'track.aac'), 'audio/aac')
  assert.equal(
    (await validateUpload({
      bucket: 'community-music',
      objectPath: 'user/track.m4a',
      buffer: aac,
      contentType: 'audio/aac',
    })).contentType,
    'audio/aac',
  )
  const opus = Buffer.concat([
    Buffer.from('4f6767530002000000000000000000000000000000000000011e01', 'hex'),
    Buffer.from('OpusHead', 'ascii'),
  ])
  assert.equal(inferContentType(opus, 'track.ogg'), 'audio/opus')
  assert.equal(
    (await validateUpload({
      bucket: 'community-music',
      objectPath: 'user/track.ogg',
      buffer: opus,
      contentType: 'application/ogg',
    })).contentType,
    'audio/opus',
  )

  const mp4 = Buffer.from('000000186674797069736f6d0000020069736f6d69736f32', 'hex')
  assert.equal(
    (await validateUpload({
      bucket: 'live-replays',
      objectPath: 'user/replay.mp4',
      buffer: mp4,
      contentType: 'video/mp4',
    })).contentType,
    'video/mp4',
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
  for (const bucket of ['post-comments-media', 'video-comments-media']) {
    await expectRejected(validateUpload({ bucket, objectPath: 'comments/user/voice.wav',
      buffer: wav, contentType: 'audio/wav' }), 'UNSUPPORTED_OR_MISMATCHED_MEDIA_TYPE')
    assert.equal((await validateUpload({ bucket, objectPath: 'comments/user/sticker.png',
      buffer: png, contentType: 'image/png' })).contentType, 'image/png')
    assert.equal((await validateUpload({ bucket, objectPath: 'comments/user/sticker.gif',
      buffer: gif, contentType: 'image/gif' })).contentType, 'image/gif')
  }
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
    { width: 720, quality: 78, format: 'webp' },
  )
  assert.equal(requestedImageVariant({ width: '720' }, 'video/mp4'), null)
  await expectRejected(
    Promise.resolve().then(() => requestedImageVariant({ width: '9000' }, 'image/jpeg')),
    'INVALID_IMAGE_VARIANT',
  )
  assert.match(storageCacheControl('user/7c3936b9-3ba7-4773-a0d0-f66bcfafbd72.jpg'), /31536000/)
  assert.match(storageCacheControl('user/avatar.jpg'), /must-revalidate/)
  assert.equal(etagMatches('W/"old", "current"', '"current"'), true)

  const variantFailure = new Error('SIMULATED_VARIANT_PERMISSION_ERROR')
  variantFailure.code = 'EACCES'
  const fallbackVariant = await resolveImageVariantResponse({
    bucket: 'avatars',
    objectPath: 'user/avatar.jpg',
    filePath: '/storage/avatars/user/avatar.jpg',
    stat: { size: 1024, mtimeMs: 1234 },
    variant: { width: 384, quality: 84, format: 'webp' },
    generateVariant: async () => { throw variantFailure },
  })
  assert.equal(fallbackVariant.responsePath, '/storage/avatars/user/avatar.jpg')
  assert.equal(fallbackVariant.servedVariant, null)
  assert.equal(fallbackVariant.variantError, variantFailure)

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

  // Exercise the actual TUS HTTP flow, including the database row lock used
  // by PATCH. This intentionally uses an audio bucket so final validation is
  // meaningful without needing ffmpeg in the unit-test environment.
  const resumableRows = new Map()
  const resumableObjects = new Map()
  const resumableQuery = async (sql, values = []) => {
    const normalized = sql.replace(/\s+/g, ' ').trim()
    if (normalized.startsWith('DELETE FROM fallback_resumable_uploads') && normalized.includes('expires_at <= NOW()')) {
      return { rows: [] }
    }
    if (normalized.startsWith('INSERT INTO fallback_resumable_uploads')) {
      const [id, ownerId, bucket, objectPath, contentType, length, metadata, allowOverwrite, expiresAt] = values
      resumableRows.set(id, {
        id,
        owner_id: ownerId,
        bucket_id: bucket,
        object_name: objectPath,
        content_type: contentType,
        upload_length: length,
        upload_offset: 0,
        upload_metadata: metadata,
        allow_overwrite: allowOverwrite,
        state: 'uploading',
        stored_object_path: null,
        expires_at: expiresAt,
      })
      return { rows: [] }
    }
    if (normalized.startsWith('SELECT * FROM fallback_resumable_uploads')) {
      const row = resumableRows.get(values[0])
      return { rows: row && row.owner_id === values[1] ? [row] : [] }
    }
    if (normalized.startsWith('UPDATE fallback_resumable_uploads SET upload_offset')) {
      const row = resumableRows.get(values[0])
      row.upload_offset = values[1]
      row.state = values[2]
      row.expires_at = values[3]
      return { rows: [] }
    }
    if (normalized.startsWith("UPDATE fallback_resumable_uploads SET state = 'completed'")) {
      const row = resumableRows.get(values[0])
      row.state = 'completed'
      row.stored_object_path = values[1]
      return { rows: [] }
    }
    if (normalized.startsWith('SELECT owner_id FROM fallback_storage_objects')) {
      return { rows: resumableObjects.has(`${values[0]}/${values[1]}`) ? [resumableObjects.get(`${values[0]}/${values[1]}`)] : [] }
    }
    if (normalized.startsWith('INSERT INTO fallback_storage_objects')) {
      const row = {
        id: '22222222-2222-4222-8222-222222222222',
        bucket_id: values[0],
        name: values[1],
        owner_id: values[2],
        content_type: values[3],
        size_bytes: values[4],
        public_url: values[5],
        local_path: values[6],
        metadata: values[7],
      }
      resumableObjects.set(`${row.bucket_id}/${row.name}`, row)
      return { rows: [row] }
    }
    throw new Error(`Unexpected resumable storage query: ${normalized}`)
  }
  const resumablePool = {
    query: resumableQuery,
    async connect() {
      return {
        query: async (sql, values) => {
          const normalized = String(sql).trim()
          if (normalized === 'BEGIN' || normalized === 'COMMIT' || normalized === 'ROLLBACK') return { rows: [] }
          if (normalized.startsWith('SELECT pg_try_advisory_lock')) return { rows: [{ acquired: true }] }
          if (normalized.startsWith('SELECT pg_advisory_unlock')) return { rows: [{ pg_advisory_unlock: true }] }
          return resumableQuery(sql, values)
        },
        release() {},
      }
    },
  }
  const resumableApp = express()
  registerStorageRoutes(resumableApp, {
    pool: resumablePool,
    storageFinalizationConnection: () => resumablePool.connect(), storageUploadAdmission: { async reserve({ id = crypto.randomUUID(), ownerId }) { return { id, assertAlive() {}, async finish() {} } }, async claimTus(id) { return { id, assertAlive() {}, async finish() {} } } },
    authRequired(req, _res, next) {
      req.auth = { sub: String(req.headers['x-test-user'] || 'test-user') }
      next()
    },
  })
  resumableApp.use((error, _req, res, _next) => res.status(error.status || 500).json({ error: error.message }))
  const resumableServer = await new Promise((resolve) => {
    const listening = resumableApp.listen(0, '127.0.0.1', () => resolve(listening))
  })
  try {
    const address = resumableServer.address()
    const baseUrl = `http://127.0.0.1:${address.port}/storage/v1/upload/resumable`
    const audioBytes = Buffer.concat([wav, Buffer.from('tus-completion')])
    const metadata = [
      `bucketName ${Buffer.from('group-audio').toString('base64')}`,
      `objectName ${Buffer.from('test-user/resumable.wav').toString('base64')}`,
      `contentType ${Buffer.from('audio/wav').toString('base64')}`,
    ].join(',')
    const createResponse = await fetch(baseUrl, {
      method: 'POST',
      headers: {
        'Tus-Resumable': '1.0.0',
        'Upload-Length': String(audioBytes.length),
        'Upload-Metadata': metadata,
        'x-test-user': 'test-user',
      },
    })
    assert.equal(createResponse.status, 201)
    const uploadUrl = new URL(createResponse.headers.get('location'), baseUrl).toString()
    assert.equal(createResponse.headers.get('upload-offset'), '0')
    assert.equal(createResponse.headers.get('tus-resumable'), '1.0.0')
    const offsetResponse = await fetch(uploadUrl, {
      method: 'HEAD',
      headers: { 'Tus-Resumable': '1.0.0', 'x-test-user': 'test-user' },
    })
    assert.equal(offsetResponse.status, 200)
    assert.equal(offsetResponse.headers.get('upload-offset'), '0')
    const patchResponse = await fetch(uploadUrl, {
      method: 'PATCH',
      headers: {
        'Tus-Resumable': '1.0.0',
        'Upload-Offset': '0',
        'Content-Type': 'application/offset+octet-stream',
        'x-test-user': 'test-user',
      },
      body: audioBytes,
    })
    assert.equal(patchResponse.status, 204)
    assert.equal(patchResponse.headers.get('upload-offset'), String(audioBytes.length))
    assert.equal(patchResponse.headers.get('upload-state'), 'completed')
    assert.equal(patchResponse.headers.get('upload-object-path'), 'test-user/resumable.wav')
    assert.deepEqual(
      await fs.promises.readFile(objectFilePath('group-audio', 'test-user/resumable.wav')),
      audioBytes,
    )
    const completedOffset = await fetch(uploadUrl, {
      method: 'HEAD',
      headers: { 'Tus-Resumable': '1.0.0', 'x-test-user': 'test-user' },
    })
    assert.equal(completedOffset.status, 200)
    assert.equal(completedOffset.headers.get('upload-state'), 'completed')
    assert.equal(completedOffset.headers.get('upload-object-path'), 'test-user/resumable.wav')

    // Simulate a process crash after committing the final offset but before
    // publishing the object. HEAD must acquire the distributed finalization
    // lock and finish the orphaned session instead of leaving it stuck.
    const uploadId = uploadUrl.split('/').pop()
    const orphaned = resumableRows.get(uploadId)
    orphaned.state = 'finalizing'
    orphaned.stored_object_path = null
    orphaned.expires_at = new Date(Date.now() + 60_000)
    await fs.promises.copyFile(
      objectFilePath('group-audio', 'test-user/resumable.wav'),
      resumableUploadFilePath(uploadId),
    )
    const recoveredFinalization = await fetch(uploadUrl, {
      method: 'HEAD',
      headers: { 'Tus-Resumable': '1.0.0', 'x-test-user': 'test-user' },
    })
    assert.equal(recoveredFinalization.status, 200)
    assert.equal(recoveredFinalization.headers.get('upload-state'), 'completed')
    assert.equal(recoveredFinalization.headers.get('upload-object-path'), 'test-user/resumable.wav')
    assert.deepEqual(
      await fs.promises.readFile(objectFilePath('group-audio', 'test-user/resumable.wav')),
      audioBytes,
    )
  } finally {
    await new Promise((resolve, reject) => resumableServer.close((error) => (error ? reject(error) : resolve())))
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
