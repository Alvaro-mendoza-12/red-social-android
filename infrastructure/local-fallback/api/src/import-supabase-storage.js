const fs = require('fs')
const path = require('path')
const { Readable } = require('stream')
const { pipeline } = require('stream/promises')
const { Pool } = require('pg')
const { ensureLocalStorageTables } = require('./storage-routes')

const args = new Set(process.argv.slice(2))

function trimTrailingSlash(value) {
  return String(value || '').trim().replace(/\/+$/, '')
}

function boolEnv(name, defaultValue = false) {
  const raw = process.env[name]
  if (raw == null || raw === '') return defaultValue
  return /^(1|true|yes|on)$/i.test(String(raw).trim())
}

function intEnv(name, defaultValue, min, max) {
  const parsed = Number(process.env[name] || defaultValue)
  if (!Number.isFinite(parsed)) return defaultValue
  return Math.max(min, Math.min(max, Math.trunc(parsed)))
}

function splitList(value) {
  return String(value || '')
    .split(',')
    .map((entry) => entry.trim())
    .filter(Boolean)
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

function encodeObjectPath(objectPath) {
  return String(objectPath || '')
    .split('/')
    .filter(Boolean)
    .map((segment) => encodeURIComponent(segment))
    .join('/')
}

function publicObjectUrl(publicBaseUrl, bucket, objectPath) {
  const encodedPath = encodeObjectPath(objectPath)
  return `${publicBaseUrl}/storage/v1/object/public/${encodeURIComponent(bucket)}/${encodedPath}`
}

function objectFilePath(storageRoot, bucket, objectPath) {
  const bucketRoot = path.join(storageRoot, bucket)
  const resolved = path.resolve(bucketRoot, objectPath)
  if (!resolved.startsWith(path.resolve(bucketRoot) + path.sep) && resolved !== path.resolve(bucketRoot)) {
    throw new Error('INVALID_STORAGE_PATH')
  }
  return resolved
}

function headers(json = true) {
  const base = {
    apikey: supabaseKey,
    Authorization: `Bearer ${supabaseKey}`,
  }
  return json ? { ...base, 'Content-Type': 'application/json' } : base
}

async function readErrorBody(response) {
  const text = await response.text().catch(() => '')
  return text.slice(0, 500)
}

async function supabaseJson(endpoint, options = {}) {
  const response = await fetch(`${supabaseUrl}${endpoint}`, {
    ...options,
    headers: {
      ...headers(true),
      ...(options.headers || {}),
    },
  })

  if (!response.ok) {
    const body = await readErrorBody(response)
    throw new Error(`SUPABASE_HTTP_${response.status}: ${endpoint} ${body}`)
  }

  if (response.status === 204) return null
  return response.json()
}

async function listBuckets() {
  const configured = splitList(process.env.SUPABASE_STORAGE_BUCKETS)
    .map(cleanBucketName)
    .filter(Boolean)
  if (configured.length > 0) return configured

  const buckets = await supabaseJson('/storage/v1/bucket')
  if (!Array.isArray(buckets)) throw new Error('SUPABASE_BUCKET_LIST_INVALID')

  return buckets
    .map((bucket) => cleanBucketName(bucket.id || bucket.name))
    .filter(Boolean)
}

async function listObjectsPage(bucket, prefix, offset) {
  const body = {
    prefix,
    limit: pageSize,
    offset,
    sortBy: {
      column: 'name',
      order: 'asc',
    },
  }

  const objects = await supabaseJson(`/storage/v1/object/list/${encodeURIComponent(bucket)}`, {
    method: 'POST',
    body: JSON.stringify(body),
  })

  if (!Array.isArray(objects)) throw new Error(`SUPABASE_OBJECT_LIST_INVALID:${bucket}:${prefix}`)
  return objects
}

function isFolderEntry(entry) {
  return Boolean(entry?.name && !entry.id && !entry.metadata)
}

function joinObjectPath(prefix, name) {
  const cleanPrefix = String(prefix || '').replace(/^\/+|\/+$/g, '')
  const cleanName = String(name || '').replace(/^\/+|\/+$/g, '')
  return [cleanPrefix, cleanName].filter(Boolean).join('/')
}

async function* walkBucket(bucket, prefix = '', visited = new Set()) {
  const visitKey = `${bucket}:${prefix}`
  if (visited.has(visitKey)) return
  visited.add(visitKey)

  let offset = 0
  while (true) {
    const page = await listObjectsPage(bucket, prefix, offset)
    if (page.length === 0) break

    for (const entry of page) {
      const name = String(entry?.name || '').trim()
      if (!name) continue

      const sourcePath = joinObjectPath(prefix, name)
      if (isFolderEntry(entry)) {
        yield* walkBucket(bucket, `${sourcePath}/`, visited)
      } else {
        yield { bucket, sourcePath, entry }
      }
    }

    if (page.length < pageSize) break
    offset += page.length
  }
}

async function fileExists(filePath) {
  try {
    await fs.promises.access(filePath, fs.constants.R_OK)
    return true
  } catch {
    return false
  }
}

async function downloadObjectToFile(bucket, sourcePath, destination) {
  const endpoint = `/storage/v1/object/${encodeURIComponent(bucket)}/${encodeObjectPath(sourcePath)}`
  const response = await fetch(`${supabaseUrl}${endpoint}`, {
    headers: headers(false),
  })

  if (!response.ok) {
    const body = await readErrorBody(response)
    throw new Error(`SUPABASE_DOWNLOAD_${response.status}: ${bucket}/${sourcePath} ${body}`)
  }

  await fs.promises.mkdir(path.dirname(destination), { recursive: true })
  const tempPath = `${destination}.download-${process.pid}-${Date.now()}`

  try {
    if (response.body) {
      await pipeline(Readable.fromWeb(response.body), fs.createWriteStream(tempPath))
    } else {
      const buffer = Buffer.from(await response.arrayBuffer())
      await fs.promises.writeFile(tempPath, buffer)
    }
    await fs.promises.rename(tempPath, destination)
  } catch (error) {
    await fs.promises.rm(tempPath, { force: true }).catch(() => {})
    throw error
  }

  const stat = await fs.promises.stat(destination)
  return {
    contentType: response.headers.get('content-type') || 'application/octet-stream',
    sizeBytes: stat.size,
  }
}

function metadataSize(entry) {
  const size = Number(entry?.metadata?.size || entry?.metadata?.contentLength || 0)
  return Number.isFinite(size) && size > 0 ? size : 0
}

function metadataContentType(entry) {
  return (
    entry?.metadata?.mimetype ||
    entry?.metadata?.mimeType ||
    entry?.metadata?.contentType ||
    'application/octet-stream'
  )
}

function validDate(value) {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date.toISOString()
}

async function upsertObject(pool, item, localPath, importInfo) {
  const bucket = cleanBucketName(item.bucket)
  const objectPath = normalizeObjectPath(item.sourcePath, item.entry?.name)
  const publicUrl = publicObjectUrl(publicBaseUrl, bucket, objectPath)
  const createdAt = validDate(item.entry?.created_at)
  const updatedAt = validDate(item.entry?.updated_at)
  const metadata = {
    supabase_storage_import: {
      source_bucket: item.bucket,
      source_path: item.sourcePath,
      source_id: item.entry?.id || null,
      imported_at: new Date().toISOString(),
      normalized_path_changed: objectPath !== item.sourcePath,
      source_metadata: item.entry?.metadata || null,
    },
  }

  await pool.query(
    `INSERT INTO fallback_storage_buckets(id, public)
     VALUES ($1, TRUE)
     ON CONFLICT (id) DO NOTHING`,
    [bucket],
  )

  await pool.query(
    `INSERT INTO fallback_storage_objects(
        bucket_id,
        name,
        owner_id,
        content_type,
        size_bytes,
        public_url,
        local_path,
        metadata,
        created_at,
        updated_at
     )
     VALUES ($1, $2, NULL, $3, $4, $5, $6, $7, COALESCE($8::timestamptz, NOW()), COALESCE($9::timestamptz, NOW()))
     ON CONFLICT (bucket_id, name)
     DO UPDATE SET
        content_type = EXCLUDED.content_type,
        size_bytes = EXCLUDED.size_bytes,
        public_url = EXCLUDED.public_url,
        local_path = EXCLUDED.local_path,
        metadata = fallback_storage_objects.metadata || EXCLUDED.metadata,
        updated_at = COALESCE(EXCLUDED.updated_at, NOW())`,
    [
      bucket,
      objectPath,
      importInfo.contentType || metadataContentType(item.entry),
      importInfo.sizeBytes || metadataSize(item.entry),
      publicUrl,
      localPath,
      metadata,
      createdAt,
      updatedAt,
    ],
  )
}

async function importObject(pool, item, stats) {
  const bucket = cleanBucketName(item.bucket)
  if (!bucket) {
    stats.failed += 1
    console.warn(`SKIP invalid bucket: ${item.bucket}`)
    return
  }

  const objectPath = normalizeObjectPath(item.sourcePath, item.entry?.name)
  const destination = objectFilePath(storageRoot, bucket, objectPath)
  const alreadyOnDisk = await fileExists(destination)

  let importInfo = {
    contentType: metadataContentType(item.entry),
    sizeBytes: metadataSize(item.entry),
  }

  if (!overwrite && alreadyOnDisk) {
    stats.skipped += 1
    console.log(`skip ${bucket}/${objectPath}`)
  } else {
    importInfo = await downloadObjectToFile(bucket, item.sourcePath, destination)
    stats.copied += 1
    console.log(`copy ${bucket}/${objectPath} ${importInfo.sizeBytes} bytes`)
  }

  await upsertObject(pool, item, destination, importInfo)
  stats.registered += 1

  if (objectPath !== item.sourcePath) {
    stats.normalized += 1
    console.warn(`normalized ${item.bucket}/${item.sourcePath} -> ${bucket}/${objectPath}`)
  }
}

function printUsage() {
  console.log(`Usage:
  node src/import-supabase-storage.js [--dry-run] [--overwrite]

Required environment:
  SUPABASE_URL
  SUPABASE_SERVICE_ROLE_KEY or SUPABASE_SECRET_KEY
  DATABASE_URL (not required for --dry-run)
  PUBLIC_BASE_URL

Optional environment:
  STORAGE_ROOT=/data/storage
  SUPABASE_STORAGE_BUCKETS=avatars,post-media,videos
  SUPABASE_STORAGE_DRY_RUN=true
  SUPABASE_STORAGE_OVERWRITE=false
  SUPABASE_STORAGE_PAGE_SIZE=100`)
}

const dryRun = args.has('--dry-run') || boolEnv('SUPABASE_STORAGE_DRY_RUN', false)
const overwrite = args.has('--overwrite') || boolEnv('SUPABASE_STORAGE_OVERWRITE', false)
const help = args.has('--help') || args.has('-h')
const supabaseUrl = trimTrailingSlash(process.env.SUPABASE_URL)
const supabaseKey = String(process.env.SUPABASE_SERVICE_ROLE_KEY || process.env.SUPABASE_SECRET_KEY || '').trim()
const databaseUrl = String(process.env.DATABASE_URL || '').trim()
const storageRoot = path.resolve(process.env.STORAGE_ROOT || path.join(process.cwd(), 'storage-data'))
const publicBaseUrl = trimTrailingSlash(process.env.PUBLIC_BASE_URL || process.env.LOCAL_PUBLIC_BASE_URL)
const pageSize = intEnv('SUPABASE_STORAGE_PAGE_SIZE', 100, 1, 1000)

async function main() {
  if (help) {
    printUsage()
    return
  }

  if (!supabaseUrl || !supabaseKey) {
    printUsage()
    throw new Error('SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are required')
  }

  if (!dryRun && !databaseUrl) {
    printUsage()
    throw new Error('DATABASE_URL is required when importing files')
  }

  if (!dryRun && !publicBaseUrl) {
    throw new Error('PUBLIC_BASE_URL is required so imported objects get Ubuntu URLs')
  }

  const buckets = await listBuckets()
  if (buckets.length === 0) {
    console.log('No Supabase storage buckets found.')
    return
  }

  console.log(`Supabase storage import ${dryRun ? '(dry-run)' : '(apply)'}`)
  console.log(`Buckets: ${buckets.join(', ')}`)
  console.log(`Storage root: ${storageRoot}`)

  const stats = {
    scanned: 0,
    copied: 0,
    skipped: 0,
    registered: 0,
    normalized: 0,
    failed: 0,
  }

  let pool = null
  if (!dryRun) {
    pool = new Pool({ connectionString: databaseUrl })
    await ensureLocalStorageTables(pool)
    await fs.promises.mkdir(storageRoot, { recursive: true })
  }

  try {
    for (const bucket of buckets) {
      console.log(`bucket ${bucket}`)
      for await (const item of walkBucket(bucket)) {
        stats.scanned += 1
        if (dryRun) {
          const normalized = normalizeObjectPath(item.sourcePath, item.entry?.name)
          const size = metadataSize(item.entry)
          console.log(`found ${bucket}/${normalized}${size ? ` ${size} bytes` : ''}`)
          if (normalized !== item.sourcePath) stats.normalized += 1
          continue
        }

        try {
          await importObject(pool, item, stats)
        } catch (error) {
          stats.failed += 1
          console.error(`failed ${bucket}/${item.sourcePath}: ${error.message}`)
        }
      }
    }
  } finally {
    if (pool) await pool.end()
  }

  console.log('Summary:')
  console.log(JSON.stringify(stats, null, 2))

  if (dryRun) {
    console.log('Dry-run finished. Run again without --dry-run to copy files into Ubuntu storage.')
  } else if (stats.failed > 0) {
    process.exitCode = 1
  }
}

main().catch((error) => {
  console.error(error.message)
  process.exitCode = 1
})
