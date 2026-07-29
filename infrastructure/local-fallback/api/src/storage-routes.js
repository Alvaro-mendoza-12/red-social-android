const fs = require('fs')
const path = require('path')
const express = require('express')
const multer = require('multer')
const crypto = require('crypto')
const sharp = require('sharp')
const { Transform } = require('stream')
const { pipeline } = require('stream/promises')

const defaultBuckets = [
  'avatars',
  'chat-media',
  'post-media',
  'moment-media',
  'videos',
  'live-replays',
  'thumbnails',
  'group-media',
  'community-media',
  'groups',
  'communities',
  'challenges',
  'video-comments-media',
  'post-comments-media',
  'group-audio',
  'profile-audio',
  'report-evidence',
]

const allowedBuckets = new Set(defaultBuckets)

const storageRoot = path.resolve(process.env.STORAGE_ROOT || path.join(process.cwd(), 'storage-data'))
const maxUploadBytes = Number(process.env.MAX_UPLOAD_BYTES || 512 * 1024 * 1024)
const tempDir = path.join(storageRoot, '.tmp')

const upload = multer({
  dest: tempDir,
  limits: {
    fileSize: maxUploadBytes,
    files: 1,
  },
})

const bucketUploadLimits = new Map([
  ['avatars', 50 * 1024 * 1024],
  ['chat-media', 60 * 1024 * 1024],
  ['group-media', 60 * 1024 * 1024],
  ['community-media', 60 * 1024 * 1024],
  ['groups', 60 * 1024 * 1024],
  ['communities', 60 * 1024 * 1024],
  ['group-audio', 20 * 1024 * 1024],
  ['profile-audio', 40 * 1024 * 1024],
  ['post-media', 25 * 1024 * 1024],
  ['moment-media', 35 * 1024 * 1024],
  ['challenges', 30 * 1024 * 1024],
  // Publishing policy is duration-based (45 seconds). Keep only the global
  // infrastructure safety ceiling so a short, high-bitrate phone recording is
  // not rejected merely because it exceeds 100 MB.
  ['videos', maxUploadBytes],
  ['live-replays', 512 * 1024 * 1024],
  ['thumbnails', 10 * 1024 * 1024],
  ['video-comments-media', 20 * 1024 * 1024],
  ['post-comments-media', 20 * 1024 * 1024],
  ['report-evidence', 10 * 1024 * 1024],
])

const imageTypes = new Set(['image/jpeg', 'image/png', 'image/webp', 'image/gif'])
const videoTypes = new Set(['video/mp4', 'video/webm'])
const audioTypes = new Set([
  'audio/mpeg',
  'audio/mp4',
  'audio/wav',
  'audio/x-wav',
  'audio/ogg',
  'audio/webm',
  'audio/flac',
])
const documentTypes = new Set([
  'application/pdf',
  'application/zip',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  'text/plain',
  'text/csv',
])
const documentExtensions = new Set(['pdf', 'zip', 'docx', 'xlsx', 'pptx', 'txt', 'csv'])
const messagingBuckets = new Set(['chat-media', 'group-media', 'community-media', 'groups', 'communities'])
const imageVariantRoot = path.join(storageRoot, '_variants')
const imageVariantJobs = new Map()
const immutableObjectPattern = /(?:[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}|\d{13,}|[0-9a-f]{32,})/i
const transformableImageTypes = new Set(['image/jpeg', 'image/png', 'image/webp'])

function storageCacheControl(objectPath) {
  return immutableObjectPattern.test(String(objectPath || ''))
    ? 'public, max-age=31536000, immutable'
    : 'public, max-age=300, stale-while-revalidate=86400, must-revalidate'
}

function requestedImageVariant(query, contentType) {
  const rawWidth = String(query?.width || '').trim()
  if (!rawWidth || !transformableImageTypes.has(canonicalContentType(contentType))) return null
  if (!/^\d{2,4}$/.test(rawWidth)) {
    const error = new Error('INVALID_IMAGE_VARIANT')
    error.status = 400
    throw error
  }
  const width = Number(rawWidth)
  const quality = Number(String(query?.quality || '78').trim())
  const format = String(query?.format || 'webp').trim().toLowerCase()
  if (width < 64 || width > 1920 || !Number.isInteger(quality) || quality < 40 || quality > 90 || format !== 'webp') {
    const error = new Error('INVALID_IMAGE_VARIANT')
    error.status = 400
    throw error
  }
  return { width, quality, format }
}

function objectVariantDirectory(bucket, objectPath) {
  const digest = crypto.createHash('sha256').update(`${bucket}:${objectPath}`).digest('hex')
  return path.join(imageVariantRoot, digest.slice(0, 2), digest)
}

async function removeImageVariants(bucket, objectPath) {
  await fs.promises.rm(objectVariantDirectory(bucket, objectPath), { recursive: true, force: true })
}

async function createImageVariant(inputPath, outputPath, variant) {
  await sharp(inputPath, { limitInputPixels: 50_000_000 })
    .rotate()
    .resize({ width: variant.width, fit: 'inside', withoutEnlargement: true })
    .webp({ quality: variant.quality, effort: 2 })
    .toFile(outputPath)
}

async function ensureImageVariant(bucket, objectPath, filePath, stat, variant) {
  const directory = objectVariantDirectory(bucket, objectPath)
  const identity = `${stat.size}-${Math.trunc(stat.mtimeMs)}-${variant.width}-${variant.quality}`
  const outputPath = path.join(directory, `${identity}.webp`)
  if (await fs.promises.access(outputPath, fs.constants.R_OK).then(() => true).catch(() => false)) {
    return outputPath
  }

  if (!imageVariantJobs.has(outputPath)) {
    const job = (async () => {
      await fs.promises.mkdir(directory, { recursive: true })
      const temporaryPath = `${outputPath}.${crypto.randomUUID()}.tmp`
      try {
        await createImageVariant(filePath, temporaryPath, variant)
        await fs.promises.rename(temporaryPath, outputPath).catch(async (error) => {
          if (error?.code !== 'EEXIST') throw error
          await fs.promises.unlink(temporaryPath).catch(() => {})
        })
        return outputPath
      } catch (error) {
        await fs.promises.unlink(temporaryPath).catch(() => {})
        throw error
      }
    })().finally(() => imageVariantJobs.delete(outputPath))
    imageVariantJobs.set(outputPath, job)
  }
  return imageVariantJobs.get(outputPath)
}

function etagMatches(headerValue, etag) {
  return String(headerValue || '')
    .split(',')
    .map((value) => value.trim().replace(/^W\//, ''))
    .includes(etag)
}

function canonicalContentType(value) {
  return String(value || 'application/octet-stream').split(';')[0].trim().toLowerCase()
}

function extensionOf(objectPath) {
  return path.extname(String(objectPath || '')).slice(1).trim().toLowerCase()
}

function startsWith(buffer, bytes) {
  if (!Buffer.isBuffer(buffer) || buffer.length < bytes.length) return false
  return bytes.every((byte, index) => buffer[index] === byte)
}

function inferContentType(header, objectPath) {
  const extension = extensionOf(objectPath)
  if (startsWith(header, [0xff, 0xd8, 0xff])) return 'image/jpeg'
  if (startsWith(header, [0x89, 0x50, 0x4e, 0x47])) return 'image/png'
  if (startsWith(header, [0x47, 0x49, 0x46, 0x38])) return 'image/gif'
  if (startsWith(header, [0x25, 0x50, 0x44, 0x46, 0x2d])) return 'application/pdf'
  if (startsWith(header, [0x50, 0x4b, 0x03, 0x04]) || startsWith(header, [0x50, 0x4b, 0x05, 0x06])) {
    if (extension === 'docx') return 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
    if (extension === 'xlsx') return 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
    if (extension === 'pptx') return 'application/vnd.openxmlformats-officedocument.presentationml.presentation'
    return 'application/zip'
  }
  if (startsWith(header, [0x52, 0x49, 0x46, 0x46])) {
    const riffKind = header.subarray(8, 12).toString('ascii')
    if (riffKind === 'WEBP') return 'image/webp'
    if (riffKind === 'WAVE') return 'audio/wav'
  }
  if (startsWith(header, [0x49, 0x44, 0x33]) || (header[0] === 0xff && (header[1] & 0xe0) === 0xe0)) {
    return 'audio/mpeg'
  }
  if (startsWith(header, [0x4f, 0x67, 0x67, 0x53])) return 'audio/ogg'
  if (startsWith(header, [0x66, 0x4c, 0x61, 0x43])) return 'audio/flac'
  if (startsWith(header, [0x1a, 0x45, 0xdf, 0xa3])) {
    return ['weba', 'opus'].includes(extension) ? 'audio/webm' : 'video/webm'
  }
  if (header?.subarray(4, 8).toString('ascii') === 'ftyp') {
    const brand = header.subarray(8, 12).toString('ascii').toLowerCase()
    return brand.includes('m4a') || extension === 'm4a' ? 'audio/mp4' : 'video/mp4'
  }
  if (['txt', 'csv'].includes(extension) && Buffer.isBuffer(header)) {
    const sample = header.subarray(0, 512)
    const hasNull = sample.includes(0)
    const controlBytes = [...sample].filter((byte) => byte < 9 || (byte > 13 && byte < 32)).length
    if (!hasNull && controlBytes <= Math.max(1, Math.floor(sample.length * 0.02))) {
      return extension === 'csv' ? 'text/csv' : 'text/plain'
    }
  }
  return ''
}

function containsExecutableHeader(header) {
  return startsWith(header, [0x4d, 0x5a]) ||
    startsWith(header, [0x7f, 0x45, 0x4c, 0x46]) ||
    startsWith(header, [0x23, 0x21])
}

function typesCompatible(declared, inferred) {
  if (declared === inferred) return true
  if (declared === 'application/octet-stream' && inferred) return true
  if (new Set(['audio/mp4', 'video/mp4']).has(declared) && new Set(['audio/mp4', 'video/mp4']).has(inferred)) return true
  if (new Set(['audio/wav', 'audio/x-wav']).has(declared) && inferred === 'audio/wav') return true
  if (new Set(['audio/webm', 'video/webm']).has(declared) && new Set(['audio/webm', 'video/webm']).has(inferred)) return true
  if (declared === 'application/zip' && documentTypes.has(inferred)) return true
  return false
}

function allowedTypesForBucket(bucket) {
  const allowed = new Set()
  if (['avatars', 'thumbnails', 'report-evidence'].includes(bucket)) imageTypes.forEach((type) => allowed.add(type))
  if (bucket === 'report-evidence') allowed.delete('image/gif')
  if (bucket === 'avatars') allowed.add('video/mp4')
  if (['post-media', 'moment-media'].includes(bucket)) {
    imageTypes.forEach((type) => allowed.add(type))
    allowed.delete('image/gif')
    allowed.add('video/mp4')
  }
  if (bucket === 'videos' || bucket === 'live-replays') allowed.add('video/mp4')
  if (['video-comments-media', 'post-comments-media', 'group-audio', 'profile-audio'].includes(bucket)) {
    audioTypes.forEach((type) => allowed.add(type))
  }
  if (messagingBuckets.has(bucket) || bucket === 'challenges') {
    imageTypes.forEach((type) => allowed.add(type))
    videoTypes.forEach((type) => allowed.add(type))
    audioTypes.forEach((type) => allowed.add(type))
    if (messagingBuckets.has(bucket)) documentTypes.forEach((type) => allowed.add(type))
  }
  return allowed
}

async function readHeader(options, maxBytes = 512) {
  if (Buffer.isBuffer(options.buffer)) return options.buffer.subarray(0, maxBytes)
  if (!options.sourcePath) return Buffer.alloc(0)
  const handle = await fs.promises.open(options.sourcePath, 'r')
  try {
    const buffer = Buffer.alloc(maxBytes)
    const { bytesRead } = await handle.read(buffer, 0, buffer.length, 0)
    return buffer.subarray(0, bytesRead)
  } finally {
    await handle.close()
  }
}

async function validateUpload(options) {
  const bucket = cleanBucketName(options.bucket)
  if (!allowedBuckets.has(bucket)) {
    const error = new Error('BUCKET_NOT_ALLOWED')
    error.status = 400
    throw error
  }
  const sizeBytes = Number(options.sizeBytes || options.buffer?.length || 0)
  if (!Number.isFinite(sizeBytes) || sizeBytes <= 0) {
    const error = new Error('FILE_REQUIRED')
    error.status = 400
    throw error
  }
  if (sizeBytes > uploadLimitForBucket(bucket)) {
    const error = new Error('UPLOAD_TOO_LARGE')
    error.status = 413
    throw error
  }
  const header = await readHeader(options)
  if (containsExecutableHeader(header)) {
    const error = new Error('EXECUTABLE_UPLOAD_BLOCKED')
    error.status = 415
    throw error
  }
  const declaredType = canonicalContentType(options.contentType)
  let inferredType = inferContentType(header, options.objectPath || options.fallbackName)
  // EBML headers do not identify audio vs. video without parsing tracks. The declared
  // WebM subtype is safe to preserve because compatibility is still restricted below.
  if (inferredType === 'video/webm' && declaredType === 'audio/webm') inferredType = 'audio/webm'
  const extension = extensionOf(options.objectPath || options.fallbackName)
  const allowedTypes = allowedTypesForBucket(bucket)
  if (!inferredType || !allowedTypes.has(inferredType) || !typesCompatible(declaredType, inferredType)) {
    const error = new Error('UNSUPPORTED_OR_MISMATCHED_MEDIA_TYPE')
    error.status = 415
    throw error
  }
  if (documentExtensions.has(extension) && !documentTypes.has(inferredType)) {
    const error = new Error('DOCUMENT_SIGNATURE_MISMATCH')
    error.status = 415
    throw error
  }
  return { contentType: inferredType, sizeBytes }
}

function uploadLimitForBucket(bucket) {
  return Math.min(maxUploadBytes, bucketUploadLimits.get(bucket) || maxUploadBytes)
}

async function streamRequestToTemp(req, byteLimit) {
  await fs.promises.mkdir(tempDir, { recursive: true })
  const declaredLength = Number(req.headers['content-length'] || 0)
  if (declaredLength > byteLimit) {
    const error = new Error('UPLOAD_TOO_LARGE')
    error.status = 413
    throw error
  }

  const tempPath = path.join(tempDir, `raw-${crypto.randomUUID()}`)
  let sizeBytes = 0
  const limiter = new Transform({
    transform(chunk, _encoding, callback) {
      sizeBytes += chunk.length
      if (sizeBytes > byteLimit) {
        const error = new Error('UPLOAD_TOO_LARGE')
        error.status = 413
        callback(error)
        return
      }
      callback(null, chunk)
    },
  })

  try {
    await pipeline(req, limiter, fs.createWriteStream(tempPath, { flags: 'wx' }))
    return { tempPath, sizeBytes }
  } catch (error) {
    await fs.promises.unlink(tempPath).catch(() => {})
    throw error
  }
}

function cleanBucketName(value) {
  const bucket = String(value || '').trim()
  if (!/^[a-zA-Z0-9][a-zA-Z0-9._-]{0,80}$/.test(bucket)) return ''
  return bucket
}

function safeSegment(value) {
  return String(value || '')
    .trim()
    .replace(/\\/g, '/')
    .split('/')
    .map((segment) => segment.trim().replace(/[^a-zA-Z0-9._-]/g, '_'))
    .filter((segment) => segment && segment !== '.' && segment !== '..')
}

function normalizeObjectPath(value, fallbackName) {
  const segments = safeSegment(value)
  if (segments.length > 0) return segments.join('/')
  const fallbackSegments = safeSegment(fallbackName || `file-${Date.now()}`)
  return fallbackSegments.length > 0 ? fallbackSegments.join('/') : `file-${Date.now()}`
}

function contentDispositionName(objectPath) {
  const base = path.basename(objectPath).replace(/"/g, '')
  return base || 'archivo'
}

function publicBaseUrl(req) {
  const configured = String(process.env.PUBLIC_BASE_URL || '').trim().replace(/\/+$/, '')
  if (configured) return configured
  const proto = String(req.headers['x-forwarded-proto'] || req.protocol || 'http').split(',')[0].trim()
  const host = String(req.headers['x-forwarded-host'] || req.get('host') || '').split(',')[0].trim()
  return host ? `${proto}://${host}` : ''
}

function publicObjectUrl(req, bucket, objectPath) {
  const encodedPath = objectPath
    .split('/')
    .filter(Boolean)
    .map((segment) => encodeURIComponent(segment))
    .join('/')
  const base = publicBaseUrl(req)
  return `${base}/storage/v1/object/public/${encodeURIComponent(bucket)}/${encodedPath}`
}

function objectFilePath(bucket, objectPath) {
  const bucketRoot = path.join(storageRoot, bucket)
  const resolved = path.resolve(bucketRoot, objectPath)
  if (!resolved.startsWith(path.resolve(bucketRoot) + path.sep) && resolved !== path.resolve(bucketRoot)) {
    throw new Error('INVALID_STORAGE_PATH')
  }
  return resolved
}

async function removeStorageObjectFiles(rows) {
  const removed = []
  const failures = []
  const seen = new Set()
  const rootPrefix = `${storageRoot}${path.sep}`

  for (const row of rows || []) {
    const bucket = cleanBucketName(row?.bucket_id)
    const objectPath = normalizeObjectPath(row?.name || '', '')
    if (!bucket || !objectPath) continue

    let filePath
    try {
      filePath = path.resolve(row?.local_path || objectFilePath(bucket, objectPath))
      if (filePath !== storageRoot && !filePath.startsWith(rootPrefix)) {
        throw new Error('STORAGE_PATH_OUTSIDE_ROOT')
      }
    } catch (error) {
      failures.push({ bucket, name: objectPath, error: error.message })
      continue
    }
    if (seen.has(filePath)) continue
    seen.add(filePath)

    try {
      await fs.promises.unlink(filePath)
      await removeImageVariants(bucket, objectPath)
      removed.push({ bucket, name: objectPath })
    } catch (error) {
      if (error?.code === 'ENOENT') {
        await removeImageVariants(bucket, objectPath)
        removed.push({ bucket, name: objectPath, alreadyMissing: true })
      } else {
        failures.push({ bucket, name: objectPath, error: error.message })
      }
    }
  }

  return { removed, failures }
}

async function ensureLocalStorageTables(pool) {
  await fs.promises.mkdir(tempDir, { recursive: true })
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_storage_buckets (
      id TEXT PRIMARY KEY,
      public BOOLEAN NOT NULL DEFAULT TRUE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_storage_objects (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      bucket_id TEXT NOT NULL REFERENCES fallback_storage_buckets(id) ON DELETE CASCADE,
      name TEXT NOT NULL,
      owner_id UUID REFERENCES fallback_users(id) ON DELETE SET NULL,
      content_type TEXT NOT NULL DEFAULT 'application/octet-stream',
      size_bytes BIGINT NOT NULL DEFAULT 0,
      public_url TEXT NOT NULL,
      local_path TEXT NOT NULL,
      metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE(bucket_id, name)
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_storage_objects_owner
    ON fallback_storage_objects(owner_id, updated_at DESC)
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_storage_objects_bucket
    ON fallback_storage_objects(bucket_id, updated_at DESC)
  `)
  await pool.query(
    `INSERT INTO fallback_storage_buckets(id, public)
     SELECT bucket, TRUE
     FROM unnest($1::text[]) AS bucket
     ON CONFLICT (id) DO NOTHING`,
    [defaultBuckets],
  )
}

function mapObjectRow(row) {
  return {
    id: row.id,
    bucket: row.bucket_id,
    path: row.name,
    publicUrl: row.public_url,
    url: row.public_url,
    contentType: row.content_type,
    size: Number(row.size_bytes || 0),
    ownerId: row.owner_id || null,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  }
}

async function upsertStoredObject(pool, req, options) {
  const bucket = cleanBucketName(options.bucket)
  const requestedPath = normalizeObjectPath(options.objectPath, options.fallbackName)
  const ownerId = String(options.ownerId || '').trim()
  const ownerPrefix = safeSegment(ownerId)[0] || ''
  const objectPath = ownerPrefix && requestedPath.split('/')[0] !== ownerPrefix
    ? `${ownerPrefix}/${requestedPath}`
    : requestedPath
  if (!bucket || !objectPath) {
    const error = new Error('INVALID_STORAGE_PATH')
    error.status = 400
    throw error
  }

  const validated = await validateUpload({ ...options, bucket, objectPath })

  const existingResult = await pool.query(
    `SELECT owner_id FROM fallback_storage_objects WHERE bucket_id = $1 AND name = $2 LIMIT 1`,
    [bucket, objectPath],
  )
  const existingOwner = String(existingResult.rows[0]?.owner_id || '').trim()
  if (existingOwner && existingOwner !== ownerId) {
    const error = new Error('STORAGE_OBJECT_OWNED_BY_ANOTHER_USER')
    error.status = 403
    throw error
  }
  if (existingResult.rows[0] && options.allowOverwrite === false) {
    const error = new Error('STORAGE_OBJECT_ALREADY_EXISTS')
    error.status = 409
    throw error
  }

  const destination = objectFilePath(bucket, objectPath)
  await fs.promises.mkdir(path.dirname(destination), { recursive: true })
  if (existingResult.rows[0]) await removeImageVariants(bucket, objectPath)

  if (options.sourcePath) {
    await fs.promises.rename(options.sourcePath, destination)
  } else {
    await fs.promises.writeFile(destination, options.buffer || Buffer.alloc(0))
  }

  const publicUrl = publicObjectUrl(req, bucket, objectPath)
  const sizeBytes =
    typeof options.sizeBytes === 'number'
      ? options.sizeBytes
      : Buffer.isBuffer(options.buffer)
        ? options.buffer.length
        : 0

  const { rows } = await pool.query(
    `INSERT INTO fallback_storage_objects(
        bucket_id,
        name,
        owner_id,
        content_type,
        size_bytes,
        public_url,
        local_path,
        metadata
     )
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
     ON CONFLICT (bucket_id, name)
     DO UPDATE SET
        owner_id = EXCLUDED.owner_id,
        content_type = EXCLUDED.content_type,
        size_bytes = EXCLUDED.size_bytes,
        public_url = EXCLUDED.public_url,
        local_path = EXCLUDED.local_path,
        metadata = EXCLUDED.metadata,
        updated_at = NOW()
     RETURNING *`,
    [
      bucket,
      objectPath,
      ownerId || null,
      validated.contentType,
      validated.sizeBytes,
      publicUrl,
      destination,
      options.metadata || {},
    ],
  )

  if (!rows[0]) {
    const error = new Error('STORAGE_OBJECT_OWNED_BY_ANOTHER_USER')
    error.status = 403
    throw error
  }

  if (transformableImageTypes.has(validated.contentType)) {
    // Generate the preview as soon as the upload is accepted. It runs in the
    // background and shares the same in-flight job with an immediate GET, so
    // browsing does not pay the full conversion cost several seconds later.
    fs.promises.stat(destination)
      .then((stat) => ensureImageVariant(bucket, objectPath, destination, stat, {
        width: 1080,
        quality: 78,
        format: 'webp',
      }))
      .catch((error) => {
        console.warn('[storage] image preview prewarm failed', {
          bucket,
          objectPath,
          error: error.message,
        })
      })
  }
  return rows[0]
}

async function serveStoredObject(pool, req, res, bucketParam, pathParam) {
  const bucket = cleanBucketName(bucketParam)
  const objectPath = normalizeObjectPath(decodeURIComponent(pathParam || ''), '')
  if (!bucket || !objectPath) return res.status(404).json({ error: 'NOT_FOUND' })

  const { rows } = await pool.query(
    `SELECT *
     FROM fallback_storage_objects
     WHERE bucket_id = $1 AND name = $2
     LIMIT 1`,
    [bucket, objectPath],
  )
  const row = rows[0]
  if (!row) return res.status(404).json({ error: 'NOT_FOUND' })
  const filePath = row?.local_path || objectFilePath(bucket, objectPath)
  await fs.promises.access(filePath, fs.constants.R_OK)
  const stat = await fs.promises.stat(filePath)
  const variant = requestedImageVariant(req.query, row?.content_type)
  const responsePath = variant
    ? await ensureImageVariant(bucket, objectPath, filePath, stat, variant)
    : filePath
  const responseStat = variant ? await fs.promises.stat(responsePath) : stat
  const lastModified = row?.updated_at ? new Date(row.updated_at) : stat.mtime
  const variantIdentity = variant ? `:${variant.width}:${variant.quality}:${variant.format}` : ''
  const etag = `"${Buffer.from(`${bucket}:${objectPath}:${stat.size}:${Math.trunc(stat.mtimeMs)}${variantIdentity}`).toString('base64url')}"`
  res.setHeader('Access-Control-Allow-Origin', '*')
  res.setHeader('Cross-Origin-Resource-Policy', 'cross-origin')
  res.setHeader('Content-Type', variant ? 'image/webp' : row?.content_type || 'application/octet-stream')
  res.setHeader('Cache-Control', storageCacheControl(objectPath))
  res.setHeader('ETag', etag)
  res.setHeader('Last-Modified', lastModified.toUTCString())
  res.setHeader('Accept-Ranges', 'bytes')
  res.setHeader('Content-Length', String(responseStat.size))
  res.setHeader(
    'Content-Disposition',
    `inline; filename="${contentDispositionName(variant ? `${objectPath}.webp` : objectPath)}"`,
  )
  if (etagMatches(req.headers['if-none-match'], etag)) {
    res.status(304)
    return res.end()
  }
  return res.sendFile(responsePath)
}

function registerStorageRoutes(app, { pool, authRequired }) {
  app.get('/v1/storage/buckets', authRequired, async (_req, res, next) => {
    try {
      const { rows } = await pool.query(
        `SELECT id, public, created_at
         FROM fallback_storage_buckets
         ORDER BY id`,
      )
      res.json({ buckets: rows })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/storage/upload', authRequired, upload.single('file'), async (req, res, next) => {
    const uploadedPath = req.file?.path
    try {
      const bucket = cleanBucketName(req.body?.bucket)
      if (!bucket) {
        return res.status(400).json({ error: 'INVALID_BUCKET' })
      }
      if (!req.file) {
        return res.status(400).json({ error: 'FILE_REQUIRED' })
      }

      const requestedPath = String(req.body?.path || '').trim()
      const defaultPath = `${req.auth.sub}/${Date.now()}-${req.file.originalname || 'file'}`
      const row = await upsertStoredObject(pool, req, {
        bucket,
        objectPath: requestedPath,
        fallbackName: defaultPath,
        ownerId: req.auth.sub,
        sourcePath: req.file.path,
        contentType: req.file.mimetype || 'application/octet-stream',
        sizeBytes: req.file.size || 0,
        metadata: {
          originalName: req.file.originalname || '',
          encoding: req.file.encoding || '',
        },
      })

      res.status(201).json({ object: mapObjectRow(row) })
    } catch (error) {
      if (uploadedPath) {
        try {
          await fs.promises.unlink(uploadedPath)
        } catch {
          // Best effort cleanup.
        }
      }
      next(error)
    }
  })

  app.get('/v1/storage/objects', authRequired, async (req, res, next) => {
    try {
      const bucket = cleanBucketName(req.query?.bucket)
      const prefix = normalizeObjectPath(req.query?.prefix || '', '') || ''
      const { rows } = await pool.query(
        `SELECT *
         FROM fallback_storage_objects
         WHERE ($1::text = '' OR bucket_id = $1)
           AND ($2::text = '' OR name LIKE ($2 || '%'))
           AND owner_id = $3
         ORDER BY updated_at DESC
         LIMIT 200`,
        [bucket, prefix, req.auth.sub],
      )
      res.json({ objects: rows.map(mapObjectRow) })
    } catch (error) {
      next(error)
    }
  })

  app.delete(/^\/v1\/storage\/objects\/([^/]+)\/(.+)$/, authRequired, async (req, res, next) => {
    try {
      const bucket = cleanBucketName(req.params[0])
      const objectPath = normalizeObjectPath(decodeURIComponent(req.params[1] || ''), '')
      if (!bucket || !objectPath) return res.status(400).json({ error: 'INVALID_STORAGE_PATH' })

      const { rows } = await pool.query(
        `SELECT *
         FROM fallback_storage_objects
         WHERE bucket_id = $1 AND name = $2
         LIMIT 1`,
        [bucket, objectPath],
      )
      const row = rows[0]
      if (!row) return res.status(404).json({ error: 'NOT_FOUND' })
      if (row.owner_id && row.owner_id !== req.auth.sub) {
        return res.status(403).json({ error: 'FORBIDDEN' })
      }

      await pool.query(
        `DELETE FROM fallback_storage_objects
         WHERE bucket_id = $1 AND name = $2`,
        [bucket, objectPath],
      )

      const filePath = row.local_path || objectFilePath(bucket, objectPath)
      try {
        await fs.promises.unlink(filePath)
      } catch (error) {
        if (error?.code !== 'ENOENT') throw error
      }
      await removeImageVariants(bucket, objectPath)

      res.json({ ok: true })
    } catch (error) {
      next(error)
    }
  })

  const objectWriteRoute = /^\/storage\/v1\/object\/([^/]+)\/(.+)$/

  app.post(/^\/storage\/v1\/object\/sign\/([^/]+)\/(.+)$/, authRequired, async (req, res, next) => {
    try {
      const bucket = cleanBucketName(req.params[0])
      const objectPath = normalizeObjectPath(decodeURIComponent(req.params[1] || ''), '')
      if (!bucket || !objectPath) return res.status(400).json({ error: 'INVALID_STORAGE_PATH' })
      const signedURL = publicObjectUrl(req, bucket, objectPath)
      res.json({ signedURL, signedUrl: signedURL })
    } catch (error) {
      next(error)
    }
  })

  app.post(objectWriteRoute, authRequired, async (req, res, next) => {
    let uploadedPath = null
    try {
      const bucket = cleanBucketName(req.params[0])
      const objectPath = normalizeObjectPath(decodeURIComponent(req.params[1] || ''), '')
      if (!bucket || !objectPath) return res.status(400).json({ error: 'INVALID_STORAGE_PATH' })
      const streamed = await streamRequestToTemp(req, uploadLimitForBucket(bucket))
      uploadedPath = streamed.tempPath
      if (!streamed.sizeBytes) {
        await fs.promises.unlink(uploadedPath).catch(() => {})
        uploadedPath = null
        return res.status(400).json({ error: 'FILE_REQUIRED' })
      }

      const row = await upsertStoredObject(pool, req, {
        bucket,
        objectPath,
        fallbackName: `${req.auth.sub}/${Date.now()}-file`,
        ownerId: req.auth.sub,
        sourcePath: streamed.tempPath,
        sizeBytes: streamed.sizeBytes,
        contentType: req.headers['content-type'] || 'application/octet-stream',
        allowOverwrite: String(req.headers['x-upsert'] || 'true').toLowerCase() !== 'false',
        metadata: {
          source: 'supabase-compatible-object-api',
        },
      })
      uploadedPath = null

      res.status(200).json({
        Key: `${row.bucket_id}/${row.name}`,
        path: row.name,
        publicUrl: row.public_url,
      })
    } catch (error) {
      if (uploadedPath) await fs.promises.unlink(uploadedPath).catch(() => {})
      next(error)
    }
  })

  app.put(objectWriteRoute, authRequired, async (req, res, next) => {
    let uploadedPath = null
    try {
      const bucket = cleanBucketName(req.params[0])
      const objectPath = normalizeObjectPath(decodeURIComponent(req.params[1] || ''), '')
      if (!bucket || !objectPath) return res.status(400).json({ error: 'INVALID_STORAGE_PATH' })
      const streamed = await streamRequestToTemp(req, uploadLimitForBucket(bucket))
      uploadedPath = streamed.tempPath
      if (!streamed.sizeBytes) {
        await fs.promises.unlink(uploadedPath).catch(() => {})
        uploadedPath = null
        return res.status(400).json({ error: 'FILE_REQUIRED' })
      }

      const row = await upsertStoredObject(pool, req, {
        bucket,
        objectPath,
        fallbackName: `${req.auth.sub}/${Date.now()}-file`,
        ownerId: req.auth.sub,
        sourcePath: streamed.tempPath,
        sizeBytes: streamed.sizeBytes,
        contentType: req.headers['content-type'] || 'application/octet-stream',
        allowOverwrite: String(req.headers['x-upsert'] || 'true').toLowerCase() !== 'false',
        metadata: {
          source: 'supabase-compatible-object-api',
        },
      })
      uploadedPath = null

      res.status(200).json({
        Key: `${row.bucket_id}/${row.name}`,
        path: row.name,
        publicUrl: row.public_url,
      })
    } catch (error) {
      if (uploadedPath) await fs.promises.unlink(uploadedPath).catch(() => {})
      next(error)
    }
  })

  app.get(/^\/storage\/v1\/object\/public\/([^/]+)\/(.+)$/, async (req, res, next) => {
    try {
      await serveStoredObject(pool, req, res, req.params[0], req.params[1])
    } catch (error) {
      if (error?.code === 'ENOENT') return res.status(404).json({ error: 'NOT_FOUND' })
      next(error)
    }
  })

  app.get(/^\/storage\/v1\/object\/([^/]+)\/(.+)$/, async (req, res, next) => {
    try {
      await serveStoredObject(pool, req, res, req.params[0], req.params[1])
    } catch (error) {
      if (error?.code === 'ENOENT') return res.status(404).json({ error: 'NOT_FOUND' })
      next(error)
    }
  })
}

module.exports = {
  allowedBuckets,
  createImageVariant,
  etagMatches,
  inferContentType,
  requestedImageVariant,
  storageCacheControl,
  validateUpload,
  ensureLocalStorageTables,
  removeStorageObjectFiles,
  registerStorageRoutes,
}
