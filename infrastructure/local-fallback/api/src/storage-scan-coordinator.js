'use strict'

const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { createStorageClamd, STORAGE_SCAN_MAX_BYTES } = require('./storage-clamd')
const { boundedWorkQueue } = require('./bounded-work-queue')
const { scanEnforcementEnabled } = require('./storage-media-reference')
const TICKET = Symbol('server-owned-storage-scan-ticket')
const error = (code, status = 503) => Object.assign(new Error(code), { status, code })
const STORAGE_SCAN_SCHEMA_SQL = "ALTER TABLE fallback_storage_objects\n  ADD COLUMN IF NOT EXISTS scan_state text NOT NULL DEFAULT 'legacy_unverified',\n  ADD COLUMN IF NOT EXISTS object_revision uuid,\n  ADD COLUMN IF NOT EXISTS scan_sha256 text,\n  ADD COLUMN IF NOT EXISTS scan_bytes bigint,\n  ADD COLUMN IF NOT EXISTS scan_engine text,\n  ADD COLUMN IF NOT EXISTS scan_signature_version text,\n  ADD COLUMN IF NOT EXISTS scan_at timestamptz,\n  ADD COLUMN IF NOT EXISTS scan_policy_sha256 text;\nDO $storage_scan$ BEGIN\n  IF NOT EXISTS (SELECT 1 FROM pg_constraint\n    WHERE conrelid='fallback_storage_objects'::regclass AND conname='storage_scan_state_check') THEN\n    ALTER TABLE fallback_storage_objects ADD CONSTRAINT storage_scan_state_check\n      CHECK (scan_state IN ('legacy_unverified','clean','rejected','revoked')) NOT VALID;\n  END IF;\n  IF NOT EXISTS (SELECT 1 FROM pg_constraint\n    WHERE conrelid='fallback_storage_objects'::regclass AND conname='storage_clean_proof_check') THEN\n    ALTER TABLE fallback_storage_objects ADD CONSTRAINT storage_clean_proof_check\n      CHECK ((scan_state <> 'clean' OR (object_revision IS NOT NULL AND scan_sha256 ~ '^[a-f0-9]{64}$'\n        AND scan_bytes = size_bytes AND scan_bytes > 0 AND scan_engine IS NOT NULL\n        AND length(trim(scan_engine)) > 0 AND scan_signature_version IS NOT NULL\n        AND length(trim(scan_signature_version)) > 0 AND scan_at IS NOT NULL\n        AND scan_policy_sha256 ~ '^[a-f0-9]{64}$')) IS TRUE) NOT VALID;\n  END IF;\nEND $storage_scan$;\nCREATE TABLE IF NOT EXISTS fallback_storage_scan_attempts (\n  revision uuid PRIMARY KEY, owner_id uuid NOT NULL, bucket_id text NOT NULL, object_name text NOT NULL,\n  state text NOT NULL CHECK(state IN ('scanning','clean','rejected','scan_failed')),\n  source_sha256 text NOT NULL, source_bytes bigint NOT NULL, delivery_sha256 text, delivery_bytes bigint,\n  engine text, signature_version text, scan_at timestamptz, policy_sha256 text, error_code text,\n  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()\n);\nCREATE INDEX IF NOT EXISTS storage_scan_attempts_owner ON fallback_storage_scan_attempts(owner_id, created_at DESC);"

async function ensureStorageScanSchema(pool, { callerTransaction = false } = {}) {
  // pg.Pool and pg.Client both expose connect(). A leased/already connected
  // Client belongs to the caller: reconnecting or releasing it breaks startup.
  const borrowed = typeof pool?.release === 'function' ||
    (typeof pool?.query === 'function' && (typeof pool?.connect !== 'function' || pool._connected === true))
  const ownsLease = !borrowed
  const client = ownsLease ? await pool.connect() : pool
  let transaction = false
  try {
    if (!callerTransaction) { await client.query('BEGIN'); transaction = true }
    // Serialize concurrent API initializations and repeated migrations for this schema.
    await client.query("SELECT pg_advisory_xact_lock(hashtextextended('novvin-storage-scan-schema-v1', 0))")
    // A pre-migrated schema must be usable by a DML-only role; IF NOT EXISTS
    // does not itself exempt ALTER TABLE from PostgreSQL ownership checks.
    const schema = await client.query(`SELECT
      (SELECT count(*)=8 FROM pg_attribute WHERE attrelid='fallback_storage_objects'::regclass
        AND NOT attisdropped AND attname IN ('scan_state','object_revision','scan_sha256','scan_bytes',
          'scan_engine','scan_signature_version','scan_at','scan_policy_sha256'))
      AND to_regclass('fallback_storage_scan_attempts') IS NOT NULL
      AND to_regclass('storage_scan_attempts_owner') IS NOT NULL
      AND (SELECT count(*)=2 FROM pg_constraint WHERE conrelid='fallback_storage_objects'::regclass
        AND contype='c' AND (conname='storage_scan_state_check' OR
          (conname='storage_clean_proof_check' AND pg_get_constraintdef(oid) LIKE '%IS TRUE%')))
      AS ready`)
    if (schema.rows[0]?.ready !== true) await client.query(STORAGE_SCAN_SCHEMA_SQL)
    if (!callerTransaction) { await client.query('COMMIT'); transaction = false }
  } catch (cause) {
    if (transaction) await client.query('ROLLBACK').catch(() => {})
    throw Object.assign(error('STORAGE_SCAN_SCHEMA_UNAVAILABLE', 500), { cause })
  } finally { if (ownsLease) client.release() }
}

function cleanStorageRow(row) {
  return row?.scan_state === 'clean' && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(row.object_revision || '') &&
    /^[a-f0-9]{64}$/.test(row.scan_sha256 || '') && row.scan_bytes != null && row.size_bytes != null &&
    Number.isSafeInteger(Number(row.scan_bytes)) && Number(row.scan_bytes) > 0 && Number(row.scan_bytes) === Number(row.size_bytes) &&
    typeof row.scan_engine === 'string' && row.scan_engine.trim().length > 0 &&
    typeof row.scan_signature_version === 'string' && row.scan_signature_version.trim().length > 0 &&
    Number.isFinite(Date.parse(row.scan_at)) && /^[a-f0-9]{64}$/.test(row.scan_policy_sha256 || '')
}
function revisionFilePath(root, revision) {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(revision || '')) throw error('STORAGE_REVISION_INVALID', 500)
  return path.join(root, '.objects', revision.slice(0, 2), revision + '.blob')
}
async function digestFile(filePath) {
  const hash = crypto.createHash('sha256'); let bytes = 0
  for await (const chunk of fs.createReadStream(filePath)) { hash.update(chunk); bytes += chunk.length }
  return { sha256: hash.digest('hex'), bytes }
}

function createStorageScanCoordinator({ root, enforced = scanEnforcementEnabled(),
  scanner = createStorageClamd(), queue = boundedWorkQueue({ concurrency: 2, maxQueued: 4, queueWaitMs: 3000 }) } = {}) {
  if (!root || !path.isAbsolute(root)) throw error('STORAGE_SCAN_ROOT_INVALID', 500)
  const quarantineRoot = path.join(root, '.quarantine')
  const objectLockKey = (bucket, objectPath) => `${bucket}/${objectPath}`

  async function withObjectLock(pool, bucket, objectPath, work) {
    if (!enforced) return work(pool)
    const client = await pool.connect(), key = objectLockKey(bucket, objectPath)
    let acquired = false
    try {
      const lock = await client.query('SELECT pg_try_advisory_lock(hashtextextended($1::text, 0)) AS acquired', [key])
      acquired = lock.rows[0]?.acquired === true
      if (!acquired) throw error('STORAGE_OBJECT_BUSY', 409)
      return await work(client)
    } finally {
      if (acquired) await client.query('SELECT pg_advisory_unlock(hashtextextended($1::text, 0))', [key]).catch(() => {})
      client.release()
    }
  }

  async function publish(pool, req, options, performWrite) {
    if (!enforced) return performWrite(pool, options)
    if (!req.auth?.sub || String(req.auth.sub) !== String(options.ownerId)) throw error('STORAGE_SCAN_OWNER_REQUIRED', 403)
    return queue.run(async () => {
      await scanner.readiness()
      const revision = crypto.randomUUID()
      const stagedPath = path.join(quarantineRoot, revision + '.part')
      const destination = revisionFilePath(root, revision)
      let client, lockAcquired = false, transaction = false, committed = false, commitAttempted = false
      try {
        client = await pool.connect()
        const lockKey = objectLockKey(options.bucket, options.objectPath || options.fallbackName)
        const locked = await client.query('SELECT pg_try_advisory_lock(hashtextextended($1::text, 0)) AS acquired', [lockKey])
        lockAcquired = locked.rows[0]?.acquired === true
        if (!lockAcquired) throw error('STORAGE_OBJECT_BUSY', 409)
        await fs.promises.mkdir(quarantineRoot, { recursive: true, mode: 0o700 })
        if (options.sourcePath) {
          const source = await fs.promises.lstat(options.sourcePath)
          if (!source.isFile() || source.isSymbolicLink()) throw error('STORAGE_SCAN_FILE_INVALID', 415)
          if (source.size !== Number(options.sizeBytes) || source.size > scanner.maxBytes || source.size < 1) throw error('STORAGE_SCAN_FILE_LIMIT', 413)
          await fs.promises.copyFile(options.sourcePath, stagedPath, fs.constants.COPYFILE_EXCL)
          await fs.promises.chmod(stagedPath, 0o600)
        } else {
          if (!Buffer.isBuffer(options.buffer) || options.buffer.length < 1 || options.buffer.length > scanner.maxBytes) throw error('STORAGE_SCAN_FILE_LIMIT', 413)
          await fs.promises.writeFile(stagedPath, options.buffer, { flag: 'wx', mode: 0o600 })
        }
        const original = await digestFile(stagedPath)
        await client.query(`INSERT INTO fallback_storage_scan_attempts(revision, owner_id, bucket_id, object_name, state, source_sha256, source_bytes)
          VALUES($1,$2,$3,$4,'scanning',$5,$6)`, [revision, req.auth.sub, options.bucket, options.objectPath || options.fallbackName, original.sha256, original.bytes])
        const scanned = await scanner.scan(stagedPath, { expectedSize: original.bytes })
        if (scanned.verdict !== 'clean' || scanned.sha256 !== original.sha256 || scanned.bytesScanned !== original.bytes) throw error('STORAGE_SCAN_RESULT_UNBOUND')
        const after = await digestFile(stagedPath)
        if (after.sha256 !== original.sha256 || after.bytes !== original.bytes) throw error('STORAGE_SCAN_FILE_CHANGED')
        const ticket = {
          destination,
          async seal(validated) {
            const final = await digestFile(stagedPath)
            if (final.bytes !== validated.sizeBytes || final.bytes > scanner.maxBytes) throw error('STORAGE_SCAN_FILE_CHANGED')
            // Even a harmless normalization can change the hash. Scan the exact delivery bytes again.
            const proof = final.sha256 === scanned.sha256 ? scanned : await scanner.scan(stagedPath, { expectedSize: final.bytes })
            const stable = await digestFile(stagedPath)
            if (proof.verdict !== 'clean' || proof.sha256 !== stable.sha256 || proof.bytesScanned !== stable.bytes || stable.sha256 !== final.sha256) throw error('STORAGE_SCAN_RESULT_UNBOUND')
            const record = { revision, ...proof }
            await client.query('BEGIN'); transaction = true
            return record
          },
        }
        const row = await performWrite(client, { ...options, sourcePath: stagedPath, buffer: undefined, sizeBytes: original.bytes, [TICKET]: ticket })
        await client.query(`UPDATE fallback_storage_scan_attempts SET state='clean', delivery_sha256=$2, delivery_bytes=$3,
          engine=$4, signature_version=$5, scan_at=$6, policy_sha256=$7, updated_at=NOW() WHERE revision=$1`,
        [revision, row.scan_sha256, row.scan_bytes, row.scan_engine, row.scan_signature_version, row.scan_at, row.scan_policy_sha256])
        commitAttempted = true
        await client.query('COMMIT'); transaction = false; committed = true
        if (options.sourcePath) await fs.promises.unlink(options.sourcePath).catch(() => {})
        return row
      } catch (cause) {
        if (transaction) { await client.query('ROLLBACK').catch(() => {}); transaction = false }
        // A broken connection during COMMIT does not tell us whether PostgreSQL committed.
        // Preserve the inert scanned revision for reconciliation; never remove bytes that a
        // committed row may reference, nor overwrite a possibly committed clean journal.
        if (client && !commitAttempted) await client.query(`UPDATE fallback_storage_scan_attempts SET state=$2, error_code=$3, updated_at=NOW() WHERE revision=$1`,
          [revision, cause.code === 'STORAGE_FILE_REJECTED' ? 'rejected' : 'scan_failed', String(cause.code || 'STORAGE_PUBLICATION_FAILED').slice(0, 80)]).catch(() => {})
        throw cause
      } finally {
        await fs.promises.unlink(stagedPath).catch(() => {})
        // A failed transaction never leaves delivery bytes at a reachable path.
        if (!committed && !commitAttempted) await fs.promises.unlink(destination).catch(() => {})
        if (client && lockAcquired) await client.query('SELECT pg_advisory_unlock(hashtextextended($1::text, 0))', [objectLockKey(options.bucket, options.objectPath || options.fallbackName)]).catch(() => {})
        client?.release()
      }
    })
  }

  async function assertReference(pool, bucket, names, ownerId) {
    if (!enforced) return true
    const { rows } = await pool.query(`SELECT * FROM fallback_storage_objects WHERE bucket_id=$1 AND name=ANY($2::text[])
      AND ($3::uuid IS NULL OR owner_id=$3::uuid) LIMIT 1`, [bucket, names, ownerId || null])
    return Boolean(rows[0] && cleanStorageRow(rows[0]))
  }
  return { enforced, publish, withObjectLock, assertReference, readiness: scanner.readiness, snapshot: queue.snapshot, maxBytes: scanner.maxBytes }
}

module.exports = { createStorageScanCoordinator, cleanStorageRow, revisionFilePath, digestFile, ensureStorageScanSchema, STORAGE_SCAN_SCHEMA_SQL, STORAGE_SCAN_TICKET: TICKET, STORAGE_SCAN_MAX_BYTES }
