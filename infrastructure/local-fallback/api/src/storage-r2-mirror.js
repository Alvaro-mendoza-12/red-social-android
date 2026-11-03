'use strict'

// Local storage remains authoritative. This module never removes or restores local files.
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { cleanStorageRow, revisionFilePath } = require('./storage-scan-coordinator')

const fail = code => Object.assign(new Error(code), { code })
function integer(value, fallback, min, max) {
  const number = value === undefined || value === '' ? fallback : Number(value)
  if (!Number.isSafeInteger(number) || number < min || number > max) throw fail('R2_CONFIG_INTEGER_INVALID')
  return number
}
function configuration(env = process.env) {
  const mode = String(env.R2_MIRROR_ENABLED || 'false')
  if (!['true', 'false'].includes(mode)) throw fail('R2_MIRROR_ENABLED_INVALID')
  if (mode === 'false') return Object.freeze({ enabled: false })
  const endpoint = new URL(String(env.R2_ENDPOINT || ''))
  if (endpoint.protocol !== 'https:' || endpoint.username || endpoint.password || endpoint.port ||
      endpoint.pathname !== '/' || endpoint.search || endpoint.hash ||
      !/^[a-f0-9]{32}(?:\.(?:eu|us|fedramp))?\.r2\.cloudflarestorage\.com$/.test(endpoint.hostname)) throw fail('R2_ENDPOINT_INVALID')
  const bucket = String(env.R2_BUCKET || '')
  if (!/^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$/.test(bucket)) throw fail('R2_BUCKET_INVALID')
  const accessKeyId = String(env.R2_ACCESS_KEY_ID || '').trim()
  const secretAccessKey = String(env.R2_SECRET_ACCESS_KEY || '').trim()
  if (!accessKeyId || !secretAccessKey) throw fail('R2_CREDENTIALS_REQUIRED')
  const root = String(env.STORAGE_ROOT || '')
  if (!path.isAbsolute(root)) throw fail('R2_LOCAL_ROOT_REQUIRED')
  return Object.freeze({ enabled: true, endpoint: endpoint.origin, bucket, accessKeyId, secretAccessKey,
    root: path.resolve(root), maxBytes: integer(env.R2_MIRROR_MAX_BYTES, 9_000_000_000, 1, Number.MAX_SAFE_INTEGER),
    batchSize: integer(env.R2_MIRROR_BATCH_SIZE, 8, 1, 100), intervalMs: integer(env.R2_MIRROR_INTERVAL_MS, 30_000, 1000, 3_600_000) })
}
function identity(row) {
  if (!cleanStorageRow(row) || !/^[a-f0-9-]{36}$/i.test(String(row.id || ''))) throw fail('R2_SOURCE_NOT_CLEAN')
  const fingerprint = crypto.createHash('sha256').update(JSON.stringify([
    row.id, row.bucket_id, row.name, row.object_revision, row.scan_sha256, Number(row.size_bytes), row.content_type,
  ])).digest('hex')
  return { fingerprint, key: `novvin/v1/${row.id}/${fingerprint}`, bytes: Number(row.size_bytes) }
}
async function openSource(root, row) {
  const expected = revisionFilePath(root, row.object_revision)
  if (row.local_path && path.resolve(row.local_path) !== expected) throw fail('R2_SOURCE_PATH_MISMATCH')
  const actualRoot = await fs.promises.realpath(root)
  const actual = await fs.promises.realpath(expected)
  if (!actual.startsWith(actualRoot + path.sep) || actual !== path.join(actualRoot, path.relative(root, expected))) throw fail('R2_SOURCE_PATH_ESCAPE')
  const handle = await fs.promises.open(expected, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0))
  try {
    const stat = await handle.stat()
    if (!stat.isFile() || stat.size !== Number(row.size_bytes)) throw fail('R2_SOURCE_SIZE_MISMATCH')
    const digest = crypto.createHash('sha256')
    for await (const chunk of handle.createReadStream({ start: 0, autoClose: false })) digest.update(chunk)
    if (digest.digest('hex') !== row.scan_sha256) throw fail('R2_SOURCE_HASH_MISMATCH')
    return handle
  } catch (error) { await handle.close(); throw error }
}
function safeError(error) {
  // SDK errors may contain signed URLs or identifiers; persist a small approved vocabulary only.
  if (/^R2_[A-Z_]+$/.test(error?.code || '')) return error.code
  const status = error?.$metadata?.httpStatusCode
  return [400, 401, 403, 404, 409, 429, 500, 502, 503, 504].includes(status) ? `R2_HTTP_${status}` : 'R2_REMOTE_OR_DATABASE_FAILURE'
}
async function replicate({ config, repository, remote, row }) {
  const object = identity(row)
  const reservation = await repository.reserve(row, object, config.maxBytes)
  if (!reservation) return { state: 'budget-paused' }
  let source
  try {
    source = await openSource(config.root, row)
    // A journal record precedes the external write. Retry adopts the same immutable key.
    await remote.putVerified({ key: object.key, source, size: object.bytes, sha256: row.scan_sha256, contentType: row.content_type })
    const current = await repository.current(row.id)
    if (!current || !cleanStorageRow(current) || identity(current).fingerprint !== object.fingerprint) {
      await repository.markDeleting(object.key)
      return { state: 'obsolete' }
    }
    await repository.markReady(object.key)
    return { state: 'ready' }
  } catch (error) {
    await repository.retry(object.key, safeError(error))
    return { state: 'retry', code: safeError(error) }
  } finally { if (source) await source.close() }
}
module.exports = { configuration, identity, openSource, replicate, safeError }
