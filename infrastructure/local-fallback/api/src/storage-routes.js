const fs = require('fs')
const path = require('path')
const express = require('express')
const multer = require('multer')
const crypto = require('crypto')
const sharp = require('sharp')
const { execFile } = require('child_process')
const { promisify } = require('util')
const { Transform } = require('stream')
const { pipeline } = require('stream/promises')
const { stableKey } = require('./request-security')
const { safeErrorLogDetails } = require('./request-observability')

const execFileAsync = promisify(execFile)

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
  'community-music',
  'report-evidence',
]

const allowedBuckets = new Set(defaultBuckets)
const signedOnlyBuckets = new Set(['report-evidence'])
const storageSigningSecret = String(process.env.STORAGE_SIGNING_SECRET || process.env.JWT_SECRET || '').trim()
const storageSignedUrlMaxSeconds = 15 * 60

if (String(process.env.NODE_ENV || '').trim().toLowerCase() === 'production' && storageSigningSecret.length < 32) {
  throw new Error('STORAGE_SIGNING_SECRET or JWT_SECRET must contain at least 32 characters in production')
}

const storageRoot = path.resolve(process.env.STORAGE_ROOT || path.join(process.cwd(), 'storage-data'))
const storageAccelPrefix = normalizeStorageAccelPrefix(process.env.STORAGE_ACCEL_PREFIX)
const maxUploadBytes = Number(process.env.MAX_UPLOAD_BYTES || 512 * 1024 * 1024)
const tempDir = path.join(storageRoot, '.tmp')
const resumableTempDir = path.join(tempDir, 'resumable')
const tusVersion = '1.0.0'
const resumableChunkBytes = 6 * 1024 * 1024
const resumableUploadLifetimeMs = 24 * 60 * 60 * 1000
const resumableCompletedRetentionMs = 60 * 60 * 1000

function normalizeStorageAccelPrefix(value) {
  const prefix = String(value || '').trim().replace(/\/+$/, '')
  if (!prefix) return ''
  if (prefix.length > 128 || !/^\/[a-zA-Z0-9/_-]+$/.test(prefix) || prefix.split('/').includes('..')) {
    throw new Error('STORAGE_ACCEL_PREFIX must be an absolute internal URI path')
  }
  return prefix
}

function storageAccelRedirect(filePath, prefix = storageAccelPrefix, root = storageRoot) {
  const normalizedPrefix = normalizeStorageAccelPrefix(prefix)
  if (!normalizedPrefix) return ''

  const resolvedRoot = path.resolve(root)
  const resolvedFile = path.resolve(filePath)
  const relativePath = path.relative(resolvedRoot, resolvedFile)
  if (!relativePath || relativePath.startsWith(`..${path.sep}`) || path.isAbsolute(relativePath)) {
    const error = new Error('STORAGE_ACCEL_PATH_OUTSIDE_ROOT')
    error.status = 500
    throw error
  }

  const encodedPath = relativePath
    .split(path.sep)
    .filter(Boolean)
    .map((segment) => encodeURIComponent(segment))
    .join('/')
  return `${normalizedPrefix}/${encodedPath}`
}

function delegateStoredObjectToEdge(res, filePath, prefix = storageAccelPrefix, root = storageRoot) {
  const accelRedirect = storageAccelRedirect(filePath, prefix, root)
  if (!accelRedirect) return false

  // The upstream response itself has no body; Nginx calculates the final
  // Content-Length (or Content-Range) from the internal file.
  res.removeHeader('Content-Length')
  res.setHeader('X-Accel-Redirect', accelRedirect)
  res.status(200).end()
  return true
}

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
  ['community-music', 80 * 1024 * 1024],
  ['post-media', 25 * 1024 * 1024],
  ['moment-media', 35 * 1024 * 1024],
  ['challenges', 30 * 1024 * 1024],
  // A 45-second delivery-ready H.264/AAC file at the maximum accepted bitrate
  // remains below this ceiling. Rejecting oversized sources before writing the
  // whole request protects the uplink and forces old clients to optimize first.
  ['videos', 32 * 1024 * 1024],
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
  'audio/aac',
  'audio/wav',
  'audio/x-wav',
  'audio/ogg',
  'application/ogg',
  'audio/opus',
  'audio/webm',
  'audio/flac',
  'audio/x-flac',
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
const maxVideoDurationSeconds = 45
const minVideoDurationSeconds = 3
const maxVideoDeliveryBitrateBps = 4_500_000

function parseFrameRate(rawValue) {
  const value = String(rawValue || '').trim()
  if (!value) return 0
  if (!value.includes('/')) return Number(value) || 0
  const [numerator, denominator] = value.split('/').map(Number)
  return Number.isFinite(numerator) && Number.isFinite(denominator) && denominator > 0
    ? numerator / denominator
    : 0
}

function validateVideoDeliveryMetadata(metadata) {
  const durationSeconds = Number(metadata?.durationSeconds || 0)
  const width = Number(metadata?.width || 0)
  const height = Number(metadata?.height || 0)
  const fps = Number(metadata?.fps || 0)
  const sizeBytes = Number(metadata?.sizeBytes || 0)
  const codec = String(metadata?.codec || '').trim().toLowerCase()
  const averageBitrateBps = durationSeconds > 0 && sizeBytes > 0
    ? Math.ceil(sizeBytes * 8 / durationSeconds)
    : Number.POSITIVE_INFINITY
  const longEdge = Math.max(width, height)
  const shortEdge = Math.min(width, height)

  let reason = ''
  if (!Number.isFinite(durationSeconds) || durationSeconds < minVideoDurationSeconds) reason = 'VIDEO_DURATION_TOO_SHORT'
  else if (durationSeconds > maxVideoDurationSeconds + 0.25) reason = 'VIDEO_DURATION_TOO_LONG'
  else if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) reason = 'VIDEO_DIMENSIONS_INVALID'
  else if (longEdge > 1920 || shortEdge > 1080 || width * height > 2_073_600) reason = 'VIDEO_RESOLUTION_REQUIRES_TRANSCODING'
  else if (!Number.isFinite(fps) || fps <= 0 || fps > 31) reason = 'VIDEO_FRAME_RATE_REQUIRES_TRANSCODING'
  else if (codec !== 'h264') reason = 'VIDEO_CODEC_REQUIRES_TRANSCODING'
  else if (averageBitrateBps > maxVideoDeliveryBitrateBps) reason = 'VIDEO_BITRATE_REQUIRES_TRANSCODING'

  if (reason) {
    const error = new Error(reason)
    error.status = 422
    throw error
  }
  return { durationSeconds, width, height, fps, codec, averageBitrateBps }
}

async function probeVideoDelivery(sourcePath, sizeBytes) {
  let parsed
  try {
    const { stdout } = await execFileAsync(
      'ffprobe',
      [
        '-v', 'error',
        '-select_streams', 'v:0',
        '-show_entries', 'stream=codec_name,width,height,avg_frame_rate,r_frame_rate:format=duration',
        '-of', 'json',
        sourcePath,
      ],
      { timeout: 20_000, maxBuffer: 1024 * 1024 },
    )
    parsed = JSON.parse(stdout)
  } catch (cause) {
    const error = new Error('VIDEO_DECODE_FAILED')
    error.status = 415
    error.cause = cause
    throw error
  }
  const stream = parsed?.streams?.[0]
  if (!stream) {
    const error = new Error('VIDEO_STREAM_REQUIRED')
    error.status = 415
    throw error
  }
  return validateVideoDeliveryMetadata({
    durationSeconds: parsed?.format?.duration || stream.duration,
    width: stream.width,
    height: stream.height,
    fps: parseFrameRate(stream.avg_frame_rate || stream.r_frame_rate),
    codec: stream.codec_name,
    sizeBytes,
  })
}

function progressiveMp4RemuxArgs(inputPath, outputPath) {
  return [
    '-hide_banner', '-loglevel', 'error', '-y',
    '-i', inputPath,
    '-map', '0:v:0', '-map', '0:a?',
    '-c', 'copy',
    '-movflags', '+faststart',
    outputPath,
  ]
}

async function optimizeMp4ForProgressiveDownload(sourcePath, { enforceFeedLimits = true, maxBytes = Infinity } = {}) {
  const outputPath = `${sourcePath}.faststart-${crypto.randomUUID()}.mp4`
  try {
    await execFileAsync(
      'ffmpeg',
      progressiveMp4RemuxArgs(sourcePath, outputPath),
      { timeout: 120_000, maxBuffer: 2 * 1024 * 1024 },
    )
    const optimizedStat = await fs.promises.stat(outputPath)
    if (optimizedStat.size <= 0 || optimizedStat.size > maxBytes) {
      const error = new Error('UPLOAD_TOO_LARGE')
      error.status = 413
      throw error
    }
    if (enforceFeedLimits) await probeVideoDelivery(outputPath, optimizedStat.size)
    await fs.promises.rm(sourcePath, { force: true })
    await fs.promises.rename(outputPath, sourcePath)
    return optimizedStat.size
  } catch (cause) {
    await fs.promises.rm(outputPath, { force: true }).catch(() => {})
    if (cause?.status === 413) throw cause
    const error = new Error('VIDEO_PROGRESSIVE_OPTIMIZATION_FAILED')
    error.status = 422
    error.cause = cause
    throw error
  }
}

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

async function resolveImageVariantResponse({
  bucket,
  objectPath,
  filePath,
  stat,
  variant,
  generateVariant = ensureImageVariant,
}) {
  if (!variant) return { responsePath: filePath, servedVariant: null, variantError: null }
  try {
    return {
      responsePath: await generateVariant(bucket, objectPath, filePath, stat, variant),
      servedVariant: variant,
      variantError: null,
    }
  } catch (variantError) {
    // A derived preview is an optimization, never a reason to hide media that
    // is already stored correctly. The caller logs the failure and serves the
    // original object with matching headers so Android/Coil can still decode it.
    return { responsePath: filePath, servedVariant: null, variantError }
  }
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
  // ADTS AAC and MPEG frames share the 0xFF sync prefix. Check the
  // stricter ADTS layer bits first so valid .aac uploads are not mislabeled.
  if (header?.[0] === 0xff && (header[1] & 0xf6) === 0xf0) return 'audio/aac'
  if (startsWith(header, [0x49, 0x44, 0x33]) || (header[0] === 0xff && (header[1] & 0xe0) === 0xe0)) {
    return 'audio/mpeg'
  }
  if (startsWith(header, [0x4f, 0x67, 0x67, 0x53])) {
    return header.indexOf(Buffer.from('OpusHead')) >= 0 ? 'audio/opus' : 'audio/ogg'
  }
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
  if (new Set(['audio/ogg', 'application/ogg', 'audio/opus']).has(declared) &&
      new Set(['audio/ogg', 'audio/opus']).has(inferred)) return true
  if (new Set(['audio/flac', 'audio/x-flac']).has(declared) && inferred === 'audio/flac') return true
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
  if (['video-comments-media', 'post-comments-media', 'group-audio', 'profile-audio', 'community-music'].includes(bucket)) {
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
  if (bucket === 'videos' && inferredType === 'video/mp4' && options.sourcePath) {
    await probeVideoDelivery(options.sourcePath, sizeBytes)
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

async function publishedContentUsesObject(pool, bucket, objectPath) {
  if (!['moment-media', 'videos', 'thumbnails'].includes(bucket)) return false
  const encodedPath = objectPath.split('/').filter(Boolean).map(encodeURIComponent).join('/')
  const marker = `/storage/v1/object/public/${encodeURIComponent(bucket)}/${encodedPath}`
  const query = bucket === 'moment-media'
    ? `SELECT 1 FROM fallback_moments
       WHERE position($1 in media_url) > 0
          OR position($1 in COALESCE(thumbnail_url, '')) > 0
       LIMIT 1`
    : `SELECT 1 FROM fallback_videos
       WHERE position($1 in video_url) > 0
          OR position($1 in COALESCE(cover_url, '')) > 0
          OR position($1 in COALESCE(thumbnail_url, '')) > 0
       LIMIT 1`
  const { rows } = await pool.query(query, [marker])
  return rows.length > 0
}

function parseNonNegativeInteger(value) {
  const raw = String(value ?? '').trim()
  if (!/^\d+$/.test(raw)) return null
  const parsed = Number(raw)
  return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : null
}

function resumableUploadFilePath(uploadId) {
  const id = String(uploadId || '').trim().toLowerCase()
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(id)) {
    const error = new Error('INVALID_UPLOAD_ID')
    error.status = 400
    throw error
  }
  return path.join(resumableTempDir, `${id}.part`)
}

function decodeTusMetadataValue(value) {
  const raw = String(value || '')
  if (!raw || raw.length > 2_048 || !/^[A-Za-z0-9+/]*={0,2}$/.test(raw) || raw.length % 4 === 1) {
    return null
  }
  const decoded = Buffer.from(raw, 'base64')
  // Buffer accepts some malformed Base64 input. Re-encoding prevents that
  // permissiveness from turning a metadata header into an ambiguous value.
  if (decoded.toString('base64').replace(/=+$/, '') !== raw.replace(/=+$/, '')) return null
  const text = decoded.toString('utf8')
  return text.includes('\u0000') ? null : text
}

function parseTusMetadata(headerValue) {
  const raw = String(headerValue || '').trim()
  if (!raw) return {}
  if (raw.length > 8_192) {
    const error = new Error('TUS_METADATA_TOO_LARGE')
    error.status = 400
    throw error
  }
  const metadata = {}
  for (const entry of raw.split(',')) {
    const [keyPart, ...valueParts] = entry.trim().split(' ')
    const key = String(keyPart || '').trim().toLowerCase()
    if (!/^[a-z0-9_-]{1,64}$/.test(key) || Object.prototype.hasOwnProperty.call(metadata, key)) {
      const error = new Error('INVALID_TUS_METADATA')
      error.status = 400
      throw error
    }
    const encoded = valueParts.join('').trim()
    const value = encoded ? decodeTusMetadataValue(encoded) : ''
    if (value == null || value.length > 1_024) {
      const error = new Error('INVALID_TUS_METADATA')
      error.status = 400
      throw error
    }
    metadata[key] = value
  }
  return metadata
}

function requireTusRequest(req) {
  if (String(req.headers['tus-resumable'] || '').trim() === tusVersion) return true
  const error = new Error('TUS_VERSION_REQUIRED')
  error.status = 412
  throw error
}

function setTusHeaders(res, { uploadLength, uploadOffset, uploadExpires, objectPath } = {}) {
  res.setHeader('Tus-Resumable', tusVersion)
  res.setHeader('Tus-Version', tusVersion)
  res.setHeader('Tus-Extension', 'creation,expiration,termination')
  res.setHeader('Tus-Max-Size', String(maxUploadBytes))
  res.setHeader('Cache-Control', 'no-store')
  if (Number.isSafeInteger(uploadLength) && uploadLength >= 0) res.setHeader('Upload-Length', String(uploadLength))
  if (Number.isSafeInteger(uploadOffset) && uploadOffset >= 0) res.setHeader('Upload-Offset', String(uploadOffset))
  if (uploadExpires) res.setHeader('Upload-Expires', new Date(uploadExpires).toUTCString())
  if (objectPath) res.setHeader('Upload-Object-Path', objectPath)
}

async function streamResumableChunk(req, destination, { offset, maxBytes }) {
  const declaredLength = parseNonNegativeInteger(req.headers['content-length'])
  if (declaredLength == null || declaredLength <= 0) {
    const error = new Error('TUS_CHUNK_LENGTH_REQUIRED')
    error.status = 400
    throw error
  }
  if (declaredLength > maxBytes) {
    const error = new Error('TUS_CHUNK_TOO_LARGE')
    error.status = 413
    throw error
  }
  let sizeBytes = 0
  const limiter = new Transform({
    transform(chunk, _encoding, callback) {
      sizeBytes += chunk.length
      if (sizeBytes > maxBytes) {
        const error = new Error('TUS_CHUNK_TOO_LARGE')
        error.status = 413
        callback(error)
        return
      }
      callback(null, chunk)
    },
  })
  try {
    await pipeline(req, limiter, fs.createWriteStream(destination, { flags: 'r+', start: offset }))
    if (sizeBytes !== declaredLength) {
      const error = new Error('TUS_CHUNK_LENGTH_MISMATCH')
      error.status = 400
      throw error
    }
    return sizeBytes
  } catch (error) {
    await fs.promises.truncate(destination, offset).catch(() => {})
    throw error
  }
}

function storageObjectToken(bucket, objectPath, expiresAtSeconds) {
  if (storageSigningSecret.length < 32) return ''
  const payload = `${bucket}\n${objectPath}\n${expiresAtSeconds}`
  const signature = crypto.createHmac('sha256', storageSigningSecret).update(payload).digest('base64url')
  return `${expiresAtSeconds}.${signature}`
}

function verifyStorageObjectToken(bucket, objectPath, token, nowSeconds = Math.floor(Date.now() / 1000)) {
  const [rawExpiry, providedSignature, ...extra] = String(token || '').split('.')
  const expiresAt = Number(rawExpiry)
  if (extra.length > 0 || !providedSignature || !Number.isSafeInteger(expiresAt)) return false
  if (expiresAt <= nowSeconds || expiresAt > nowSeconds + storageSignedUrlMaxSeconds + 30) return false
  const expected = storageObjectToken(bucket, objectPath, expiresAt).split('.')[1] || ''
  const left = Buffer.from(providedSignature)
  const right = Buffer.from(expected)
  return left.length === right.length && left.length > 0 && crypto.timingSafeEqual(left, right)
}

function signedObjectUrl(req, bucket, objectPath, expiresInSeconds = 10 * 60) {
  const ttl = Math.max(30, Math.min(Number(expiresInSeconds) || 600, storageSignedUrlMaxSeconds))
  const expiresAt = Math.floor(Date.now() / 1000) + ttl
  const token = storageObjectToken(bucket, objectPath, expiresAt)
  if (!token) return ''
  const encodedPath = objectPath.split('/').filter(Boolean).map(encodeURIComponent).join('/')
  return `${publicBaseUrl(req)}/storage/v1/object/sign/${encodeURIComponent(bucket)}/${encodedPath}?token=${encodeURIComponent(token)}`
}

function parseStorageObjectUrl(rawUrl) {
  try {
    const parsed = new URL(String(rawUrl || ''), 'http://storage.local')
    const match = parsed.pathname.match(/^\/storage\/v1\/object\/(?:public|sign)\/([^/]+)\/(.+)$/i)
    if (!match) return null
    const bucket = cleanBucketName(decodeURIComponent(match[1]))
    const objectPath = normalizeObjectPath(decodeURIComponent(match[2]), '')
    return bucket && objectPath ? { bucket, objectPath } : null
  } catch {
    return null
  }
}

function signStoredObjectReference(req, rawUrl, expiresInSeconds = 10 * 60) {
  const location = parseStorageObjectUrl(rawUrl)
  if (!location || !signedOnlyBuckets.has(location.bucket)) return rawUrl
  return signedObjectUrl(req, location.bucket, location.objectPath, expiresInSeconds) || null
}

async function maySignStoredObject(pool, req, row) {
  if (String(row?.owner_id || '') === String(req.auth?.sub || '')) return true
  if (row?.bucket_id !== 'report-evidence') return false
  const { rows } = await pool.query(
    `SELECT 1
     FROM fallback_users u
     JOIN fallback_profile_moderation m
       ON lower(m.user_id) IN (lower(u.id::text), lower(COALESCE(u.supabase_user_id::text, '')))
     WHERE u.id = $1::uuid
       AND COALESCE(m.is_admin, FALSE) = TRUE
       AND COALESCE(m.admin_access_level, 'none') <> 'none'
     LIMIT 1`,
    [req.auth.sub],
  )
  return Boolean(rows[0])
}

function objectFilePath(bucket, objectPath) {
  const bucketRoot = path.join(storageRoot, bucket)
  const resolved = path.resolve(bucketRoot, objectPath)
  if (!resolved.startsWith(path.resolve(bucketRoot) + path.sep) && resolved !== path.resolve(bucketRoot)) {
    throw new Error('INVALID_STORAGE_PATH')
  }
  return resolved
}

/**
 * Resolve a database storage row to the only filesystem location that its
 * bucket/name identity is allowed to own. `local_path` is retained for
 * migration diagnostics, but it must never be able to redirect a public read
 * or a delete to an arbitrary file on the host.
 */
function storedObjectFilePath(row, bucket, objectPath) {
  const expected = objectFilePath(bucket, objectPath)
  const recorded = String(row?.local_path || '').trim()
  if (recorded && path.resolve(recorded) !== expected) {
    const error = new Error('STORAGE_OBJECT_PATH_MISMATCH')
    error.status = 500
    throw error
  }
  return expected
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
      filePath = storedObjectFilePath(row, bucket, objectPath)
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
  await fs.promises.mkdir(resumableTempDir, { recursive: true })
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
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_resumable_uploads (
      id UUID PRIMARY KEY,
      owner_id UUID NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
      bucket_id TEXT NOT NULL REFERENCES fallback_storage_buckets(id) ON DELETE CASCADE,
      object_name TEXT NOT NULL,
      content_type TEXT NOT NULL,
      upload_length BIGINT NOT NULL CHECK (upload_length > 0),
      upload_offset BIGINT NOT NULL DEFAULT 0 CHECK (upload_offset >= 0 AND upload_offset <= upload_length),
      upload_metadata TEXT NOT NULL DEFAULT '',
      allow_overwrite BOOLEAN NOT NULL DEFAULT TRUE,
      state TEXT NOT NULL DEFAULT 'uploading' CHECK (state IN ('uploading', 'finalizing', 'completed', 'rejected')),
      stored_object_path TEXT,
      expires_at TIMESTAMPTZ NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_resumable_uploads_expiry
    ON fallback_resumable_uploads(expires_at)
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_resumable_uploads_owner
    ON fallback_resumable_uploads(owner_id, updated_at DESC)
  `)
  await pool.query(
    `INSERT INTO fallback_storage_buckets(id, public)
     SELECT bucket, bucket <> ALL($2::text[])
     FROM unnest($1::text[]) AS bucket
     ON CONFLICT (id) DO UPDATE SET public = EXCLUDED.public`,
    [defaultBuckets, Array.from(signedOnlyBuckets)],
  )
}

async function withResumableUploadTransaction(pool, task) {
  if (!pool || typeof pool.connect !== 'function') {
    const error = new Error('RESUMABLE_UPLOAD_DATABASE_UNAVAILABLE')
    error.status = 503
    throw error
  }
  const client = await pool.connect()
  let committed = false
  try {
    await client.query('BEGIN')
    const result = await task(client)
    await client.query('COMMIT')
    committed = true
    return result
  } catch (error) {
    if (!committed) await client.query('ROLLBACK').catch(() => {})
    throw error
  } finally {
    client.release()
  }
}

function mapResumableUploadRow(row) {
  return {
    id: String(row?.id || ''),
    ownerId: String(row?.owner_id || ''),
    bucket: cleanBucketName(row?.bucket_id),
    objectPath: normalizeObjectPath(row?.object_name || '', ''),
    contentType: canonicalContentType(row?.content_type),
    uploadLength: Number(row?.upload_length || 0),
    uploadOffset: Number(row?.upload_offset || 0),
    metadata: String(row?.upload_metadata || ''),
    allowOverwrite: Boolean(row?.allow_overwrite),
    state: String(row?.state || ''),
    storedObjectPath: String(row?.stored_object_path || ''),
    expiresAt: row?.expires_at,
  }
}

async function cleanupExpiredResumableUploads(pool) {
  const { rows } = await pool.query(
    `DELETE FROM fallback_resumable_uploads
      WHERE expires_at <= NOW()
         OR (state = 'completed' AND updated_at <= NOW() - ($1::text)::interval)
      RETURNING id, state`,
    [`${resumableCompletedRetentionMs} milliseconds`],
  )
  await Promise.all((rows || [])
    .filter((row) => String(row?.state || '') !== 'completed')
    .map((row) => fs.promises.unlink(resumableUploadFilePath(row.id)).catch(() => {})))
  return rows?.length || 0
}

async function finalizeResumableUpload(pool, req, upload) {
  const sourcePath = resumableUploadFilePath(upload.id)
  try {
    const row = await upsertStoredObject(pool, req, {
      bucket: upload.bucket,
      objectPath: upload.objectPath,
      fallbackName: `${upload.ownerId}/${Date.now()}-file`,
      ownerId: upload.ownerId,
      sourcePath,
      sizeBytes: upload.uploadLength,
      contentType: upload.contentType,
      allowOverwrite: upload.allowOverwrite,
      metadata: {
        source: 'tus-resumable-upload',
      },
    })
    await pool.query(
      `UPDATE fallback_resumable_uploads
          SET state = 'completed', stored_object_path = $2, expires_at = NOW() + ($3::text)::interval, updated_at = NOW()
        WHERE id = $1`,
      [upload.id, row.name, `${resumableCompletedRetentionMs} milliseconds`],
    )
    return row
  } catch (error) {
    const rejected = Number(error?.status || 500) >= 400 && Number(error?.status || 500) < 500
    if (rejected) {
      await fs.promises.unlink(sourcePath).catch(() => {})
      await pool.query(
        `UPDATE fallback_resumable_uploads
            SET state = 'rejected', expires_at = NOW(), updated_at = NOW()
          WHERE id = $1`,
        [upload.id],
      ).catch(() => {})
    } else {
      await pool.query(
        `UPDATE fallback_resumable_uploads
            SET state = 'finalizing', expires_at = NOW() + ($2::text)::interval, updated_at = NOW()
          WHERE id = $1`,
        [upload.id, `${resumableUploadLifetimeMs} milliseconds`],
      ).catch(() => {})
    }
    throw error
  }
}

async function finalizeResumableUploadWithLock(pool, req, uploadId) {
  const client = await pool.connect()
  let acquired = false
  try {
    const lockResult = await client.query(
      `SELECT pg_try_advisory_lock(hashtextextended($1::text, 0)) AS acquired`,
      [uploadId],
    )
    acquired = lockResult.rows[0]?.acquired === true
    if (!acquired) return null

    const { rows } = await client.query(
      `SELECT * FROM fallback_resumable_uploads WHERE id = $1 AND owner_id = $2 LIMIT 1`,
      [uploadId, req.auth.sub],
    )
    const upload = mapResumableUploadRow(rows[0])
    if (!upload.id) {
      const error = new Error('TUS_UPLOAD_NOT_FOUND')
      error.status = 404
      throw error
    }
    if (upload.state === 'completed') {
      return { name: upload.storedObjectPath, alreadyCompleted: true }
    }
    if (upload.state !== 'finalizing' || upload.uploadOffset !== upload.uploadLength) {
      const error = new Error('TUS_UPLOAD_NOT_READY_TO_FINALIZE')
      error.status = 409
      throw error
    }
    return finalizeResumableUpload(pool, req, upload)
  } finally {
    if (acquired) {
      await client.query(
        `SELECT pg_advisory_unlock(hashtextextended($1::text, 0))`,
        [uploadId],
      ).catch(() => {})
    }
    client.release()
  }
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

  let validated = await validateUpload({ ...options, bucket, objectPath })
  if ((bucket === 'videos' || messagingBuckets.has(bucket)) &&
      validated.contentType === 'video/mp4' && options.sourcePath) {
    // Chat attachments may be longer than feed clips, but their MP4 index
    // must be at the front so Media3 can start without downloading the end.
    const optimizedSizeBytes = await optimizeMp4ForProgressiveDownload(options.sourcePath, {
      enforceFeedLimits: bucket === 'videos',
      maxBytes: uploadLimitForBucket(bucket),
    })
    validated = { ...validated, sizeBytes: optimizedSizeBytes }
  }

  const existingResult = await pool.query(
    `SELECT owner_id FROM fallback_storage_objects WHERE bucket_id = $1 AND name = $2 LIMIT 1`,
    [bucket, objectPath],
  )
  const existingOwner = String(existingResult.rows[0]?.owner_id || '').trim()
  if (existingResult.rows[0] && !existingOwner) {
    const error = new Error('STORAGE_OBJECT_OWNER_UNKNOWN')
    error.status = 403
    throw error
  }
  if (existingOwner && existingOwner !== ownerId) {
    const error = new Error('STORAGE_OBJECT_OWNED_BY_ANOTHER_USER')
    error.status = 403
    throw error
  }
  if (existingResult.rows[0] && await publishedContentUsesObject(pool, bucket, objectPath)) {
    const error = new Error('STORAGE_OBJECT_IN_USE')
    error.status = 409
    throw error
  }
  if (existingResult.rows[0] && options.allowOverwrite === false) {
    const error = new Error('STORAGE_OBJECT_ALREADY_EXISTS')
    error.status = 409
    throw error
  }

  const destination = objectFilePath(bucket, objectPath)
  await fs.promises.mkdir(path.dirname(destination), { recursive: true })
  const publicUrl = publicObjectUrl(req, bucket, objectPath)
  const destinationExists = existingResult.rows[0]
    ? await fs.promises.stat(destination).then((stat) => stat.isFile()).catch(() => false)
    : false
  const backupPath = destinationExists
    ? `${destination}.replace-backup-${crypto.randomUUID()}`
    : null
  let installed = false
  let storedRow

  if (backupPath) await fs.promises.rename(destination, backupPath)
  try {
    if (options.sourcePath) {
      await fs.promises.rename(options.sourcePath, destination)
    } else {
      await fs.promises.writeFile(destination, options.buffer || Buffer.alloc(0))
    }
    installed = true

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
    storedRow = rows[0]
    if (!storedRow) {
      const error = new Error('STORAGE_OBJECT_OWNED_BY_ANOTHER_USER')
      error.status = 403
      throw error
    }
  } catch (error) {
    if (installed) await fs.promises.rm(destination, { force: true }).catch(() => {})
    if (backupPath) await fs.promises.rename(backupPath, destination).catch(() => {})
    throw error
  }

  if (existingResult.rows[0]) {
    await removeImageVariants(bucket, objectPath).catch((error) => {
      // The object and its database row are already committed. Failing the
      // request here would tell the client that a successful upload failed and
      // encourage an unsafe retry; stale previews can be regenerated lazily.
      console.warn('[storage] stale image variant cleanup failed', {
        bucket,
        objectRef: stableKey(`${bucket}/${objectPath}`),
        ...safeErrorLogDetails(error),
      })
    })
  }
  if (backupPath) await fs.promises.rm(backupPath, { force: true }).catch(() => {})

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
          objectRef: stableKey(`${bucket}/${objectPath}`),
          ...safeErrorLogDetails(error),
        })
      })
  }
  return storedRow
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
  const filePath = storedObjectFilePath(row, bucket, objectPath)
  await fs.promises.access(filePath, fs.constants.R_OK)
  const stat = await fs.promises.stat(filePath)
  const requestedVariant = requestedImageVariant(req.query, row?.content_type)
  const { responsePath, servedVariant, variantError } = await resolveImageVariantResponse({
    bucket,
    objectPath,
    filePath,
    stat,
    variant: requestedVariant,
  })
  if (variantError) {
    console.warn('[storage] image variant unavailable; serving original', {
      bucket,
      objectRef: stableKey(`${bucket}/${objectPath}`),
      width: requestedVariant.width,
      quality: requestedVariant.quality,
      ...safeErrorLogDetails(variantError),
    })
  }
  const responseStat = servedVariant ? await fs.promises.stat(responsePath) : stat
  const lastModified = row?.updated_at ? new Date(row.updated_at) : stat.mtime
  const variantIdentity = servedVariant
    ? `:${servedVariant.width}:${servedVariant.quality}:${servedVariant.format}`
    : ''
  const etag = `"${Buffer.from(`${bucket}:${objectPath}:${stat.size}:${Math.trunc(stat.mtimeMs)}${variantIdentity}`).toString('base64url')}"`
  res.setHeader('Access-Control-Allow-Origin', '*')
  res.setHeader('Cross-Origin-Resource-Policy', 'cross-origin')
  res.setHeader('Content-Type', servedVariant ? 'image/webp' : row?.content_type || 'application/octet-stream')
  res.setHeader('Cache-Control', storageCacheControl(objectPath))
  res.setHeader('ETag', etag)
  res.setHeader('Last-Modified', lastModified.toUTCString())
  res.setHeader('Accept-Ranges', 'bytes')
  res.setHeader('Content-Length', String(responseStat.size))
  res.setHeader(
    'Content-Disposition',
    `inline; filename="${contentDispositionName(servedVariant ? `${objectPath}.webp` : objectPath)}"`,
  )
  if (etagMatches(req.headers['if-none-match'], etag)) {
    res.status(304)
    return res.end()
  }
  if (storageAccelPrefix) {
    // Authorization, signed-URL validation and object lookup remain in the
    // API. Nginx performs only the internal file transfer, preserving byte
    // ranges while keeping the physical storage path unreachable publicly.
    delegateStoredObjectToEdge(res, responsePath)
    return
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
      // Legacy objects without an owner are not claimable by every logged-in user.
      if (!row.owner_id || row.owner_id !== req.auth.sub) {
        return res.status(403).json({ error: 'FORBIDDEN' })
      }
      if (await publishedContentUsesObject(pool, bucket, objectPath)) {
        return res.status(409).json({ error: 'STORAGE_OBJECT_IN_USE' })
      }

      await pool.query(
        `DELETE FROM fallback_storage_objects
         WHERE bucket_id = $1 AND name = $2`,
        [bucket, objectPath],
      )

      const filePath = storedObjectFilePath(row, bucket, objectPath)
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

  const resumableCollectionRoute = '/storage/v1/upload/resumable'
  const resumableItemRoute = /^\/storage\/v1\/upload\/resumable\/([0-9a-f-]{36})$/i
  const sendTusError = (res, error) => {
    setTusHeaders(res)
    res.status(Number(error?.status || 500)).json({ error: error?.message || 'RESUMABLE_UPLOAD_FAILED' })
  }

  app.options(resumableCollectionRoute, (_req, res) => {
    setTusHeaders(res)
    res.status(204).end()
  })

  app.post(resumableCollectionRoute, authRequired, async (req, res) => {
    let uploadId = ''
    try {
      requireTusRequest(req)
      const uploadLength = parseNonNegativeInteger(req.headers['upload-length'])
      if (uploadLength == null || uploadLength <= 0) {
        const error = new Error('TUS_UPLOAD_LENGTH_REQUIRED')
        error.status = 400
        throw error
      }
      const metadata = parseTusMetadata(req.headers['upload-metadata'])
      const bucket = cleanBucketName(metadata.bucketname)
      const objectPath = normalizeObjectPath(metadata.objectname, '')
      const contentType = canonicalContentType(metadata.contenttype)
      if (!bucket || !allowedBuckets.has(bucket) || !objectPath || !metadata.contenttype || !contentType) {
        const error = new Error('INVALID_TUS_UPLOAD_METADATA')
        error.status = 400
        throw error
      }
      if (uploadLength > uploadLimitForBucket(bucket)) {
        const error = new Error('UPLOAD_TOO_LARGE')
        error.status = 413
        throw error
      }

      await cleanupExpiredResumableUploads(pool).catch((error) => {
        console.warn('[storage] resumable cleanup failed', safeErrorLogDetails(error))
      })
      uploadId = crypto.randomUUID()
      const tempPath = resumableUploadFilePath(uploadId)
      const expiresAt = new Date(Date.now() + resumableUploadLifetimeMs)
      await fs.promises.mkdir(resumableTempDir, { recursive: true })
      await fs.promises.writeFile(tempPath, Buffer.alloc(0), { flag: 'wx' })
      try {
        await pool.query(
          `INSERT INTO fallback_resumable_uploads(
             id, owner_id, bucket_id, object_name, content_type, upload_length,
             upload_metadata, allow_overwrite, expires_at
           ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)`,
          [
            uploadId,
            req.auth.sub,
            bucket,
            objectPath,
            contentType,
            uploadLength,
            String(req.headers['upload-metadata'] || ''),
            String(req.headers['x-upsert'] || 'true').toLowerCase() !== 'false',
            expiresAt,
          ],
        )
      } catch (error) {
        await fs.promises.unlink(tempPath).catch(() => {})
        throw error
      }

      setTusHeaders(res, { uploadLength, uploadOffset: 0, uploadExpires: expiresAt })
      res.setHeader('Location', `${resumableCollectionRoute}/${uploadId}`)
      res.status(201).end()
    } catch (error) {
      if (uploadId) await fs.promises.unlink(resumableUploadFilePath(uploadId)).catch(() => {})
      sendTusError(res, error)
    }
  })

  app.head(resumableItemRoute, authRequired, async (req, res) => {
    try {
      requireTusRequest(req)
      const uploadId = String(req.params[0] || '').toLowerCase()
      const { rows } = await pool.query(
        `SELECT * FROM fallback_resumable_uploads WHERE id = $1 AND owner_id = $2 LIMIT 1`,
        [uploadId, req.auth.sub],
      )
      const upload = mapResumableUploadRow(rows[0])
      if (!upload.id) {
        const error = new Error('TUS_UPLOAD_NOT_FOUND')
        error.status = 404
        throw error
      }
      if (new Date(upload.expiresAt).getTime() <= Date.now()) {
        await pool.query(`DELETE FROM fallback_resumable_uploads WHERE id = $1`, [upload.id])
        await fs.promises.unlink(resumableUploadFilePath(upload.id)).catch(() => {})
        const error = new Error('TUS_UPLOAD_EXPIRED')
        error.status = 410
        throw error
      }
      if (upload.state === 'rejected') {
        const error = new Error('TUS_UPLOAD_REJECTED')
        error.status = 410
        throw error
      }
      const completedObject = upload.state === 'finalizing'
        ? await finalizeResumableUploadWithLock(pool, req, upload.id)
        : null
      const effectiveState = completedObject || upload.state === 'completed' ? 'completed' : upload.state
      setTusHeaders(res, {
        uploadLength: upload.uploadLength,
        uploadOffset: upload.uploadOffset,
        uploadExpires: effectiveState === 'completed' ? null : upload.expiresAt,
        objectPath: completedObject?.name || upload.storedObjectPath,
      })
      res.setHeader('Upload-State', effectiveState)
      res.status(200).end()
    } catch (error) {
      sendTusError(res, error)
    }
  })

  app.patch(resumableItemRoute, authRequired, async (req, res) => {
    try {
      requireTusRequest(req)
      if (canonicalContentType(req.headers['content-type']) !== 'application/offset+octet-stream') {
        const error = new Error('TUS_CONTENT_TYPE_REQUIRED')
        error.status = 415
        throw error
      }
      const uploadId = String(req.params[0] || '').toLowerCase()
      const requestedOffset = parseNonNegativeInteger(req.headers['upload-offset'])
      if (requestedOffset == null) {
        const error = new Error('TUS_UPLOAD_OFFSET_REQUIRED')
        error.status = 400
        throw error
      }

      const result = await withResumableUploadTransaction(pool, async (client) => {
        const { rows } = await client.query(
          `SELECT * FROM fallback_resumable_uploads
            WHERE id = $1 AND owner_id = $2
            FOR UPDATE`,
          [uploadId, req.auth.sub],
        )
        const upload = mapResumableUploadRow(rows[0])
        if (!upload.id) {
          const error = new Error('TUS_UPLOAD_NOT_FOUND')
          error.status = 404
          throw error
        }
        if (new Date(upload.expiresAt).getTime() <= Date.now()) {
          await client.query(`DELETE FROM fallback_resumable_uploads WHERE id = $1`, [upload.id])
          await fs.promises.unlink(resumableUploadFilePath(upload.id)).catch(() => {})
          const error = new Error('TUS_UPLOAD_EXPIRED')
          error.status = 410
          throw error
        }
        if (upload.state === 'completed') return { upload, completed: true }
        if (upload.state !== 'uploading') {
          const error = new Error(upload.state === 'rejected' ? 'TUS_UPLOAD_REJECTED' : 'TUS_UPLOAD_FINALIZING')
          error.status = upload.state === 'rejected' ? 410 : 409
          throw error
        }
        if (requestedOffset !== upload.uploadOffset) {
          const error = new Error('TUS_OFFSET_CONFLICT')
          error.status = 409
          error.upload = upload
          throw error
        }

        const tempPath = resumableUploadFilePath(upload.id)
        const stat = await fs.promises.stat(tempPath).catch(() => null)
        if (!stat || !stat.isFile() || stat.size !== upload.uploadOffset) {
          const error = new Error('TUS_UPLOAD_STATE_CORRUPTED')
          error.status = 500
          throw error
        }
        const chunkSize = await streamResumableChunk(req, tempPath, {
          offset: upload.uploadOffset,
          maxBytes: Math.min(resumableChunkBytes, upload.uploadLength - upload.uploadOffset),
        })
        const uploadOffset = upload.uploadOffset + chunkSize
        const finalizing = uploadOffset === upload.uploadLength
        const expiresAt = new Date(Date.now() + resumableUploadLifetimeMs)
        await client.query(
          `UPDATE fallback_resumable_uploads
              SET upload_offset = $2, state = $3, expires_at = $4, updated_at = NOW()
            WHERE id = $1`,
          [upload.id, uploadOffset, finalizing ? 'finalizing' : 'uploading', expiresAt],
        )
        return {
          upload: { ...upload, uploadOffset, expiresAt, state: finalizing ? 'finalizing' : 'uploading' },
          finalize: finalizing,
        }
      })

      let completedObject = null
      if (result.finalize) completedObject = await finalizeResumableUploadWithLock(pool, req, result.upload.id)
      const upload = result.upload
      setTusHeaders(res, {
        uploadLength: upload.uploadLength,
        uploadOffset: upload.uploadOffset,
        uploadExpires: completedObject || upload.state === 'completed' ? null : upload.expiresAt,
        objectPath: completedObject?.name || upload.storedObjectPath,
      })
      res.setHeader('Upload-State', completedObject || upload.state === 'completed' ? 'completed' : upload.state)
      res.status(204).end()
    } catch (error) {
      if (error?.upload) {
        setTusHeaders(res, {
          uploadLength: error.upload.uploadLength,
          uploadOffset: error.upload.uploadOffset,
          uploadExpires: error.upload.expiresAt,
        })
      }
      sendTusError(res, error)
    }
  })

  app.delete(resumableItemRoute, authRequired, async (req, res) => {
    try {
      requireTusRequest(req)
      const uploadId = String(req.params[0] || '').toLowerCase()
      const { rows } = await pool.query(
        `DELETE FROM fallback_resumable_uploads
          WHERE id = $1 AND owner_id = $2
          RETURNING id, state`,
        [uploadId, req.auth.sub],
      )
      if (!rows[0]) {
        const error = new Error('TUS_UPLOAD_NOT_FOUND')
        error.status = 404
        throw error
      }
      if (String(rows[0].state || '') !== 'completed') {
        await fs.promises.unlink(resumableUploadFilePath(uploadId)).catch(() => {})
      }
      setTusHeaders(res)
      res.status(204).end()
    } catch (error) {
      sendTusError(res, error)
    }
  })

  const objectWriteRoute = /^\/storage\/v1\/object\/([^/]+)\/(.+)$/

  app.post(/^\/storage\/v1\/object\/sign\/([^/]+)\/(.+)$/, authRequired, async (req, res, next) => {
    try {
      const bucket = cleanBucketName(req.params[0])
      const objectPath = normalizeObjectPath(decodeURIComponent(req.params[1] || ''), '')
      if (!bucket || !objectPath) return res.status(400).json({ error: 'INVALID_STORAGE_PATH' })
      const { rows } = await pool.query(
        `SELECT * FROM fallback_storage_objects WHERE bucket_id = $1 AND name = $2 LIMIT 1`,
        [bucket, objectPath],
      )
      if (!rows[0]) return res.status(404).json({ error: 'NOT_FOUND' })
      if (!(await maySignStoredObject(pool, req, rows[0]))) return res.status(403).json({ error: 'FORBIDDEN' })
      const signedURL = signedOnlyBuckets.has(bucket)
        ? signedObjectUrl(req, bucket, objectPath, req.body?.expiresIn)
        : publicObjectUrl(req, bucket, objectPath)
      if (!signedURL) return res.status(503).json({ error: 'STORAGE_SIGNING_NOT_CONFIGURED' })
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
      if (signedOnlyBuckets.has(cleanBucketName(req.params[0]))) return res.status(404).json({ error: 'NOT_FOUND' })
      await serveStoredObject(pool, req, res, req.params[0], req.params[1])
    } catch (error) {
      if (error?.code === 'ENOENT') return res.status(404).json({ error: 'NOT_FOUND' })
      next(error)
    }
  })

  app.get(/^\/storage\/v1\/object\/sign\/([^/]+)\/(.+)$/, async (req, res, next) => {
    try {
      const bucket = cleanBucketName(req.params[0])
      const objectPath = normalizeObjectPath(decodeURIComponent(req.params[1] || ''), '')
      if (!bucket || !objectPath || !signedOnlyBuckets.has(bucket)) {
        return res.status(404).json({ error: 'NOT_FOUND' })
      }
      if (!verifyStorageObjectToken(bucket, objectPath, req.query?.token)) {
        return res.status(401).json({ error: 'SIGNED_URL_INVALID_OR_EXPIRED' })
      }
      await serveStoredObject(pool, req, res, bucket, objectPath)
    } catch (error) {
      if (error?.code === 'ENOENT') return res.status(404).json({ error: 'NOT_FOUND' })
      next(error)
    }
  })

  app.get(/^\/storage\/v1\/object\/([^/]+)\/(.+)$/, async (req, res, next) => {
    try {
      if (signedOnlyBuckets.has(cleanBucketName(req.params[0]))) return res.status(404).json({ error: 'NOT_FOUND' })
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
  delegateStoredObjectToEdge,
  etagMatches,
  inferContentType,
  requestedImageVariant,
  parseFrameRate,
  optimizeMp4ForProgressiveDownload,
  progressiveMp4RemuxArgs,
  probeVideoDelivery,
  parseStorageObjectUrl,
  objectFilePath,
  parseNonNegativeInteger,
  parseTusMetadata,
  storedObjectFilePath,
  resumableUploadFilePath,
  signStoredObjectReference,
  signedObjectUrl,
  storageAccelRedirect,
  storageCacheControl,
  streamResumableChunk,
  verifyStorageObjectToken,
  validateUpload,
  validateVideoDeliveryMetadata,
  upsertStoredObject,
  ensureLocalStorageTables,
  cleanupExpiredResumableUploads,
  removeStorageObjectFiles,
  resolveImageVariantResponse,
  registerStorageRoutes,
}
