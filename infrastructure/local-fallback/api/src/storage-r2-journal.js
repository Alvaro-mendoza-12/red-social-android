'use strict'
const { identity } = require('./storage-r2-mirror')

const SCHEMA = `CREATE TABLE IF NOT EXISTS fallback_storage_r2_mirrors (
  target text NOT NULL, remote_key text NOT NULL, object_id uuid NOT NULL, fingerprint text NOT NULL CHECK(fingerprint~'^[a-f0-9]{64}$'),
  bucket_id text NOT NULL, object_name text NOT NULL, object_revision uuid NOT NULL,
  sha256 text NOT NULL CHECK(sha256~'^[a-f0-9]{64}$'), size_bytes bigint NOT NULL CHECK(size_bytes>0), content_type text NOT NULL,
  state text NOT NULL CHECK(state IN ('pending','ready','deleting','deleted')) DEFAULT 'pending',
  attempts integer NOT NULL DEFAULT 0, last_error text, retry_at timestamptz NOT NULL DEFAULT now(),
  verified_at timestamptz, updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(target,remote_key), UNIQUE(target,object_id,fingerprint));
CREATE INDEX IF NOT EXISTS storage_r2_pending ON fallback_storage_r2_mirrors(state,retry_at);
CREATE INDEX IF NOT EXISTS storage_r2_objects ON fallback_storage_r2_mirrors(object_id);`

// Use one leased session under a global advisory lock throughout each batch.
// The source registry is polled so imports, administrative deletion and all upload
// protocols participate without adding unverified hooks to every producer.
function repository(db, config) {
  const target = `${config.endpoint}/${config.bucket}`
  return {
    async reserve(row, object, maxBytes) {
      const existing = await db.query('SELECT * FROM fallback_storage_r2_mirrors WHERE remote_key=$1 AND target=$2', [object.key, target])
      if (existing.rows[0] && existing.rows[0].state !== 'deleted') return existing.rows[0].state === 'pending'
      const usage = await db.query("SELECT COALESCE(sum(size_bytes),0)::text AS bytes FROM fallback_storage_r2_mirrors WHERE state<>'deleted' AND target=$1", [target])
      if (BigInt(usage.rows[0].bytes) + BigInt(object.bytes) > BigInt(maxBytes)) return false
      await db.query(`INSERT INTO fallback_storage_r2_mirrors(remote_key,object_id,fingerprint,bucket_id,object_name,object_revision,sha256,size_bytes,content_type,target)
        VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10) ON CONFLICT(target,remote_key) DO UPDATE
        SET state='pending',attempts=0,last_error=NULL,retry_at=now(),verified_at=NULL,updated_at=now()`,
      [object.key, row.id, object.fingerprint, row.bucket_id, row.name, row.object_revision, row.scan_sha256, object.bytes, row.content_type, target])
      return true
    },
    async current(id) { return (await db.query('SELECT * FROM fallback_storage_objects WHERE id=$1', [id])).rows[0] },
    async markReady(key) { await db.query("UPDATE fallback_storage_r2_mirrors SET state='ready',verified_at=now(),last_error=NULL,attempts=0,updated_at=now() WHERE remote_key=$1 AND state='pending' AND target=$2", [key, target]) },
    async markDeleting(key) { await db.query("UPDATE fallback_storage_r2_mirrors SET state='deleting',retry_at=now(),updated_at=now() WHERE remote_key=$1 AND state<>'deleted' AND target=$2", [key, target]) },
    async retry(key, code) { await db.query(`UPDATE fallback_storage_r2_mirrors SET attempts=LEAST(attempts+1,30), last_error=$2,
      retry_at=now()+make_interval(secs=>LEAST(3600, 30*power(2,LEAST(attempts,7)))::int),updated_at=now() WHERE remote_key=$1 AND target=$3`, [key, code, target]) },
  }
}
async function batch(db, config, remote) {
  const { replicate, safeError } = require('./storage-r2-mirror')
  const journal = repository(db, config)
  const target = `${config.endpoint}/${config.bucket}`
  const acquired = (await db.query("SELECT pg_try_advisory_lock(hashtextextended('novvin-r2-mirror-v1',0)) AS acquired")).rows[0]?.acquired
  if (!acquired) return { busy: true }
  const counts = { ready: 0, retry: 0, obsolete: 0, deleted: 0, budgetPaused: 0 }
  try {
    // A changed/deleted/rejected source invalidates its old mirror. Remote keys
    // are immutable; deleting a previous revision cannot delete a newer upload.
    await db.query(`UPDATE fallback_storage_r2_mirrors m SET state='deleting',retry_at=now(),updated_at=now()
      WHERE m.target=$1 AND m.state IN ('pending','ready') AND NOT EXISTS (SELECT 1 FROM fallback_storage_objects o
        WHERE o.id=m.object_id AND o.object_revision=m.object_revision AND o.scan_sha256=m.sha256
        AND o.size_bytes=m.size_bytes AND o.bucket_id=m.bucket_id AND o.name=m.object_name AND o.content_type=m.content_type AND o.scan_state='clean')`, [target])
    const obsolete = await db.query("SELECT remote_key FROM fallback_storage_r2_mirrors WHERE state='deleting' AND retry_at<=now() AND target=$2 ORDER BY retry_at LIMIT $1", [config.batchSize, target])
    for (const item of obsolete.rows) {
      try {
        await remote.remove(item.remote_key)
        await db.query("UPDATE fallback_storage_r2_mirrors SET state='deleted',last_error=NULL,updated_at=now() WHERE remote_key=$1 AND target=$2", [item.remote_key, target])
        counts.deleted++
      } catch (error) { await journal.retry(item.remote_key, safeError(error)); counts.retry++ }
    }
    const pending = await db.query(`SELECT o.* FROM fallback_storage_objects o
      WHERE o.scan_state='clean' AND o.object_revision IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM fallback_storage_r2_mirrors m WHERE m.target=$2 AND m.object_id=o.id AND m.object_revision=o.object_revision
          AND m.sha256=o.scan_sha256 AND m.size_bytes=o.size_bytes AND m.bucket_id=o.bucket_id AND m.object_name=o.name
          AND m.content_type=o.content_type AND ((m.state='ready') OR (m.state<>'deleted' AND m.retry_at>now())))
      ORDER BY o.created_at,o.id LIMIT $1`, [config.batchSize, target])
    for (const row of pending.rows) {
      try {
        // Unsupported proof remains visible in the inventory; never upload quarantine.
        identity(row)
        const result = await replicate({ config, repository: journal, remote, row })
        if (result.state === 'budget-paused') counts.budgetPaused++
        else counts[result.state]++
      } catch (error) {
        // Invalid proof is a data defect, not an external authorization to copy it.
        if (error.code !== 'R2_SOURCE_NOT_CLEAN') throw error
        counts.retry++
      }
    }
    return counts
  } finally { await db.query("SELECT pg_advisory_unlock(hashtextextended('novvin-r2-mirror-v1',0))") }
}
module.exports = { SCHEMA, repository, batch }
