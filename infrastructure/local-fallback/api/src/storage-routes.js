const fs = require('fs')
const path = require('path')
const express = require('express')
const multer = require('multer')
const crypto = require('crypto')
const zlib = require('zlib')
const sharp = require('sharp')
const { boundedWorkQueue } = require('./bounded-work-queue')
const { createStorageObjectReadGuard } = require('./storage-read-access')
const { storageReadConfiguration } = require('./storage-read-config')
const storageReadConfig = storageReadConfiguration()
const { normalizeImageVariant } = require('./image-variant-policy')
const { createStorageScanCoordinator, cleanStorageRow, revisionFilePath, ensureStorageScanSchema, STORAGE_SCAN_TICKET } = require('./storage-scan-coordinator')
const SCAN_COORDINATOR = Symbol('trusted-storage-coordinator')
const { execFile } = require('child_process')
const { promisify } = require('util')
const { Transform } = require('stream')
const { pipeline } = require('stream/promises')
const { stableKey } = require('./request-security')
const { createStorageUploadAdmission, acquireStorageFinalizationClient, ensureStorageUploadAdmissionSchema } = require('./storage-upload-admission')
const UPLOAD_RESERVATION = Symbol('server-owned-upload-reservation')
const FINALIZATION_CONNECTION = Symbol('storage-finalization-connection')
const FINALIZATION_ALIVE = Symbol('storage-finalization-lock-alive')
const { safeErrorLogDetails } = require('./request-observability')
const { validateMessagingUpload } = require('./messaging-upload-policy')

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
const defaultScanCoordinator = createStorageScanCoordinator({ root: storageRoot })
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
  storage: multer.diskStorage({
    destination: (_req, _file, callback) => callback(null, tempDir),
    filename: (req, _file, callback) => callback(null, `admitted-${req[UPLOAD_RESERVATION]?.id || crypto.randomUUID()}-${crypto.randomUUID()}`),
  }),
  limits: {
    fileSize: maxUploadBytes,
    files: 1, fields: 4, parts: 5, fieldSize: 2048, fieldNameSize: 64, headerPairs: 20,
  },
})

const bucketUploadLimits = new Map([
  ['avatars', 50 * 1024 * 1024],
  ['chat-media', 500 * 1024 * 1024],
  ['group-media', 500 * 1024 * 1024],
  ['community-media', 500 * 1024 * 1024],
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
  'application/vnd.ms-excel',
  'application/msword',
  'application/vnd.ms-powerpoint',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  'application/vnd.ms-excel.sheet.macroenabled.12',
  'application/vnd.ms-excel.sheet.binary.macroenabled.12',
  'application/vnd.ms-word.document.macroenabled.12',
  'application/vnd.ms-powerpoint.presentation.macroenabled.12',
  'application/vnd.oasis.opendocument.text',
  'application/vnd.oasis.opendocument.spreadsheet',
  'application/vnd.oasis.opendocument.presentation',
  'application/rtf',
  'application/epub+zip',
  'text/plain',
  'text/csv',
  'text/tab-separated-values',
])
const documentExtensions = new Set([
  'pdf', 'zip', 'doc', 'docx', 'docm',
  'xls', 'xlsx', 'xlsm', 'xlsb',
  'ppt', 'pptx', 'pptm', 'odt', 'ods',
  'odp', 'rtf', 'epub', 'txt', 'csv', 'tsv',
])
const blockedMessageAttachmentExtensions = new Set([
  'exe', 'dll', 'com', 'scr', 'msi', 'bat', 'cmd', 'ps1', 'psm1', 'sh', 'bash',
  'zsh', 'fish', 'vbs', 'vbe', 'js', 'mjs', 'cjs', 'hta', 'html', 'htm', 'svg',
  'wasm', 'class', 'so', 'dylib', 'elf',
])
const blockedMessageAttachmentMimes = new Set([
  'text/html', 'application/xhtml+xml', 'image/svg+xml', 'text/javascript',
  'application/javascript', 'application/x-javascript', 'application/wasm',
  'application/x-msdownload', 'application/x-executable', 'application/x-sh',
  'application/x-httpd-php',
])
const messagingBuckets = new Set(['chat-media', 'group-media', 'community-media', 'groups', 'communities'])
const imageVariantRoot = path.join(storageRoot, '_variants')
const imageVariantJobs = new Map()
const imageVariantQueue = boundedWorkQueue({ concurrency: 2, maxQueued: 16, queueWaitMs: 5000 })
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

async function probeVideoDelivery(sourcePath, sizeBytes, runProcess = execFileAsync) {
  let parsed
  try {
    const { stdout } = await runProcess(
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

function normalizeMp4AudioCodecs(codecs) {
  if (!Array.isArray(codecs) || codecs.some((codec) =>
    typeof codec !== 'string' || !/^[a-z0-9_]+$/i.test(codec.trim()))) {
    const error = new Error('VIDEO_AUDIO_METADATA_INVALID')
    error.status = 415
    throw error
  }
  return codecs.map((codec) => codec.trim().toLowerCase())
}

function parseMp4AudioCodecs(metadata) {
  if (!Array.isArray(metadata?.streams)) return normalizeMp4AudioCodecs(null)
  return normalizeMp4AudioCodecs(metadata.streams.map((stream) => stream?.codec_name))
}

async function probeMp4AudioCodecs(sourcePath, runProcess = execFileAsync) {
  try {
    const { stdout } = await runProcess('ffprobe', [
      '-v', 'error', '-select_streams', 'a',
      '-show_entries', 'stream=codec_name', '-of', 'json', sourcePath,
    ], { timeout: 20_000, maxBuffer: 1024 * 1024 })
    return parseMp4AudioCodecs(JSON.parse(stdout))
  } catch (cause) {
    const error = new Error('VIDEO_DECODE_FAILED')
    error.status = 415
    error.cause = cause
    throw error
  }
}

function progressiveMp4RemuxArgs(inputPath, outputPath, audioCodecs = []) {
  // Keep the video and existing AAC packets intact. Other camera audio codecs
  // (notably AMR in 3GP-branded MP4 uploads) need AAC for mobile MP4 delivery.
  // Ordinals match -map 0:a?: all audio tracks are retained in their input order.
  const audioArgs = normalizeMp4AudioCodecs(audioCodecs).flatMap((codec, index) =>
    codec === 'aac' ? [] : [
      `-c:a:${index}`, 'aac', `-profile:a:${index}`, 'aac_low', `-b:a:${index}`, '128k',
    ])
  return [
    '-hide_banner', '-loglevel', 'error', '-y',
    '-i', inputPath,
    '-map', '0:v:0', '-map', '0:a?',
    '-c', 'copy',
    ...audioArgs,
    '-movflags', '+faststart',
    outputPath,
  ]
}

async function optimizeMp4ForProgressiveDownload(sourcePath, {
  enforceFeedLimits = true, maxBytes = Infinity, runProcess = execFileAsync,
} = {}) {
  const outputPath = `${sourcePath}.faststart-${crypto.randomUUID()}.mp4`
  try {
    const audioCodecs = await probeMp4AudioCodecs(sourcePath, runProcess)
    await runProcess(
      'ffmpeg',
      progressiveMp4RemuxArgs(sourcePath, outputPath, audioCodecs),
      { timeout: 120_000, maxBuffer: 2 * 1024 * 1024 },
    )
    const optimizedStat = await fs.promises.stat(outputPath)
    if (optimizedStat.size <= 0 || optimizedStat.size > maxBytes) {
      const error = new Error('UPLOAD_TOO_LARGE')
      error.status = 413
      throw error
    }
    if (enforceFeedLimits) await probeVideoDelivery(outputPath, optimizedStat.size, runProcess)
    // Replace only after successful normalization and validation. A failed
    // rename must leave the original upload available for caller cleanup.
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
  return normalizeImageVariant(query, canonicalContentType(contentType))
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
    .timeout({ seconds: 10 })
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
    const job = imageVariantQueue.run(async () => {
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
    }).finally(() => imageVariantJobs.delete(outputPath))
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
  if (startsWith(header, [0x7b, 0x5c, 0x72, 0x74, 0x66])) return 'application/rtf'
  if (startsWith(header, [0x50, 0x4b, 0x03, 0x04]) || startsWith(header, [0x50, 0x4b, 0x05, 0x06])) {
    if (extension === 'docx') return 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
    if (extension === 'xlsx') return 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
    if (extension === 'pptx') return 'application/vnd.openxmlformats-officedocument.presentationml.presentation'
    if (extension === 'xlsm') return 'application/vnd.ms-excel.sheet.macroenabled.12'
    if (extension === 'xlsb') return 'application/vnd.ms-excel.sheet.binary.macroenabled.12'
    if (extension === 'docm') return 'application/vnd.ms-word.document.macroenabled.12'
    if (extension === 'pptm') return 'application/vnd.ms-powerpoint.presentation.macroenabled.12'
    if (extension === 'odt') return 'application/vnd.oasis.opendocument.text'
    if (extension === 'ods') return 'application/vnd.oasis.opendocument.spreadsheet'
    if (extension === 'odp') return 'application/vnd.oasis.opendocument.presentation'
    if (extension === 'epub') return 'application/epub+zip'
    return 'application/zip'
  }
  if (startsWith(header, [0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1])) {
    if (extension === 'xls') return 'application/vnd.ms-excel'
    if (extension === 'doc') return 'application/msword'
    if (extension === 'ppt') return 'application/vnd.ms-powerpoint'
    return 'application/x-ole-storage'
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
  if (['txt', 'csv', 'tsv'].includes(extension) && Buffer.isBuffer(header)) {
    const sample = header.subarray(0, 512)
    const hasNull = sample.includes(0)
    const controlBytes = [...sample].filter((byte) => byte < 9 || (byte > 13 && byte < 32)).length
    if (!hasNull && controlBytes <= Math.max(1, Math.floor(sample.length * 0.02))) {
      return extension === 'csv' ? 'text/csv' : extension === 'tsv' ? 'text/tab-separated-values' : 'text/plain'
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

  if (['video-comments-media', 'post-comments-media'].includes(bucket)) imageTypes.forEach((type) => allowed.add(type))
  if (bucket === 'avatars') allowed.add('video/mp4')
  if (['post-media', 'moment-media'].includes(bucket)) {
    imageTypes.forEach((type) => allowed.add(type))
    allowed.delete('image/gif')
    allowed.add('video/mp4')
  }
  if (bucket === 'videos' || bucket === 'live-replays') allowed.add('video/mp4')
  if (['group-audio', 'profile-audio', 'community-music'].includes(bucket)) {
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

// Confirm a renamed OOXML Excel package before treating it as an .xlsx/.xlsm.
async function verifiedMisnamedExcelPackage(options) {
  const size = Number(options.sizeBytes || options.buffer?.length || 0)
  if (!Number.isSafeInteger(size) || size < 22 || size > 128 * 1024 * 1024) return null
  let handle = null
  const readAt = async (length, position) => {
    if (!Number.isSafeInteger(length) || length < 0 || !Number.isSafeInteger(position) ||
        position < 0 || position + length > size) return Buffer.alloc(0)
    if (Buffer.isBuffer(options.buffer)) return options.buffer.subarray(position, position + length)
    if (!options.sourcePath) return Buffer.alloc(0)
    if (!handle) handle = await fs.promises.open(options.sourcePath, 'r')
    const buffer = Buffer.alloc(length)
    const { bytesRead } = await handle.read(buffer, 0, length, position)
    return buffer.subarray(0, bytesRead)
  }
  const readPackageXml = async (member, maxCompressedBytes, maxExpandedBytes, directoryOffset) => {
    if (!member || member.compressedSize > maxCompressedBytes || member.uncompressedSize > maxExpandedBytes ||
        ![0, 8].includes(member.method)) return null
    const local = await readAt(30, member.localOffset)
    if (local.length !== 30 || local.readUInt32LE(0) !== 0x04034b50) return null
    const localFlags = local.readUInt16LE(6)
    const localMethod = local.readUInt16LE(8)
    const nameLength = local.readUInt16LE(26)
    const extraLength = local.readUInt16LE(28)
    if (localFlags !== member.flags || localMethod !== member.method || (localFlags & 1) || nameLength !== member.nameBytes.length) return null
    const localName = await readAt(nameLength, member.localOffset + 30)
    if (!localName.equals(member.nameBytes)) return null
    const dataOffset = member.localOffset + 30 + nameLength + extraLength
    if (dataOffset + member.compressedSize > directoryOffset) return null
    const compressed = await readAt(member.compressedSize, dataOffset)
    if (compressed.length !== member.compressedSize) return null
    const xml = member.method === 0
      ? compressed
      : zlib.inflateRawSync(compressed, { maxOutputLength: maxExpandedBytes })
    if (xml.length !== member.uncompressedSize) return null
    const text = xml.toString('utf8')
    if (text.includes('\uFFFD') || /<!DOCTYPE|<!ENTITY/i.test(text)) return null
    return text
  }
  try {
    const tailLength = Math.min(size, 65_557)
    const tail = await readAt(tailLength, size - tailLength)
    let end = -1
    for (let i = tail.length - 22; i >= Math.max(0, tail.length - 65_557); i--) {
      if (tail.readUInt32LE(i) === 0x06054b50) { end = i; break }
    }
    if (end < 0 || tail.readUInt16LE(end + 4) || tail.readUInt16LE(end + 6)) return null
    const diskCount = tail.readUInt16LE(end + 8)
    const count = tail.readUInt16LE(end + 10)
    const directorySize = tail.readUInt32LE(end + 12)
    const directoryOffset = tail.readUInt32LE(end + 16)
    const commentLength = tail.readUInt16LE(end + 20)
    if (commentLength !== tail.length - end - 22 || diskCount !== count || count < 2 || count > 4096 ||
        directorySize > 1024 * 1024 || directoryOffset + directorySize > size - tailLength + end) return null
    const directory = await readAt(directorySize, directoryOffset)
    if (directory.length !== directorySize) return null
    let cursor = 0
    const members = new Map()
    for (let i = 0; i < count; i++) {
      if (cursor + 46 > directory.length || directory.readUInt32LE(cursor) !== 0x02014b50) return null
      const flags = directory.readUInt16LE(cursor + 8)
      const method = directory.readUInt16LE(cursor + 10)
      const compressedSize = directory.readUInt32LE(cursor + 20)
      const uncompressedSize = directory.readUInt32LE(cursor + 24)
      const nameLength = directory.readUInt16LE(cursor + 28)
      const extraLength = directory.readUInt16LE(cursor + 30)
      const commentLength = directory.readUInt16LE(cursor + 32)
      const localOffset = directory.readUInt32LE(cursor + 42)
      const next = cursor + 46 + nameLength + extraLength + commentLength
      if (next > directory.length || (flags & 1) || compressedSize === 0xFFFFFFFF ||
          uncompressedSize === 0xFFFFFFFF || localOffset >= directoryOffset) return null
      const nameBytes = directory.subarray(cursor + 46, cursor + 46 + nameLength)
      const name = nameBytes.toString('utf8')
      if (name === 'xl/workbook.xml' || name === '[Content_Types].xml') {
        members.set(name, { name, nameBytes: Buffer.from(nameBytes), flags, method, compressedSize, uncompressedSize, localOffset })
      }
      cursor = next
    }
    if (cursor !== directory.length) return null
    const manifest = await readPackageXml(members.get('[Content_Types].xml'), 64 * 1024, 128 * 1024, directoryOffset)
    const workbook = await readPackageXml(members.get('xl/workbook.xml'), 2 * 1024 * 1024, 4 * 1024 * 1024, directoryOffset)
    if (!manifest || !workbook || !/<(?:[A-Za-z0-9_]+:)?Types\b/i.test(manifest) ||
        !/<(?:[A-Za-z0-9_]+:)?workbook\b/i.test(workbook) ||
        !/schemas\.openxmlformats\.org\/spreadsheetml\/2006\/main|purl\.oclc\.org\/ooxml\/spreadsheetml\/main/i.test(workbook)) return null
    const workbookOverride = manifest.match(/<(?:[A-Za-z0-9_]+:)?Override\b[^>]*\bPartName=["']\/xl\/workbook\.xml["'][^>]*>/i)?.[0]
    if (!workbookOverride) return null
    const contentType = workbookOverride.match(/\bContentType=["']([^"']+)["']/i)?.[1]?.toLowerCase()
    if (contentType === 'application/vnd.ms-excel.sheet.macroenabled.main+xml') {
      return { contentType: 'application/vnd.ms-excel.sheet.macroenabled.12', extension: 'xlsm' }
    }
    if (contentType === 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml') {
      return { contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', extension: 'xlsx' }
    }
    return null
  } catch {
    return null
  } finally {
    await handle?.close().catch(() => {})
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
  const excelPackage = extension === 'xls' && inferredType === 'application/zip' &&
    declaredType === 'application/vnd.ms-excel'
    ? await verifiedMisnamedExcelPackage(options)
    : null
  if (excelPackage) inferredType = excelPackage.contentType
  const isMessagingBucket = ['chat-media', 'group-media', 'community-media'].includes(bucket)
  const safeOpaqueMime = (declaredType === 'application/octet-stream' ||
    declaredType.startsWith('application/') || declaredType.startsWith('text/')) &&
    !blockedMessageAttachmentMimes.has(declaredType)
  const safeOpaqueExtension = /^[a-z0-9]{1,16}$/.test(extension) &&
    !blockedMessageAttachmentExtensions.has(extension)
  const genericFileAttachment = isMessagingBucket && safeOpaqueMime && safeOpaqueExtension
  // Unknown binary formats are carried as opaque attachments; known signatures still
  // get their exact MIME and document extensions retain signature checks below.
  if (!inferredType && genericFileAttachment && !documentExtensions.has(extension)) inferredType = declaredType
  const allowedTypes = allowedTypesForBucket(bucket)
  if (!inferredType || (!allowedTypes.has(inferredType) && !genericFileAttachment) ||
      (!excelPackage && !typesCompatible(declaredType, inferredType))) {
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
  if (['chat-media', 'group-media', 'community-media'].includes(bucket)) await validateMessagingUpload({ ...options, contentType: inferredType, sizeBytes })
  return { contentType: inferredType, sizeBytes }
}

function uploadLimitForBucket(bucket, coordinator = defaultScanCoordinator) {
  return Math.min(maxUploadBytes, bucketUploadLimits.get(bucket) || maxUploadBytes, coordinator.enforced ? coordinator.maxBytes : Infinity)
}

async function streamRequestToTemp(req, byteLimit) {
  await fs.promises.mkdir(tempDir, { recursive: true })
  const declaredLength = Number(req.headers['content-length'] || 0)
  if (declaredLength > byteLimit) {
    const error = new Error('UPLOAD_TOO_LARGE')
    error.status = 413
    throw error
  }

  const tempPath = path.join(tempDir, `admitted-${req[UPLOAD_RESERVATION]?.id || crypto.randomUUID()}-${crypto.randomUUID()}`)
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
  if (configured) return storageReadConfig.enforced ? new URL(configured).origin : configured
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
  const expected = row?.object_revision ? revisionFilePath(storageRoot, row.object_revision) : objectFilePath(bucket, objectPath)
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
  await ensureStorageScanSchema(pool)
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
  await ensureStorageUploadAdmissionSchema(pool)
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
  const reconciled = await createStorageUploadAdmission({ pool, root: storageRoot }).reconcileExpired()
  // Unfinished uploads are removed only by reservation reconciliation after
  // acquiring the same lease held through PATCH/finalization. Never delete an
  // active file merely because its wall-clock expiration passed.
  const { rows } = await pool.query(`DELETE FROM fallback_resumable_uploads
    WHERE state='completed' AND updated_at <= NOW() - ($1::text)::interval RETURNING id`,
    [`${resumableCompletedRetentionMs} milliseconds`])
  return reconciled.released + (rows?.length || 0)
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
        WHERE id = $1 AND state = 'finalizing'`,
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
          WHERE id = $1 AND state = 'finalizing'`,
        [upload.id],
      ).catch(() => {})
    } else {
      await pool.query(
        `UPDATE fallback_resumable_uploads
            SET state = 'finalizing', expires_at = NOW() + ($2::text)::interval, updated_at = NOW()
          WHERE id = $1 AND state = 'finalizing'`,
        [upload.id, `${resumableUploadLifetimeMs} milliseconds`],
      ).catch(() => {})
    }
    throw error
  }
}

async function finalizeResumableUploadWithLock(pool, req, uploadId) {
  const client = await (req[FINALIZATION_CONNECTION] || acquireStorageFinalizationClient)(pool)
  let acquired = false, alive = true
  const previousGuard = req[FINALIZATION_ALIVE]
  const lostError = () => Object.assign(new Error('STORAGE_UPLOAD_FINALIZATION_LOST'), { code: 'STORAGE_UPLOAD_FINALIZATION_LOST', status: 503, expose: true })
  const lost = () => { alive = false; const failure = lostError(); req.destroy?.(failure); req.res?.destroy?.(failure) }
  client.on?.('error', lost)
  req[FINALIZATION_ALIVE] = () => { if (!alive) throw lostError() }
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
    return await finalizeResumableUpload(pool, req, upload)
  } finally {
    if (acquired) {
      await client.query(
        `SELECT pg_advisory_unlock(hashtextextended($1::text, 0))`,
        [uploadId],
      ).catch(() => {})
    }
    client.removeListener?.('error', lost)
    req[FINALIZATION_ALIVE] = previousGuard
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
    scanState: row.scan_state || 'legacy_unverified',
    objectRevision: row.object_revision || null,
  }
}

async function upsertStoredObject(pool, req, options) {
  req[UPLOAD_RESERVATION]?.assertAlive()
  req[FINALIZATION_ALIVE]?.()
  const coordinator = req[SCAN_COORDINATOR] || defaultScanCoordinator
  const bucket = cleanBucketName(options.bucket)
  if (!allowedBuckets.has(bucket)) throw Object.assign(new Error('BUCKET_NOT_ALLOWED'), { status: 400 })
  const ownerPrefix = safeSegment(String(options.ownerId || '').trim())[0] || ''
  const requested = normalizeObjectPath(options.objectPath, options.fallbackName)
  const objectPath = ownerPrefix && requested.split('/')[0] !== ownerPrefix ? `${ownerPrefix}/${requested}` : requested
  // Serialize the canonical bucket/path, not two different spellings of the same path.
  return coordinator.publish(pool, req, { ...options, bucket, objectPath },
    (connection, prepared) => writeValidatedStoredObject(connection, req, prepared))
}

async function writeValidatedStoredObject(pool, req, options) {
  req[UPLOAD_RESERVATION]?.assertAlive()
  req[FINALIZATION_ALIVE]?.()
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

  const scanProof = options[STORAGE_SCAN_TICKET] ? await options[STORAGE_SCAN_TICKET].seal(validated) : null
  req[UPLOAD_RESERVATION]?.assertAlive()
  req[FINALIZATION_ALIVE]?.()
  const destination = scanProof ? options[STORAGE_SCAN_TICKET].destination : objectFilePath(bucket, objectPath)
  await fs.promises.mkdir(path.dirname(destination), { recursive: true })
  const publicUrl = publicObjectUrl(req, bucket, objectPath)
  const destinationExists = existingResult.rows[0] && !scanProof
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
          metadata,
           scan_state, object_revision, scan_sha256, scan_bytes, scan_engine, scan_signature_version, scan_at, scan_policy_sha256
       )
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16)
       ON CONFLICT (bucket_id, name)
       DO UPDATE SET
          owner_id = EXCLUDED.owner_id,
          content_type = EXCLUDED.content_type,
          size_bytes = EXCLUDED.size_bytes,
          public_url = EXCLUDED.public_url,
          local_path = EXCLUDED.local_path,
          metadata = EXCLUDED.metadata,
           scan_state = EXCLUDED.scan_state, object_revision = EXCLUDED.object_revision, scan_sha256 = EXCLUDED.scan_sha256,
           scan_bytes = EXCLUDED.scan_bytes, scan_engine = EXCLUDED.scan_engine, scan_signature_version = EXCLUDED.scan_signature_version,
           scan_at = EXCLUDED.scan_at, scan_policy_sha256 = EXCLUDED.scan_policy_sha256,
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
        scanProof ? 'clean' : 'legacy_unverified', scanProof?.revision || null, scanProof?.sha256 || null,
        scanProof?.bytesScanned || null, scanProof?.engine || null, scanProof?.signatureVersion || null,
        scanProof?.scannedAt || null, scanProof?.policySha256 || null,
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

async function serveStoredObject(pool, req, res, bucketParam, pathParam, coordinator = defaultScanCoordinator, readGuard = null) {
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
  let readAdmission = null
  if (readGuard) {
    readAdmission = await readGuard(req, res, row)
    if (readAdmission?.retryAuthenticated) readAdmission = await readGuard(req, res, row)
    if (!readAdmission?.allowed) {
      if (readAdmission?.responseHandled || res.headersSent) return
      return res.status(404).json({ error: 'NOT_FOUND' })
    }
  }
  if (coordinator.enforced && !cleanStorageRow(row)) return res.status(404).json({ error: 'NOT_FOUND' })
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
  const etag = `"${Buffer.from(`${bucket}:${objectPath}:${row?.object_revision || "legacy"}:${row?.scan_sha256 || ""}:${stat.size}:${Math.trunc(stat.mtimeMs)}${variantIdentity}`).toString('base64url')}"`
  res.setHeader('Access-Control-Allow-Origin', '*')
  res.setHeader('Cross-Origin-Resource-Policy', 'cross-origin')
  res.setHeader('Content-Type', servedVariant ? 'image/webp' : row?.content_type || 'application/octet-stream')
  res.setHeader('Cache-Control', readAdmission?.private ? 'private, no-store' : storageCacheControl(objectPath))
  if (!readAdmission?.viewOnce) {
    res.setHeader('ETag', etag)
    res.setHeader('Last-Modified', lastModified.toUTCString())
  }
  if (require('./playback-cache-policy').admitsPlaybackCache(row,readAdmission)) res.setHeader('X-Novvin-Playback-Cache','permission-check-v1')
  res.setHeader('Accept-Ranges', 'bytes')
  res.setHeader('Content-Length', String(responseStat.size))
  res.setHeader(
    'Content-Disposition',
    `inline; filename="${contentDispositionName(servedVariant ? `${objectPath}.webp` : objectPath)}"`,
  )
  if (!readAdmission?.viewOnce && etagMatches(req.headers['if-none-match'], etag)) {
    res.status(304)
    return res.end()
  }
  if (readAdmission?.viewOnce) {
    // Express overrides sendFile's etag option with the application setting.
    // Stream only the already admitted window; never create cache validators,
    // conditional 304 responses or an internal edge redirect for a one-time lease.
    res.removeHeader('ETag')
    res.removeHeader('Last-Modified')
    const start = readAdmission.byteStart
    const end = readAdmission.byteEnd
    res.setHeader('Content-Length', String(end - start + 1))
    if (req.headers.range) {
      res.status(206)
      res.setHeader('Content-Range', `bytes ${start}-${end}/${responseStat.size}`)
    }
    await pipeline(fs.createReadStream(responsePath, { start, end }), res)
    return
  }
  if (storageAccelPrefix && !readAdmission?.viewOnce) {
    // Authorization, signed-URL validation and object lookup remain in the
    // API. Nginx performs only the internal file transfer, preserving byte
    // ranges while keeping the physical storage path unreachable publicly.
    delegateStoredObjectToEdge(res, responsePath)
    return
  }
  // A clean immutable revision lives in our canonical .objects directory.
  // Express ignores dot directories by default. Permit that internal path only
  // after row authorization, clean-proof verification and canonical resolution.
  // Legacy uploader paths retain the default hidden-file restriction.
  return res.sendFile(responsePath, { dotfiles: row?.object_revision ? 'allow' : 'ignore', ...(readAdmission?.viewOnce ? {etag:false,lastModified:false,cacheControl:false} : {}) })
}

async function storageScanReadiness(coordinator = defaultScanCoordinator) {
  if (!coordinator.enforced) return { enforced: false }
  const verified = await coordinator.readiness()
  return {
    enforced: true,
    engine: verified.engine,
    signatureVersion: verified.signatureVersion,
    signatureUpdatedAt: verified.signatureUpdatedAt,
  }
}

function registerStorageRoutes(app, { pool, authRequired, storageScanCoordinator = defaultScanCoordinator,
  storageUploadAdmission,
  storageFinalizationConnection = acquireStorageFinalizationClient,
  storageObjectReadGuard = storageReadConfig.enforced
    ? createStorageObjectReadGuard({ pool, authRequired, origins: storageReadConfig.origins }) : null,
}) {
  const storageAuth = (req, res, next) => { req[SCAN_COORDINATOR] = storageScanCoordinator; req[FINALIZATION_CONNECTION] = storageFinalizationConnection; return authRequired(req, res, next) }
  let configuredAdmission = storageUploadAdmission
  const admission = () => configuredAdmission || (configuredAdmission = createStorageUploadAdmission({ pool, root: storageRoot }))
  const admittedRaw = (handler, { multipart = false } = {}) => async (req, res, next) => {
    let reservation, finishing
    const finish = () => {
      if (!reservation) return Promise.resolve()
      return finishing || (finishing = reservation.finish())
    }
    try {
      const bucket = multipart ? null : cleanBucketName(req.params[0])
      const byteLimit = multipart ? Math.min(512 * 1024 * 1024, maxUploadBytes + 64 * 1024) : uploadLimitForBucket(bucket, storageScanCoordinator)
      const declared = req.headers['content-length'] == null ? byteLimit : Number(req.headers['content-length'])
      if (!Number.isSafeInteger(declared) || declared < 1 || declared > byteLimit) {
        throw Object.assign(new Error('UPLOAD_TOO_LARGE'), { status: 413 })
      }
      reservation = await admission().reserve({ ownerId: req.auth.sub, bytes: declared, kind: multipart ? 'multipart' : 'raw' })
      req[UPLOAD_RESERVATION] = reservation
      reservation.onLost?.(() => { const failure = Object.assign(new Error('STORAGE_UPLOAD_RESERVATION_LOST'), { status: 503 }); req.destroy(failure); res.destroy(failure) })
      if (multipart) await new Promise((resolve, reject) => upload.single('file')(req, res, cause => cause ? reject(cause) : resolve()))
      reservation.assertAlive()
      await handler(req, res, next, finish)
    } catch (cause) {
      if (cause instanceof multer.MulterError && /^LIMIT_[A-Z_]+$/.test(cause.code || '')) {
        cause.status = ['LIMIT_UNEXPECTED_FILE', 'LIMIT_FIELD_KEY'].includes(cause.code) ? 400 : 413
        cause.expose = true
      }
      if (cause.retryAfterSeconds) res.setHeader('Retry-After', String(cause.retryAfterSeconds))
      await finish().catch(() => {})
      next(cause)
    } finally { await finish().catch(() => {}) }
  }
  const admittedTus = handler => async (req, res) => {
    let reservation, finishing
    const finish = () => {
      if (!reservation) return Promise.resolve()
      if (!finishing) finishing = (async () => {
        // Release before sending the response: a normal TUS client immediately
        // follows HEAD with PATCH, and must not race this request's cleanup.
        let keep = true, expiresAt = reservation.expiresAt
        try {
          const row = (await pool.query('SELECT state,expires_at FROM fallback_resumable_uploads WHERE id=$1 AND owner_id=$2', [reservation.id, req.auth.sub])).rows[0]
          keep = Boolean(row && ['uploading', 'finalizing'].includes(row.state)); expiresAt = row?.expires_at || expiresAt
        } catch { /* A database outage must not release an uncertain upload. */ }
        await reservation.finish({ keep, expiresAt })
      })()
      return finishing
    }
    try {
      reservation = await admission().claimTus(String(req.params[0] || '').toLowerCase(), req.auth.sub,
        { activate: req.method === 'PATCH' ? true : req.method === 'HEAD' ? 'finalizing' : false })
      req[UPLOAD_RESERVATION] = reservation
      reservation?.onLost?.(() => { const failure = Object.assign(new Error('STORAGE_UPLOAD_RESERVATION_LOST'), { status: 503 }); req.destroy(failure); res.destroy(failure) })
      await handler(req, res, finish)
    } catch (cause) {
      await finish().catch(() => {})
      setTusHeaders(res)
      if (cause.retryAfterSeconds) res.setHeader('Retry-After', String(cause.retryAfterSeconds))
      if (!res.headersSent) res.status(cause.status || 503).json({ error: cause.expose ? cause.code : 'TUS_UPLOAD_UNAVAILABLE' })
    } finally {
      await finish().catch(() => {})
    }
  }
  app.get('/v1/storage/buckets', storageAuth, async (_req, res, next) => {
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

  app.post('/v1/storage/upload', storageAuth, admittedRaw(async (req, res, next, finish) => {
    const uploadedPath = req.file?.path
    try {
      const bucket = cleanBucketName(req.body?.bucket)
      if (!bucket) {
        await finish()
        return res.status(400).json({ error: 'INVALID_BUCKET' })
      }
      if (!req.file) {
        await finish()
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

      await finish()
      res.status(201).json({ object: mapObjectRow(row) })
    } catch (error) {
      await finish().catch(() => {})
      next(error)
    } finally {
      if (uploadedPath) {
        try {
          await fs.promises.unlink(uploadedPath)
        } catch {
          // The successful write already moved this path; other failures are best effort.
        }
      }
    }
  }, { multipart: true }))

  app.get('/v1/storage/objects', storageAuth, async (req, res, next) => {
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

  app.delete(/^\/v1\/storage\/objects\/([^/]+)\/(.+)$/, storageAuth, async (req, res, next) => {
    try {
      const bucket = cleanBucketName(req.params[0])
      const objectPath = normalizeObjectPath(decodeURIComponent(req.params[1] || ''), '')
      if (!bucket || !objectPath) return res.status(400).json({ error: 'INVALID_STORAGE_PATH' })

      await storageScanCoordinator.withObjectLock(pool, bucket, objectPath, async (connection) => {
      const { rows } = await connection.query(
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
      if (await publishedContentUsesObject(connection, bucket, objectPath)) {
        return res.status(409).json({ error: 'STORAGE_OBJECT_IN_USE' })
      }

      const filePath = storedObjectFilePath(row, bucket, objectPath)
      const deleted = await connection.query(
        `DELETE FROM fallback_storage_objects
         WHERE bucket_id = $1 AND name = $2 AND local_path = $3
           AND object_revision IS NOT DISTINCT FROM $4::uuid
         RETURNING id`,
        [bucket, objectPath, row.local_path, row.object_revision || null],
      )
      if (!deleted.rows[0]) return res.status(409).json({ error: 'STORAGE_OBJECT_CHANGED' })
      try {
        await fs.promises.unlink(filePath)
      } catch (error) {
        if (error?.code !== 'ENOENT') throw error
      }
      await removeImageVariants(bucket, objectPath)

      res.json({ ok: true })
      })
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

  app.post(resumableCollectionRoute, storageAuth, async (req, res) => {
    let uploadId = '', reservation, created = false
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
      if (uploadLength > uploadLimitForBucket(bucket, storageScanCoordinator)) {
        const error = new Error('UPLOAD_TOO_LARGE')
        error.status = 413
        throw error
      }

      await cleanupExpiredResumableUploads(pool).catch((error) => {
        console.warn('[storage] resumable cleanup failed', safeErrorLogDetails(error))
      })
      uploadId = crypto.randomUUID()
      reservation = await admission().reserve({ ownerId: req.auth.sub, bytes: uploadLength, kind: 'tus', id: uploadId, expiresAt: new Date(Date.now() + resumableUploadLifetimeMs) })
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
      created = true
      await reservation.finish({ keep: true })
      res.status(201).end()
    } catch (error) {
      if (uploadId) await pool.query('DELETE FROM fallback_resumable_uploads WHERE id=$1 AND owner_id=$2', [uploadId, req.auth.sub]).catch(() => {})
      if (uploadId) await fs.promises.unlink(resumableUploadFilePath(uploadId)).catch(() => {})
      sendTusError(res, error)
    } finally { if (reservation) await reservation.finish({ keep: created }).catch(() => {}) }
  })

  app.head(resumableItemRoute, storageAuth, admittedTus(async (req, res, finish) => {
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
      await finish()
      res.status(200).end()
    } catch (error) {
      await finish()
      sendTusError(res, error)
    }
  }))

  app.patch(resumableItemRoute, storageAuth, admittedTus(async (req, res, finish) => {
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
      await finish()
      res.status(204).end()
    } catch (error) {
      if (error?.upload) {
        setTusHeaders(res, {
          uploadLength: error.upload.uploadLength,
          uploadOffset: error.upload.uploadOffset,
          uploadExpires: error.upload.expiresAt,
        })
      }
      await finish()
      sendTusError(res, error)
    }
  }))

  app.delete(resumableItemRoute, storageAuth, admittedTus(async (req, res, finish) => {
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
      await finish()
      res.status(204).end()
    } catch (error) {
      await finish()
      sendTusError(res, error)
    }
  }))

  const objectWriteRoute = /^\/storage\/v1\/object\/([^/]+)\/(.+)$/

  app.post(/^\/storage\/v1\/object\/sign\/([^/]+)\/(.+)$/, storageAuth, async (req, res, next) => {
    try {
      const bucket = cleanBucketName(req.params[0])
      const objectPath = normalizeObjectPath(decodeURIComponent(req.params[1] || ''), '')
      if (!bucket || !objectPath) return res.status(400).json({ error: 'INVALID_STORAGE_PATH' })
      const { rows } = await pool.query(
        `SELECT * FROM fallback_storage_objects WHERE bucket_id = $1 AND name = $2 LIMIT 1`,
        [bucket, objectPath],
      )
      if (!rows[0] || (storageScanCoordinator.enforced && !cleanStorageRow(rows[0]))) return res.status(404).json({ error: 'NOT_FOUND' })
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

  app.post(objectWriteRoute, storageAuth, admittedRaw(async (req, res, next, finish) => {
    let uploadedPath = null
    try {
      const bucket = cleanBucketName(req.params[0])
      const objectPath = normalizeObjectPath(decodeURIComponent(req.params[1] || ''), '')
      if (!bucket || !objectPath) { await finish(); return res.status(400).json({ error: 'INVALID_STORAGE_PATH' }) }
      const streamed = await streamRequestToTemp(req, uploadLimitForBucket(bucket, storageScanCoordinator))
      uploadedPath = streamed.tempPath
      if (!streamed.sizeBytes) {
        await fs.promises.unlink(uploadedPath).catch(() => {})
        uploadedPath = null
        await finish()
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

      await finish()
      res.status(200).json({
        Key: `${row.bucket_id}/${row.name}`,
        path: row.name,
        publicUrl: row.public_url,
      })
    } catch (error) {
      if (uploadedPath) await fs.promises.unlink(uploadedPath).catch(() => {})
      await finish().catch(() => {})
      next(error)
    }
  }))

  app.put(objectWriteRoute, storageAuth, admittedRaw(async (req, res, next, finish) => {
    let uploadedPath = null
    try {
      const bucket = cleanBucketName(req.params[0])
      const objectPath = normalizeObjectPath(decodeURIComponent(req.params[1] || ''), '')
      if (!bucket || !objectPath) { await finish(); return res.status(400).json({ error: 'INVALID_STORAGE_PATH' }) }
      const streamed = await streamRequestToTemp(req, uploadLimitForBucket(bucket, storageScanCoordinator))
      uploadedPath = streamed.tempPath
      if (!streamed.sizeBytes) {
        await fs.promises.unlink(uploadedPath).catch(() => {})
        uploadedPath = null
        await finish()
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

      await finish()
      res.status(200).json({
        Key: `${row.bucket_id}/${row.name}`,
        path: row.name,
        publicUrl: row.public_url,
      })
    } catch (error) {
      if (uploadedPath) await fs.promises.unlink(uploadedPath).catch(() => {})
      await finish().catch(() => {})
      next(error)
    }
  }))

  app.get(/^\/storage\/v1\/object\/public\/([^/]+)\/(.+)$/, async (req, res, next) => {
    try {
      if (signedOnlyBuckets.has(cleanBucketName(req.params[0]))) return res.status(404).json({ error: 'NOT_FOUND' })
      await serveStoredObject(pool, req, res, req.params[0], req.params[1], storageScanCoordinator, storageObjectReadGuard)
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
      await serveStoredObject(pool, req, res, bucket, objectPath, storageScanCoordinator, storageObjectReadGuard)
    } catch (error) {
      if (error?.code === 'ENOENT') return res.status(404).json({ error: 'NOT_FOUND' })
      next(error)
    }
  })

  app.get(/^\/storage\/v1\/object\/([^/]+)\/(.+)$/, async (req, res, next) => {
    try {
      if (signedOnlyBuckets.has(cleanBucketName(req.params[0]))) return res.status(404).json({ error: 'NOT_FOUND' })
      await serveStoredObject(pool, req, res, req.params[0], req.params[1], storageScanCoordinator, storageObjectReadGuard)
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
  parseMp4AudioCodecs,
  probeMp4AudioCodecs,
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
  storageScanReadiness,
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
