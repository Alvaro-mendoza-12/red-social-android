const fs = require('fs')
const path = require('path')
const multer = require('multer')

const defaultBuckets = [
  'avatars',
  'chat-media',
  'post-media',
  'moment-media',
  'videos',
  'thumbnails',
  'group-media',
  'community-media',
  'groups',
  'communities',
  'challenges',
  'video-comments-media',
  'post-comments-media',
]

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

      await pool.query(
        `INSERT INTO fallback_storage_buckets(id, public)
         VALUES ($1, TRUE)
         ON CONFLICT (id) DO NOTHING`,
        [bucket],
      )

      const requestedPath = String(req.body?.path || '').trim()
      const defaultPath = `${req.auth.sub}/${Date.now()}-${req.file.originalname || 'file'}`
      const objectPath = normalizeObjectPath(requestedPath, defaultPath)
      const destination = objectFilePath(bucket, objectPath)

      await fs.promises.mkdir(path.dirname(destination), { recursive: true })
      await fs.promises.rename(req.file.path, destination)

      const publicUrl = publicObjectUrl(req, bucket, objectPath)
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
          req.auth.sub,
          req.file.mimetype || 'application/octet-stream',
          req.file.size || 0,
          publicUrl,
          destination,
          {
            originalName: req.file.originalname || '',
            encoding: req.file.encoding || '',
          },
        ],
      )

      res.status(201).json({ object: mapObjectRow(rows[0]) })
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
      const { rows } = await pool.query(
        `SELECT *
         FROM fallback_storage_objects
         WHERE ($1::text = '' OR bucket_id = $1)
         ORDER BY updated_at DESC
         LIMIT 200`,
        [bucket],
      )
      res.json({ objects: rows.map(mapObjectRow) })
    } catch (error) {
      next(error)
    }
  })

  app.get(/^\/storage\/v1\/object\/public\/([^/]+)\/(.+)$/, async (req, res, next) => {
    try {
      const bucket = cleanBucketName(req.params[0])
      const objectPath = normalizeObjectPath(decodeURIComponent(req.params[1] || ''), '')
      if (!bucket || !objectPath) return res.status(404).json({ error: 'NOT_FOUND' })

      const { rows } = await pool.query(
        `SELECT *
         FROM fallback_storage_objects
         WHERE bucket_id = $1 AND name = $2
         LIMIT 1`,
        [bucket, objectPath],
      )
      const row = rows[0]
      const filePath = row?.local_path || objectFilePath(bucket, objectPath)
      await fs.promises.access(filePath, fs.constants.R_OK)
      res.setHeader('Content-Type', row?.content_type || 'application/octet-stream')
      res.setHeader('Cache-Control', 'public, max-age=3600')
      res.setHeader('Content-Disposition', `inline; filename="${contentDispositionName(objectPath)}"`)
      res.sendFile(filePath)
    } catch (error) {
      if (error?.code === 'ENOENT') return res.status(404).json({ error: 'NOT_FOUND' })
      next(error)
    }
  })
}

module.exports = {
  ensureLocalStorageTables,
  registerStorageRoutes,
}
