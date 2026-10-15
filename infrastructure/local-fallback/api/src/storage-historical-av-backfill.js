'use strict'

// Administrative library only. No HTTP route, startup task, or automatic apply.
// Plans contain private paths/identifiers and must stay in the protected server.
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { pipeline } = require('node:stream/promises')
const { createStorageScanCoordinator, cleanStorageRow, digestFile, STORAGE_SCAN_TICKET,
  STORAGE_SCAN_MAX_BYTES } = require('./storage-scan-coordinator')
const { allowedBuckets, validateUpload } = require('./storage-routes')
const VERSION = 1
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
const error = (code, status = 409) => Object.assign(new Error(code), { code, status })
const bounded = (value, min, max) => Number.isSafeInteger(value) && value >= min && value <= max
const hash = value => crypto.createHash('sha256').update(value).digest('hex')
function canonical(value) {
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']'
  if (value && typeof value === 'object') return '{' + Object.keys(value).sort().map(key => JSON.stringify(key) + ':' + canonical(value[key])).join(',') + '}'
  return JSON.stringify(value)
}
const planDigest = plan => hash(canonical(plan))
const pseudonym = snapshot => hash(snapshot.id).slice(0, 24)
const fingerprint = stat => ({ dev: String(stat.dev), ino: String(stat.ino), size: String(stat.size),
  mtimeNs: String(stat.mtimeNs), ctimeNs: String(stat.ctimeNs) })
const equal = (left, right) => canonical(left) === canonical(right)
async function syncDirectory(directory) {
  const handle = await fs.promises.open(directory, fs.constants.O_RDONLY | (fs.constants.O_DIRECTORY || 0))
  try { await handle.sync() } finally { await handle.close() }
}
async function syncFile(file) {
  const handle = await fs.promises.open(file, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0))
  try { await handle.sync() } finally { await handle.close() }
}

async function rootPath(root) {
  if (!path.isAbsolute(root || '')) throw error('HISTORICAL_AV_ROOT_INVALID', 500)
  const normalized = path.resolve(root)
  const stat = await fs.promises.lstat(normalized)
  if (!stat.isDirectory() || stat.isSymbolicLink() || path.resolve(await fs.promises.realpath(normalized)) !== normalized) throw error('HISTORICAL_AV_ROOT_INVALID', 500)
  return normalized
}
async function inspectPath(root, file, { directory = false } = {}) {
  if (typeof file !== 'string' || !path.isAbsolute(file) || file.includes('\0')) throw error('HISTORICAL_AV_PATH_INVALID')
  const normalized = path.resolve(file), relative = path.relative(root, normalized)
  if (!relative || relative.startsWith('..' + path.sep) || relative === '..' || path.isAbsolute(relative)) throw error('HISTORICAL_AV_PATH_INVALID')
  let current = root, stat
  const parts = relative.split(path.sep)
  for (let index = 0; index < parts.length; index++) {
    current = path.join(current, parts[index])
    stat = await fs.promises.lstat(current, { bigint: true })
    if (stat.isSymbolicLink() || (index < parts.length - 1 || directory ? !stat.isDirectory() : !stat.isFile())) throw error('HISTORICAL_AV_PATH_INVALID')
  }
  return { normalized, fingerprint: fingerprint(stat) }
}
async function safeDirectory(root, directory) {
  const relative = path.relative(root, path.resolve(directory))
  if (!relative || relative === '..' || relative.startsWith('..' + path.sep) || path.isAbsolute(relative)) throw error('HISTORICAL_AV_PATH_INVALID')
  let current = root
  for (const part of relative.split(path.sep)) {
    current = path.join(current, part)
    await fs.promises.mkdir(current, { mode: 0o700 }).catch(cause => { if (cause.code !== 'EEXIST') throw cause })
    const stat = await fs.promises.lstat(current)
    if (!stat.isDirectory() || stat.isSymbolicLink()) throw error('HISTORICAL_AV_PATH_INVALID')
    // Flush existing entries too: a previous process may have failed between
    // mkdir and its parent barrier. A retry must not assume they were durable.
    await syncDirectory(current); await syncDirectory(path.dirname(current))
  }
}
async function createJournal(root, plan, digest) {
  const directory = path.join(root, '.backfill-journal')
  await safeDirectory(root, directory)
  const filename = path.join(directory, crypto.randomUUID() + '.jsonl')
  async function append(value, first = false) {
    const handle = await fs.promises.open(filename, first ? 'wx' : 'a', 0o600)
    try { await handle.writeFile(JSON.stringify({ at: new Date().toISOString(), ...value }) + '\n'); await handle.sync() }
    finally { await handle.close() }
    if (first) await syncDirectory(directory)
  }
  await append({ stage: 'planned', planDigest: digest, plan }, true)
  return { filename, append }
}
function validSnapshot(snapshot) {
  return snapshot && UUID.test(snapshot.id || '') && UUID.test(snapshot.owner_id || '') &&
    snapshot.scan_state === 'legacy_unverified' && typeof snapshot.bucket_id === 'string' && snapshot.bucket_id.length > 0 &&
    typeof snapshot.name === 'string' && snapshot.name.length > 0 && typeof snapshot.local_path === 'string' &&
    bounded(Number(snapshot.size_bytes), 1, STORAGE_SCAN_MAX_BYTES)
}
async function validateHistoricalUpload(snapshot, sourcePath) {
  // Explicitly reject executable/active file extensions, including APK ZIPs.
  // MIME stored in the historical registry is only a claim, never a proof.
  const extension = path.extname(snapshot.name).slice(1).toLowerCase()
  if (new Set(['apk','aab','exe','dll','msi','com','bat','cmd','ps1','sh','js','mjs','cjs','html','htm','xhtml','svg']).has(extension)) throw error('HISTORICAL_AV_ACTIVE_FILE_BLOCKED',415)
  try {
    return await validateUpload({ bucket:snapshot.bucket_id,objectPath:snapshot.name,contentType:snapshot.content_type,
      sourcePath,sizeBytes:Number(snapshot.size_bytes) })
  } catch (cause) {
    // The existing validator uses stable message codes on some policy errors.
    if (!cause.code) cause.code = /^[A-Z0-9_]+$/.test(cause.message||'') ? cause.message : 'HISTORICAL_AV_MEDIA_POLICY_FAILED'
    throw cause
  }
}

async function planHistoricalAvBackfill(pool, { root, cursor = null, maxObjects = 20,
  maxInputBytes = 512 * 1024 ** 2 } = {}) {
  const normalizedRoot = await rootPath(root)
  if (cursor !== null && !UUID.test(cursor) || !bounded(maxObjects, 1, 100) || !bounded(maxInputBytes, 1, 4 * 1024 ** 3)) throw error('HISTORICAL_AV_PLAN_LIMIT_INVALID', 400)
  const rows = (await pool.query(`SELECT to_jsonb(o) AS snapshot FROM fallback_storage_objects o
    WHERE scan_state='legacy_unverified' AND ($1::uuid IS NULL OR id>$1::uuid)
    ORDER BY id LIMIT $2`, [cursor, maxObjects + 1])).rows
  const entries = [], blocked = []
  let bytes = 0, nextCursor = cursor, exhausted = rows.length <= maxObjects
  for (const { snapshot } of rows.slice(0, maxObjects)) {
    if (validSnapshot(snapshot) && bytes + Number(snapshot.size_bytes) > maxInputBytes) { exhausted = false; break }
    nextCursor = snapshot.id
    try {
      if (!validSnapshot(snapshot)) throw error('HISTORICAL_AV_OBJECT_INVALID')
      if (!allowedBuckets.has(snapshot.bucket_id)) throw error('BUCKET_NOT_ALLOWED',400)
      const source = await inspectPath(normalizedRoot, snapshot.local_path)
      if (Number(source.fingerprint.size) !== Number(snapshot.size_bytes)) throw error('HISTORICAL_AV_SIZE_CHANGED')
      entries.push({ snapshot, fingerprint: source.fingerprint }); bytes += Number(snapshot.size_bytes)
    } catch (cause) { blocked.push({ object: pseudonym(snapshot), code: String(cause.code || 'HISTORICAL_AV_SOURCE_UNAVAILABLE').slice(0, 80) }) }
  }
  const plan = { version: VERSION, root: normalizedRoot, createdAt: new Date().toISOString(), cursor,
    nextCursor, exhausted, maxObjects, maxInputBytes, inputBytes: bytes, entries, blocked }
  return { plan, digest: planDigest(plan) }
}

function validatePlan(plan, reviewedDigest, root) {
  if (!/^[a-f0-9]{64}$/.test(reviewedDigest || '') || planDigest(plan) !== reviewedDigest || plan?.version !== VERSION ||
      plan.root !== root || !Array.isArray(plan.entries) || !Array.isArray(plan.blocked) ||
      !bounded(plan.maxObjects, 1, 100) || !bounded(plan.maxInputBytes, 1, 4 * 1024 ** 3) || plan.entries.length > plan.maxObjects) throw error('HISTORICAL_AV_PLAN_NOT_REVIEWED', 400)
  const seen = new Set(); let bytes = 0
  for (const entry of plan.entries) {
    if (!validSnapshot(entry.snapshot) || !entry.fingerprint || seen.has(entry.snapshot.id)) throw error('HISTORICAL_AV_PLAN_INVALID', 400)
    seen.add(entry.snapshot.id); bytes += Number(entry.snapshot.size_bytes)
  }
  if (bytes !== plan.inputBytes || bytes > plan.maxInputBytes) throw error('HISTORICAL_AV_PLAN_INVALID', 400)
}

async function privateCopy(root, entry, target, lease) {
  const before = await inspectPath(root, entry.snapshot.local_path)
  if (!equal(before.fingerprint, entry.fingerprint)) throw error('HISTORICAL_AV_SOURCE_CHANGED')
  const source = await fs.promises.open(before.normalized, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0))
  const controller = new AbortController(), removeLost = lease.onLost(() => controller.abort())
  try {
    const opened = fingerprint(await source.stat({ bigint: true }))
    if (!equal(opened, before.fingerprint) || !equal((await inspectPath(root, before.normalized)).fingerprint, opened)) throw error('HISTORICAL_AV_SOURCE_CHANGED')
    lease.assertAlive()
    await pipeline(source.createReadStream({ autoClose: false }), fs.createWriteStream(target, { flags: 'wx', mode: 0o600 }), { signal: controller.signal })
    await syncFile(target)
    await syncDirectory(path.dirname(target))
    lease.assertAlive()
    const after = fingerprint(await source.stat({ bigint: true }))
    if (!equal(after, opened) || !equal((await inspectPath(root, before.normalized)).fingerprint, opened)) throw error('HISTORICAL_AV_SOURCE_CHANGED')
    return await digestFile(target)
  } finally { removeLost(); await source.close() }
}

// Capture COMMIT intent without changing the coordinator. Its ambiguous-commit
// policy preserves an installed revision; we preserve budget until reconciled.
function commitTrackedPool(pool, progress) {
  return { async connect() {
    const client = await pool.connect()
    return { release: client.release.bind(client), async query(sql, values) {
      if (typeof sql === 'string' && sql.trim().toUpperCase() === 'COMMIT') progress.commitAttempted = true
      return client.query(sql, values)
    } }
  } }
}

async function confirmCommitted(pool, coordinator, root, snapshot, proof) {
  if (!proof) return null
  return coordinator.withObjectLock(pool, snapshot.bucket_id, snapshot.name, async client => {
    const row = (await client.query('SELECT * FROM fallback_storage_objects WHERE id=$1', [snapshot.id])).rows[0]
    if (!cleanStorageRow(row) || row.object_revision !== proof.revision || row.scan_sha256 !== proof.sha256 || row.local_path !== proof.destination) return null
    const attempt = (await client.query("SELECT * FROM fallback_storage_scan_attempts WHERE revision=$1 AND state='clean'", [proof.revision])).rows[0]
    if (!attempt || attempt.delivery_sha256 !== proof.sha256 || Number(attempt.delivery_bytes) !== proof.bytesScanned) return null
    await inspectPath(root, row.local_path)
    const delivered = await digestFile(row.local_path)
    if (delivered.sha256 !== proof.sha256 || delivered.bytes !== proof.bytesScanned) return null
    return row
  })
}
async function recordRejection(pool, coordinator, snapshot) {
  return coordinator.withObjectLock(pool, snapshot.bucket_id, snapshot.name, async client => {
    const result = await client.query(`UPDATE fallback_storage_objects o SET scan_state='rejected',updated_at=now()
      WHERE id=$1 AND to_jsonb(o)=$2::jsonb RETURNING id`, [snapshot.id, JSON.stringify(snapshot)])
    return result.rowCount === 1
  })
}

async function applyHistoricalAvBackfill(pool, plan, { root, reviewedPlanDigest, admission,
  scanner, coordinator = createStorageScanCoordinator({ root, enforced: true, scanner }),
  stopOnFailure = true } = {}) {
  const normalizedRoot = await rootPath(root)
  validatePlan(plan, reviewedPlanDigest, normalizedRoot)
  if (coordinator.enforced !== true || typeof admission?.reserve !== 'function') throw error('HISTORICAL_AV_GUARDS_REQUIRED', 500)
  const report = { planDigest: reviewedPlanDigest, planned: plan.entries.length, inputBytes: plan.inputBytes,
    blocked: plan.blocked, results: [], complete: false }
  const tempRoot = path.join(normalizedRoot, '.tmp')
  await safeDirectory(normalizedRoot, tempRoot)
  const journal = await createJournal(normalizedRoot, plan, reviewedPlanDigest)
  report.privateJournal = journal.filename
  for (const entry of plan.entries) {
    const snapshot = entry.snapshot, progress = { commitAttempted: false, proof: null }
    const result = { object: pseudonym(snapshot), state: 'failed' }
    let lease, privateSource, keepReservation = false
    try {
      lease = await admission.reserve({ ownerId: snapshot.owner_id, bytes: Number(snapshot.size_bytes), kind: 'raw' })
      await journal.append({ stage: 'reserved', objectId: snapshot.id, reservation: lease.id })
      // A backfill revision may outlive the ordinary RAW TTL after a crash or
      // uncertain COMMIT. Pin this administrative reservation before copying.
      // Only explicit reconciliation of the private journal may release it.
      const pinned = await pool.query(`UPDATE fallback_storage_upload_reservations SET expires_at='infinity',updated_at=now()
        WHERE id=$1 AND owner_id=$2 AND kind='raw' RETURNING id`, [lease.id, snapshot.owner_id])
      if (pinned.rowCount !== 1) throw error('HISTORICAL_AV_RESERVATION_MISSING', 503)
      lease.assertAlive()
      privateSource = path.join(tempRoot, 'admitted-' + lease.id + '-' + crypto.randomUUID())
      const copied = await privateCopy(normalizedRoot, entry, privateSource, lease)
      if (copied.bytes !== Number(snapshot.size_bytes)) throw error('HISTORICAL_AV_SIZE_CHANGED')
      await validateHistoricalUpload(snapshot,privateSource)
      const published = await coordinator.publish(commitTrackedPool(pool, progress), { auth: { sub: snapshot.owner_id } }, {
        bucket: snapshot.bucket_id, objectPath: snapshot.name, ownerId: snapshot.owner_id,
        sourcePath: privateSource, sizeBytes: copied.bytes,
      }, async (client, prepared) => {
        lease.assertAlive()
        const current = (await client.query('SELECT to_jsonb(o) AS snapshot FROM fallback_storage_objects o WHERE id=$1', [snapshot.id])).rows[0]?.snapshot
        if (!equal(current, snapshot)) throw error('HISTORICAL_AV_OBJECT_CHANGED')
        if (!equal((await inspectPath(normalizedRoot, snapshot.local_path)).fingerprint, entry.fingerprint)) throw error('HISTORICAL_AV_SOURCE_CHANGED')
        const ticket = prepared[STORAGE_SCAN_TICKET]
        if (!ticket) throw error('HISTORICAL_AV_SCAN_TICKET_REQUIRED', 500)
        const validated = await validateHistoricalUpload(snapshot,prepared.sourcePath)
        const proof = await ticket.seal(validated)
        if (proof.sha256 !== copied.sha256 || proof.bytesScanned !== copied.bytes) throw error('HISTORICAL_AV_COPY_CHANGED')
        progress.proof = { ...proof, destination: ticket.destination }
        lease.assertAlive()
        await journal.append({ stage: 'sealed', objectId: snapshot.id, reservation: lease.id, proof: progress.proof })
        await safeDirectory(normalizedRoot, path.dirname(ticket.destination))
        // The revision is generated by the coordinator and never overwrites an old file.
        await fs.promises.link(prepared.sourcePath, ticket.destination)
        await fs.promises.unlink(prepared.sourcePath)
        // A committed row must not precede persistence of its delivery inode
        // and directory entry. Directory fsync failure is a publication failure.
        await syncFile(ticket.destination)
        await syncDirectory(path.dirname(ticket.destination))
        await syncDirectory(path.dirname(prepared.sourcePath))
        lease.assertAlive()
        const changed = await client.query(`UPDATE fallback_storage_objects o SET local_path=$3, scan_state='clean',
          object_revision=$4,scan_sha256=$5,scan_bytes=$6,scan_engine=$7,scan_signature_version=$8,
          scan_at=$9,scan_policy_sha256=$10,updated_at=now()
          WHERE id=$1 AND to_jsonb(o)=$2::jsonb RETURNING *`, [snapshot.id, JSON.stringify(snapshot), ticket.destination,
          proof.revision, proof.sha256, proof.bytesScanned, proof.engine, proof.signatureVersion, proof.scannedAt, proof.policySha256])
        if (changed.rowCount !== 1) throw error('HISTORICAL_AV_OBJECT_CHANGED')
        lease.assertAlive()
        if (!cleanStorageRow(changed.rows[0])) throw error('HISTORICAL_AV_PROOF_INVALID', 500)
        return changed.rows[0]
      })
      if (!cleanStorageRow(published)) throw error('HISTORICAL_AV_PROOF_INVALID', 500)
      result.state = 'clean'; result.revision = published.object_revision; result.sha256 = published.scan_sha256
    } catch (cause) {
      result.code = String(cause.code || 'HISTORICAL_AV_FAILED').slice(0, 80)
      if (progress.commitAttempted) {
        const confirmed = await confirmCommitted(pool, coordinator, normalizedRoot, snapshot, progress.proof).catch(() => null)
        if (confirmed) {
          result.state = 'clean'; result.revision = confirmed.object_revision; result.sha256 = confirmed.scan_sha256
          result.commitAcknowledgmentRecovered = true
        } else { result.state = 'uncertain'; keepReservation = true; result.reservation = lease?.id }
      } else if (cause.code === 'STORAGE_FILE_REJECTED') {
        const rejected = await recordRejection(pool, coordinator, snapshot).catch(() => false)
        result.state = rejected ? 'rejected' : 'failed'
        if (!rejected) result.rejectionStateUnconfirmed = true
      }
    } finally {
      // Only our private copy is disposable. Historical source and confirmed
      // immutable revisions are never deleted by this worker.
      if (privateSource) await fs.promises.unlink(privateSource).catch(() => {})
      if (lease) await lease.finish({ keep: keepReservation, expiresAt: 'infinity' }).catch(cause => {
        result.budgetReleaseUnconfirmed = true; result.budgetCode = String(cause.code || 'BUDGET_RELEASE_FAILED').slice(0, 80)
      })
    }
    await journal.append({ stage: 'result', objectId: snapshot.id, ...result })
    report.results.push(result)
    if (stopOnFailure && (result.state !== 'clean' || result.budgetReleaseUnconfirmed)) break
  }
  report.complete = report.blocked.length === 0 && report.results.length === plan.entries.length &&
    report.results.every(result => result.state === 'clean' && !result.budgetReleaseUnconfirmed)
  return report
}

async function reconcileHistoricalAvBackfill(pool, { root, privateJournal, admission, reviewedJournalDigest = null,
  scanner, coordinator = createStorageScanCoordinator({ root, enforced: true, scanner }) } = {}) {
  const normalizedRoot = await rootPath(root), journalRoot = path.join(normalizedRoot, '.backfill-journal')
  if (path.dirname(path.resolve(privateJournal || '')) !== journalRoot || !/^[a-f0-9-]{36}\.jsonl$/.test(path.basename(privateJournal || ''))) throw error('HISTORICAL_AV_JOURNAL_INVALID', 400)
  await inspectPath(normalizedRoot, privateJournal)
  const stat = await fs.promises.stat(privateJournal)
  if (stat.size > 32 * 1024 ** 2) throw error('HISTORICAL_AV_JOURNAL_LIMIT', 413)
  const text = await fs.promises.readFile(privateJournal, 'utf8'), journalDigest = hash(text)
  const events = text.trim().split('\n').map(line => JSON.parse(line)), header = events[0]
  validatePlan(header?.plan, header?.planDigest, normalizedRoot)
  if (header?.stage !== 'planned' || coordinator.enforced !== true || typeof admission?.removeOwnedTemps !== 'function' ||
    reviewedJournalDigest !== null && reviewedJournalDigest !== journalDigest) throw error('HISTORICAL_AV_JOURNAL_NOT_REVIEWED', 400)
  const report = { journalDigest, dryRun: reviewedJournalDigest === null, results: [] }
  const entries = new Map(header.plan.entries.map(entry => [entry.snapshot.id, entry.snapshot]))
  for (const reservationEvent of events.filter(event => event.stage === 'reserved')) {
    const snapshot = entries.get(reservationEvent.objectId), reservation = reservationEvent.reservation
    if (!snapshot || !UUID.test(reservation || '')) throw error('HISTORICAL_AV_JOURNAL_INVALID', 400)
    const sealed = events.find(event => event.stage === 'sealed' && event.objectId === snapshot.id && event.reservation === reservation)
    const proof = sealed?.proof, result = { object: pseudonym(snapshot), reservation, state: 'blocked' }
    try {
      await coordinator.withObjectLock(pool, snapshot.bucket_id, snapshot.name, async client => {
        const key = 'novvin-storage-upload-reservation:' + reservation
        const locked = (await client.query('SELECT pg_try_advisory_lock(hashtextextended($1,0)) AS acquired',[key])).rows[0]?.acquired === true
        if (!locked) throw error('HISTORICAL_AV_JOB_ACTIVE')
        try {
          const budget = (await client.query('SELECT * FROM fallback_storage_upload_reservations WHERE id=$1',[reservation])).rows[0]
          if (!budget) { result.state='already_released'; return }
          if (budget.kind !== 'raw' || budget.owner_id !== snapshot.owner_id || Number(budget.input_bytes) !== Number(snapshot.size_bytes)) throw error('HISTORICAL_AV_RESERVATION_CHANGED')
          const row = (await client.query('SELECT * FROM fallback_storage_objects WHERE id=$1',[snapshot.id])).rows[0]
          const attempt = proof ? (await client.query("SELECT * FROM fallback_storage_scan_attempts WHERE revision=$1 AND state='clean'",[proof.revision])).rows[0] : null
          if (!proof || !cleanStorageRow(row) || row.object_revision !== proof.revision || row.local_path !== proof.destination || row.scan_sha256 !== proof.sha256 ||
            !attempt || attempt.delivery_sha256 !== proof.sha256 || Number(attempt.delivery_bytes) !== proof.bytesScanned) throw error('HISTORICAL_AV_MANUAL_RECONCILIATION_REQUIRED')
          await inspectPath(normalizedRoot,row.local_path)
          const delivered = await digestFile(row.local_path)
          if (delivered.sha256 !== proof.sha256 || delivered.bytes !== proof.bytesScanned) throw error('HISTORICAL_AV_DELIVERY_CHANGED')
          result.state='confirmed_clean'
          if (!report.dryRun) {
            if (hash(await fs.promises.readFile(privateJournal,'utf8')) !== journalDigest) throw error('HISTORICAL_AV_JOURNAL_CHANGED')
            await admission.removeOwnedTemps(reservation,'raw')
            const removed = await client.query(`DELETE FROM fallback_storage_upload_reservations
              WHERE id=$1 AND owner_id=$2 AND kind='raw' AND input_bytes=$3 RETURNING id`,[reservation,snapshot.owner_id,snapshot.size_bytes])
            if (removed.rowCount !== 1) throw error('HISTORICAL_AV_RESERVATION_CHANGED')
            result.state='released_confirmed_clean'
          }
        } finally { await client.query('SELECT pg_advisory_unlock(hashtextextended($1,0))',[key]).catch(()=>{}) }
      })
    } catch (cause) { result.state='blocked'; result.code=String(cause.code||'HISTORICAL_AV_RECONCILIATION_FAILED').slice(0,80) }
    report.results.push(result)
  }
  return report
}

module.exports = { planHistoricalAvBackfill, applyHistoricalAvBackfill, reconcileHistoricalAvBackfill, historicalAvPlanDigest: planDigest }
