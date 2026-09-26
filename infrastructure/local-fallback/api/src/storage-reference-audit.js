const fs = require('fs')
const path = require('path')

const DEFAULT_GRACE_HOURS = 7 * 24
const DEFAULT_PAGE_SIZE = 1_000
const STORAGE_COLUMN_PATTERN = /(storage|object|url|uri|path|media|audio|video|image|avatar|cover|thumbnail|evidence|attachment|file)/i
const STORAGE_MARKER_PATTERN = /\/storage\//i

function normalizeBucket(value) {
  const bucket = String(value || '').trim().toLowerCase()
  return /^[a-z0-9][a-z0-9._-]*$/.test(bucket) ? bucket : ''
}

function decodePathSegment(value) {
  try {
    return decodeURIComponent(value)
  } catch {
    return value
  }
}

function normalizeObjectName(value) {
  const raw = String(value || '').trim().replace(/\\/g, '/').replace(/^\/+/, '')
  if (!raw || raw.includes('\0')) return ''
  const parts = raw.split('/').map(decodePathSegment)
  if (parts.some((part) => !part || part === '.' || part === '..' || part.includes('/') || part.includes('\\'))) {
    return ''
  }
  return parts.join('/')
}

function storageObjectKey(bucketValue, nameValue) {
  const bucket = normalizeBucket(bucketValue)
  const name = normalizeObjectName(nameValue)
  return bucket && name ? `${bucket}/${name}` : ''
}

function stripCandidateNoise(value) {
  return String(value || '')
    .trim()
    .replace(/^["'(<\[]+/, '')
    .replace(/["')>\],.;:]+$/, '')
}

function storageKeyFromCandidate(value) {
  const candidate = stripCandidateNoise(value)
  if (!candidate) return ''
  let pathname = candidate
  try {
    if (/^https?:\/\//i.test(candidate)) pathname = new URL(candidate).pathname
    else pathname = new URL(candidate, 'https://audit.invalid').pathname
  } catch {
    pathname = candidate.split(/[?#]/, 1)[0]
  }

  const segments = String(pathname || '')
    .split('/')
    .filter(Boolean)
    .map(decodePathSegment)
  const storageIndex = segments.findIndex((segment) => segment.toLowerCase() === 'storage')
  if (storageIndex < 0) return ''

  let cursor = storageIndex + 1
  if (segments[cursor]?.toLowerCase() === 'v1' && segments[cursor + 1]?.toLowerCase() === 'object') {
    cursor += 2
    if (['public', 'sign', 'authenticated'].includes(segments[cursor]?.toLowerCase())) cursor += 1
  }
  const bucket = segments[cursor]
  const name = segments.slice(cursor + 1).join('/')
  return storageObjectKey(bucket, name)
}

function normalizeAlias(value) {
  const raw = stripCandidateNoise(value)
  if (!raw) return ''
  try {
    if (/^https?:\/\//i.test(raw)) {
      const parsed = new URL(raw)
      parsed.search = ''
      parsed.hash = ''
      return parsed.toString().replace(/\/$/, '')
    }
  } catch {
    // Keep the raw alias. Malformed values cannot become a filesystem path.
  }
  return raw.replace(/[?#].*$/, '')
}

function buildStorageAliasIndex(objects) {
  const aliases = new Map()
  const ambiguous = new Set()

  function register(aliasValue, key) {
    const alias = normalizeAlias(aliasValue)
    if (!alias || ambiguous.has(alias)) return
    const existing = aliases.get(alias)
    if (existing && existing !== key) {
      aliases.delete(alias)
      ambiguous.add(alias)
      return
    }
    aliases.set(alias, key)
  }

  for (const object of objects || []) {
    const key = storageObjectKey(object.bucket_id, object.name)
    if (!key) continue
    register(object.id, key)
    register(key, key)
    register(`/${key}`, key)
    register(object.name, key)
    register(object.public_url, key)
    register(`/storage/v1/object/public/${key}`, key)
    register(`/storage/v1/object/${key}`, key)
  }
  return aliases
}

function collectScalarValues(value, output, seen = new WeakSet()) {
  if (value == null) return
  if (typeof value === 'string') {
    output.push(value)
    const trimmed = value.trim()
    if ((trimmed.startsWith('{') || trimmed.startsWith('[')) && trimmed.length <= 10 * 1024 * 1024) {
      try {
        collectScalarValues(JSON.parse(trimmed), output, seen)
      } catch {
        // A normal text cell is allowed to start with JSON punctuation.
      }
    }
    return
  }
  if (typeof value !== 'object' || seen.has(value)) return
  seen.add(value)
  if (Array.isArray(value)) {
    value.forEach((item) => collectScalarValues(item, output, seen))
    return
  }
  Object.values(value).forEach((item) => collectScalarValues(item, output, seen))
}

function extractStorageObjectKeys(value, aliases = new Map()) {
  const values = []
  collectScalarValues(value, values)
  const keys = new Set()
  const candidatePattern = /https?:\/\/[^\s"'<>]+|\/storage\/[^\s"'<>]+/gi

  for (const scalar of values) {
    const directAlias = aliases.get(normalizeAlias(scalar))
    if (directAlias) keys.add(directAlias)
    for (const match of scalar.matchAll(candidatePattern)) {
      const candidate = match[0]
      const alias = aliases.get(normalizeAlias(candidate))
      if (alias) keys.add(alias)
      const parsed = storageKeyFromCandidate(candidate)
      if (parsed) keys.add(parsed)
    }
  }
  return keys
}

function quoteIdentifier(value) {
  return `"${String(value || '').replace(/"/g, '""')}"`
}

function normalizeSchemas(value) {
  const source = Array.isArray(value) ? value : String(value || '').split(',')
  const schemas = Array.from(new Set(source.map((item) => String(item).trim()).filter(Boolean)))
  if (schemas.length === 0) return ['public', 'supabase_import']
  if (schemas.length > 8) throw new Error('STORAGE_AUDIT_SCHEMA_LIMIT_EXCEEDED')
  return schemas
}

async function discoverReferenceColumns(client, schemas) {
  const { rows } = await client.query(
    `SELECT c.table_schema, c.table_name, c.column_name, c.udt_name
     FROM information_schema.columns c
     JOIN information_schema.tables t
       ON t.table_schema = c.table_schema
      AND t.table_name = c.table_name
      AND t.table_type = 'BASE TABLE'
     WHERE c.table_schema = ANY($1::text[])
       AND c.table_name <> 'fallback_storage_objects'
       AND c.is_generated = 'NEVER'
       AND c.udt_name = ANY($2::text[])
     ORDER BY c.table_schema, c.table_name, c.ordinal_position`,
    [schemas, ['text', 'varchar', 'bpchar', 'json', 'jsonb', '_text', '_varchar', 'uuid']],
  )
  return rows.filter((column) => column.udt_name !== 'uuid' || STORAGE_COLUMN_PATTERN.test(column.column_name))
}

function referenceCellQuery(column) {
  const schema = quoteIdentifier(column.table_schema)
  const table = quoteIdentifier(column.table_name)
  const field = quoteIdentifier(column.column_name)
  const scanEveryNonEmptyValue =
    STORAGE_COLUMN_PATTERN.test(column.column_name) ||
    ['json', 'jsonb', '_text', '_varchar'].includes(column.udt_name)
  const predicate = scanEveryNonEmptyValue
    ? `btrim(${field}::text) <> ''`
    : `position('/storage/' in lower(${field}::text)) > 0`
  return `SELECT ctid::text AS row_locator, ${field}::text AS value
          FROM ${schema}.${table}
          WHERE ${field} IS NOT NULL AND ${predicate}
          ORDER BY ctid
          LIMIT $1 OFFSET $2`
}

async function collectDatabaseReferences(client, objects, options = {}) {
  const schemas = normalizeSchemas(options.schemas)
  const pageSize = Number(options.pageSize || DEFAULT_PAGE_SIZE)
  if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > 10_000) {
    throw new Error('STORAGE_AUDIT_PAGE_SIZE_INVALID')
  }
  const aliases = buildStorageAliasIndex(objects)
  const knownKeys = new Set((objects || []).map((row) => storageObjectKey(row.bucket_id, row.name)).filter(Boolean))
  const references = new Map()
  const danglingReferences = new Map()
  const columns = await discoverReferenceColumns(client, schemas)
  let cellsScanned = 0

  for (const column of columns) {
    const sql = referenceCellQuery(column)
    let offset = 0
    while (true) {
      const { rows } = await client.query(sql, [pageSize, offset])
      cellsScanned += rows.length
      for (const row of rows) {
        for (const key of extractStorageObjectKeys(row.value, aliases)) {
          const target = knownKeys.has(key) ? references : danglingReferences
          const entries = target.get(key) || []
          if (entries.length < 25) {
            entries.push({
              schema: column.table_schema,
              table: column.table_name,
              column: column.column_name,
              rowLocator: row.row_locator,
            })
          }
          target.set(key, entries)
        }
      }
      if (rows.length < pageSize) break
      offset += rows.length
    }
  }
  return { columns, cellsScanned, references, danglingReferences }
}

function isPathWithin(root, candidate) {
  const relative = path.relative(root, candidate)
  return relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative))
}

function resolveStoragePath(storageRoot, object) {
  const root = path.resolve(storageRoot)
  const bucket = normalizeBucket(object.bucket_id)
  const name = normalizeObjectName(object.name)
  if (!bucket || !name) return { safe: false, issue: 'invalid-storage-key' }
  const expectedPath = path.resolve(root, bucket, ...name.split('/'))
  if (!isPathWithin(root, expectedPath)) return { safe: false, issue: 'expected-path-outside-root' }

  const rawLocalPath = String(object.local_path || '').trim()
  if (rawLocalPath && !path.isAbsolute(rawLocalPath)) {
    return { safe: false, issue: 'relative-local-path', expectedPath }
  }
  const resolvedPath = rawLocalPath ? path.resolve(rawLocalPath) : expectedPath
  if (!isPathWithin(root, resolvedPath)) {
    return { safe: false, issue: 'local-path-outside-root', expectedPath, resolvedPath }
  }
  return {
    safe: true,
    expectedPath,
    resolvedPath,
    mismatch: resolvedPath !== expectedPath,
  }
}

function objectAgeMs(object, nowMs) {
  const timestamp = new Date(object.updated_at || object.created_at || 0).getTime()
  return Number.isFinite(timestamp) ? Math.max(0, nowMs - timestamp) : Number.POSITIVE_INFINITY
}

function classifyStorageObject({ object, references, file, pathResolution, nowMs, graceMs }) {
  const referenceCount = references?.length || 0
  const sizeBytes = Number(object.size_bytes || 0)
  const ageMs = objectAgeMs(object, nowMs)
  const integrity = []
  if (!pathResolution?.safe) integrity.push(pathResolution?.issue || 'unsafe-path')
  if (pathResolution?.mismatch) integrity.push('local-path-mismatch')
  if (file?.exists && Number.isFinite(file.size) && file.size !== sizeBytes) integrity.push('size-mismatch')

  let status
  let severity
  if (!pathResolution?.safe || pathResolution?.mismatch) {
    status = 'protected-path-anomaly'
    severity = 'critical'
  } else if (referenceCount > 0 && !file?.exists) {
    status = 'referenced-file-missing'
    severity = 'critical'
  } else if (referenceCount > 0) {
    status = 'referenced'
    severity = integrity.length > 0 ? 'warning' : 'ok'
  } else if (ageMs < graceMs) {
    status = 'protected-recent-unreferenced'
    severity = 'info'
  } else if (!file?.exists) {
    status = 'stale-metadata-candidate'
    severity = 'warning'
  } else {
    status = 'orphan-candidate'
    severity = 'warning'
  }

  return {
    key: storageObjectKey(object.bucket_id, object.name),
    bucket: normalizeBucket(object.bucket_id),
    name: normalizeObjectName(object.name),
    status,
    severity,
    sizeBytes,
    actualSizeBytes: file?.exists ? Number(file.size || 0) : null,
    ageHours: Number.isFinite(ageMs) ? Math.floor(ageMs / 3_600_000) : null,
    referenceCount,
    referenceSamples: (references || []).slice(0, 10),
    integrity,
    createdAt: object.created_at || null,
    updatedAt: object.updated_at || null,
  }
}

async function statRegularFile(filePath) {
  if (!filePath) return { exists: false, size: null }
  try {
    const stat = await fs.promises.lstat(filePath)
    if (stat.isSymbolicLink()) return { exists: true, size: stat.size, unsafeType: 'symlink' }
    if (!stat.isFile()) return { exists: true, size: stat.size, unsafeType: 'not-regular-file' }
    return { exists: true, size: stat.size, mtimeMs: stat.mtimeMs }
  } catch (error) {
    if (error?.code === 'ENOENT') return { exists: false, size: null }
    throw error
  }
}

async function *walkStorageFiles(directory) {
  let entries
  try {
    entries = await fs.promises.readdir(directory, { withFileTypes: true })
  } catch (error) {
    if (error?.code === 'ENOENT') return
    throw error
  }
  for (const entry of entries) {
    const currentPath = path.join(directory, entry.name)
    if (entry.isSymbolicLink()) {
      yield { path: currentPath, type: 'symlink' }
    } else if (entry.isDirectory()) {
      yield *walkStorageFiles(currentPath)
    } else if (entry.isFile()) {
      const stat = await fs.promises.stat(currentPath)
      yield { path: currentPath, type: 'file', size: stat.size, mtimeMs: stat.mtimeMs }
    } else {
      yield { path: currentPath, type: 'other' }
    }
  }
}

function summarizeFindings(objects, untrackedFiles, scan, danglingReferences = []) {
  const byStatus = {}
  const bytesByStatus = {}
  for (const finding of objects) {
    byStatus[finding.status] = (byStatus[finding.status] || 0) + 1
    bytesByStatus[finding.status] = (bytesByStatus[finding.status] || 0) + Number(finding.actualSizeBytes ?? finding.sizeBytes ?? 0)
  }
  for (const finding of untrackedFiles) {
    byStatus[finding.status] = (byStatus[finding.status] || 0) + 1
    bytesByStatus[finding.status] = (bytesByStatus[finding.status] || 0) + Number(finding.sizeBytes || 0)
  }
  for (const finding of danglingReferences) {
    byStatus[finding.status] = (byStatus[finding.status] || 0) + 1
    bytesByStatus[finding.status] = bytesByStatus[finding.status] || 0
  }
  const critical = [...objects, ...untrackedFiles, ...danglingReferences]
    .filter((item) => item.severity === 'critical').length
  const candidates = [...objects, ...untrackedFiles].filter((item) => item.status.endsWith('candidate')).length
  return {
    objects: objects.length,
    filesystemOnly: untrackedFiles.length,
    danglingReferences: danglingReferences.length,
    referenceColumns: scan.columns.length,
    referenceCellsScanned: scan.cellsScanned,
    critical,
    candidates,
    byStatus,
    bytesByStatus,
  }
}

async function auditStorageReferences(client, options = {}) {
  const storageRoot = path.resolve(options.storageRoot || path.join(process.cwd(), 'storage-data'))
  const graceHours = Number(options.graceHours ?? DEFAULT_GRACE_HOURS)
  if (!Number.isFinite(graceHours) || graceHours < 1 || graceHours > 24 * 365) {
    throw new Error('STORAGE_AUDIT_GRACE_HOURS_INVALID')
  }
  const graceMs = graceHours * 3_600_000
  const nowMs = Number(options.nowMs || Date.now())
  const { rows: objects } = await client.query(
    `SELECT id::text, bucket_id, name, owner_id::text, content_type, size_bytes,
            public_url, local_path, metadata, created_at, updated_at
     FROM fallback_storage_objects
     ORDER BY bucket_id, name`,
  )
  const scan = await collectDatabaseReferences(client, objects, options)
  const findings = []
  const trackedPaths = new Set()

  for (const object of objects) {
    const key = storageObjectKey(object.bucket_id, object.name)
    const pathResolution = resolveStoragePath(storageRoot, object)
    const file = pathResolution.safe
      ? await statRegularFile(pathResolution.resolvedPath)
      : { exists: false, size: null }
    if (pathResolution.safe) trackedPaths.add(pathResolution.resolvedPath)
    if (file.unsafeType) {
      pathResolution.safe = false
      pathResolution.issue = file.unsafeType
    }
    findings.push(classifyStorageObject({
      object,
      references: scan.references.get(key) || [],
      file,
      pathResolution,
      nowMs,
      graceMs,
    }))
  }

  const { rows: buckets } = await client.query(`SELECT id FROM fallback_storage_buckets ORDER BY id`)
  const untrackedFiles = []
  for (const row of buckets) {
    const bucket = normalizeBucket(row.id)
    if (!bucket) continue
    const bucketRoot = path.resolve(storageRoot, bucket)
    if (!isPathWithin(storageRoot, bucketRoot)) continue
    for await (const entry of walkStorageFiles(bucketRoot)) {
      if (trackedPaths.has(entry.path)) continue
      const ageMs = Number.isFinite(entry.mtimeMs) ? Math.max(0, nowMs - entry.mtimeMs) : Number.POSITIVE_INFINITY
      const status = entry.type !== 'file'
        ? 'protected-filesystem-anomaly'
        : ageMs < graceMs
          ? 'protected-recent-filesystem-only'
          : 'filesystem-orphan-candidate'
      untrackedFiles.push({
        bucket,
        relativePath: path.relative(bucketRoot, entry.path).replace(/\\/g, '/'),
        status,
        severity: entry.type !== 'file' ? 'critical' : status.endsWith('candidate') ? 'warning' : 'info',
        sizeBytes: Number(entry.size || 0),
        ageHours: Number.isFinite(ageMs) ? Math.floor(ageMs / 3_600_000) : null,
      })
    }
  }

  const danglingReferences = Array.from(scan.danglingReferences.entries()).map(([key, references]) => ({
    key,
    status: 'dangling-storage-reference',
    severity: 'critical',
    referenceCount: references.length,
    referenceSamples: references.slice(0, 10),
  }))
  const summary = summarizeFindings(findings, untrackedFiles, scan, danglingReferences)
  return {
    version: 1,
    mode: 'read-only',
    destructiveActions: false,
    generatedAt: new Date(nowMs).toISOString(),
    storageRoot,
    graceHours,
    schemas: normalizeSchemas(options.schemas),
    summary,
    objects: findings,
    danglingReferences,
    filesystemOnly: untrackedFiles,
  }
}

module.exports = {
  DEFAULT_GRACE_HOURS,
  STORAGE_COLUMN_PATTERN,
  auditStorageReferences,
  buildStorageAliasIndex,
  classifyStorageObject,
  collectDatabaseReferences,
  extractStorageObjectKeys,
  isPathWithin,
  normalizeObjectName,
  normalizeSchemas,
  quoteIdentifier,
  referenceCellQuery,
  resolveStoragePath,
  storageKeyFromCandidate,
  storageObjectKey,
  summarizeFindings,
}
