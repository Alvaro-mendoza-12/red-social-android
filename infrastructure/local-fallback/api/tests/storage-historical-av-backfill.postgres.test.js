'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const os = require('node:os')
const crypto = require('node:crypto')
const { Pool } = require('pg')
const { planHistoricalAvBackfill, applyHistoricalAvBackfill, reconcileHistoricalAvBackfill } = require('../src/storage-historical-av-backfill')
const { createStorageUploadAdmission, ensureStorageUploadAdmissionSchema } = require('../src/storage-upload-admission')
const { ensureStorageScanSchema, digestFile, cleanStorageRow } = require('../src/storage-scan-coordinator')
const connectionString = process.env.STORAGE_BACKFILL_TEST_DATABASE_URL
const inert = Buffer.from('Synthetic historical storage fixture; no malware.')
function deferred() { let resolve; const promise = new Promise(value => { resolve = value }); return { promise, resolve } }
function fail(code) { return Object.assign(new Error(code), { code }) }
async function fileCount(root) {
  const entries = await fs.promises.readdir(root, { withFileTypes: true }).catch(cause => cause.code === 'ENOENT' ? [] : Promise.reject(cause))
  let total = 0
  for (const entry of entries) total += entry.isDirectory() ? await fileCount(path.join(root, entry.name)) : 1
  return total
}
async function fixture(t, { scannerAction, readinessAction, poolAction } = {}) {
  const schema = 'historical_av_' + crypto.randomUUID().replaceAll('-', '')
  const root = await fs.promises.mkdtemp(path.join(os.tmpdir(), 'novvin-historical-av-'))
  const admin = new Pool({ connectionString })
  await admin.query('CREATE SCHEMA ' + schema)
  let pool = new Pool({ connectionString, max: 4, options: '-c search_path=' + schema + ',public' })
  const appName = 'backfill_lease_' + crypto.randomUUID().replaceAll('-', '')
  const workerRole = 'backfill_runtime_' + crypto.randomUUID().replaceAll('-', '')
  const owner = crypto.randomUUID()
  await pool.query(`CREATE TABLE fallback_storage_objects(id uuid PRIMARY KEY,bucket_id text NOT NULL,name text NOT NULL,
    owner_id uuid,content_type text,size_bytes bigint,public_url text,local_path text,metadata jsonb,
    created_at timestamptz DEFAULT now(),updated_at timestamptz DEFAULT now(),UNIQUE(bucket_id,name));
    CREATE TABLE fallback_resumable_uploads(id uuid PRIMARY KEY,owner_id uuid,state text,upload_length bigint,expires_at timestamptz);
    CREATE TABLE linked_messages(id uuid PRIMARY KEY,object_id uuid REFERENCES fallback_storage_objects(id),url text,visibility text);`)
  await ensureStorageScanSchema(pool); await ensureStorageUploadAdmissionSchema(pool)
  await admin.query('CREATE ROLE ' + workerRole + ' LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS')
  await admin.query('GRANT USAGE ON SCHEMA ' + schema + ' TO ' + workerRole)
  await pool.query('GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA ' + schema + ' TO ' + workerRole)
  await pool.end()
  const workerUrl = new URL(connectionString); workerUrl.username = workerRole
  pool = new Pool({connectionString:workerUrl.toString(),max:4,options:'-c search_path='+schema+',public'})
  const leasePool = new Pool({connectionString:workerUrl.toString(),max:7,options:'-c search_path='+schema+',public',application_name:appName})
  leasePool.on('error', () => {})
  const admission = createStorageUploadAdmission({ pool, leasePool, root, minFreeBytes: 0,
    statfs: async () => ({ bavail: 1_000_000n, bsize: 4096n }) })
  let scans = 0
  const scanner = { maxBytes: 500 * 1024 ** 2,
    async readiness() { if (readinessAction) await readinessAction() },
    async scan(file, { expectedSize }) {
      scans++
      if (scannerAction) await scannerAction({ file, pool, scans })
      const measured = await digestFile(file); assert.equal(measured.bytes, expectedSize)
      return { verdict: 'clean', sha256: measured.sha256, bytesScanned: measured.bytes,
        engine: 'SYNTHETIC_TEST_SCANNER', signatureVersion: 'inert-fixture-only', scannedAt: new Date().toISOString(), policySha256: 'a'.repeat(64) }
    } }
  const publishingPool = poolAction ? { query: pool.query.bind(pool), async connect() {
    const client = await pool.connect()
    return { release: client.release.bind(client), async query(sql, values) { return poolAction(client, sql, values, pool) } }
  } } : pool
  const f = { root, pool, publishingPool, admin, leasePool, owner, admission, scanner, appName,
    scans: () => scans,
    async add({ bytes = inert, ownerId = owner, file, id = crypto.randomUUID(), name = crypto.randomUUID() + '.txt' } = {}) {
      const source = file || path.join(root, name)
      if (!file) await fs.promises.writeFile(source, bytes, { mode: 0o600 })
      const url = 'https://private.invalid/storage/v1/object/public/chat-media/' + name
      await pool.query(`INSERT INTO fallback_storage_objects(id,bucket_id,name,owner_id,content_type,size_bytes,public_url,local_path,metadata)
        VALUES($1,'chat-media',$2,$3,'application/octet-stream',$4,$5,$6,$7)`, [id,name,ownerId,bytes.length,url,source,{visibility:'private',nested:{keep:'original'}}])
      await pool.query('INSERT INTO linked_messages VALUES($1,$2,$3,$4)', [crypto.randomUUID(),id,url,'private'])
      return { id, source, url, name }
    },
    async plan(options = {}) { return planHistoricalAvBackfill(pool, { root, ...options }) },
    async apply(prepared, options = {}) { return applyHistoricalAvBackfill(publishingPool, prepared.plan, { root,
      reviewedPlanDigest: prepared.digest, admission, scanner, ...options }) },
    async row(id) { return (await pool.query('SELECT * FROM fallback_storage_objects WHERE id=$1',[id])).rows[0] },
    async reservations() { return (await pool.query('SELECT * FROM fallback_storage_upload_reservations')).rows },
  }
  t.after(async () => {
    await leasePool.end(); await pool.end(); await admin.query('DROP SCHEMA ' + schema + ' CASCADE')
    await admin.query('DROP ROLE ' + workerRole); await admin.end()
    assert.ok(root.startsWith(path.resolve(os.tmpdir()) + path.sep + 'novvin-historical-av-'))
    await fs.promises.rm(root, { recursive: true, force: true })
  })
  return f
}

test('historical AV backfill: isolated PostgreSQL, real files, synthetic scanner', { skip: !connectionString || process.platform !== 'linux' }, async t => {
  await t.test('clean revision preserves original bytes, URL, owner, private metadata and message relation', async t => {
    const f = await fixture(t), object = await f.add(), original = await f.row(object.id)
    const role=(await f.pool.query('SELECT rolsuper,rolcreatedb,rolcreaterole,rolreplication,rolbypassrls FROM pg_roles WHERE rolname=current_user')).rows[0]
    assert.ok(Object.values(role).every(value=>value===false))
    const result = await f.apply(await f.plan())
    assert.equal(result.complete, true); assert.equal(result.results[0].state, 'clean')
    const row = await f.row(object.id); assert.equal(cleanStorageRow(row), true)
    for (const key of ['id','bucket_id','name','owner_id','content_type','size_bytes','public_url','metadata','created_at']) assert.deepEqual(row[key], original[key])
    assert.deepEqual(await fs.promises.readFile(object.source), inert)
    assert.deepEqual(await fs.promises.readFile(row.local_path), inert)
    assert.notEqual(row.local_path, object.source)
    const link = (await f.pool.query('SELECT * FROM linked_messages WHERE object_id=$1',[object.id])).rows[0]
    assert.equal(link.url, object.url); assert.equal(link.visibility, 'private')
    assert.equal((await f.reservations()).length, 0); assert.equal(await fileCount(path.join(f.root,'.tmp')),0)
    assert.equal(await fileCount(path.join(f.root,'.quarantine')),0)
    assert.equal((await f.plan()).plan.entries.length,0)
  })
  await t.test('bounded keyset plan, missing owner and tampered review digest', async t => {
    const f = await fixture(t)
    const ids = ['00000000-0000-4000-8000-000000000001','00000000-0000-4000-8000-000000000002','00000000-0000-4000-8000-000000000003']
    await f.add({ id: ids[0] }); await f.add({ id: ids[1] }); await f.add({ id: ids[2], ownerId: null })
    const first = await f.plan({ maxObjects: 1, maxInputBytes: inert.length })
    assert.equal(first.plan.entries.length,1); assert.equal(first.plan.inputBytes,inert.length)
    assert.equal(first.plan.nextCursor,ids[0]); assert.equal(first.plan.exhausted,false)
    const next = await f.plan({ cursor:first.plan.nextCursor,maxObjects:2 })
    assert.equal(next.plan.entries[0].snapshot.id,ids[1]); assert.equal(next.plan.blocked.length,1)
    assert.equal(next.plan.blocked[0].code,'HISTORICAL_AV_OBJECT_INVALID')
    assert.ok(!JSON.stringify(next.plan.blocked).includes(ids[2]))
    first.plan.entries[0].snapshot.public_url = 'https://tampered.invalid'
    await assert.rejects(f.apply(first), { code: 'HISTORICAL_AV_PLAN_NOT_REVIEWED' })
    assert.equal((await f.reservations()).length,0); assert.equal(f.scans(),0)
  })
  await t.test('inert scanner rejection preserves original, records rejected state by CAS and keeps durable journal', async t => {
    const f = await fixture(t, { scannerAction: async () => { throw fail('STORAGE_FILE_REJECTED') } }), object = await f.add()
    const result = await f.apply(await f.plan())
    assert.equal(result.complete,false); assert.equal(result.results[0].state,'rejected')
    assert.equal((await f.row(object.id)).scan_state,'rejected')
    assert.deepEqual(await fs.promises.readFile(object.source),inert)
    assert.equal((await f.pool.query('SELECT state FROM fallback_storage_scan_attempts')).rows[0].state,'rejected')
    assert.equal((await f.reservations()).length,0); assert.equal(await fileCount(path.join(f.root,'.objects')),0)
    assert.equal((await f.plan()).plan.entries.length,0)
    const events = (await fs.promises.readFile(result.privateJournal,'utf8')).trim().split('\n').map(JSON.parse)
    assert.equal(events[0].stage,'planned'); assert.equal(events.at(-1).state,'rejected')
  })
  await t.test('scanner unavailable never publishes or relabels legacy', async t => {
    const f = await fixture(t, { readinessAction: async () => { throw fail('STORAGE_SCAN_UNAVAILABLE') } }), object = await f.add()
    const result = await f.apply(await f.plan())
    assert.equal(result.results[0].code,'STORAGE_SCAN_UNAVAILABLE'); assert.equal(f.scans(),0)
    assert.equal((await f.row(object.id)).scan_state,'legacy_unverified')
    assert.equal((await f.reservations()).length,0); assert.equal(await fileCount(path.join(f.root,'.tmp')),0)
  })
  await t.test('source changed after planning fails before scanner without deleting original', async t => {
    const f = await fixture(t), object = await f.add(), prepared = await f.plan()
    await fs.promises.appendFile(object.source,'changed')
    const result = await f.apply(prepared)
    assert.equal(result.results[0].code,'HISTORICAL_AV_SOURCE_CHANGED'); assert.equal(f.scans(),0)
    assert.equal((await f.row(object.id)).scan_state,'legacy_unverified')
    assert.equal((await fs.promises.stat(object.source)).size,inert.length+7)
  })
  await t.test('outside-root source and symlink directory cannot be planned', async t => {
    const f = await fixture(t), outside = await fs.promises.mkdtemp(path.join(os.tmpdir(),'novvin-backfill-outside-'))
    t.after(async () => { assert.ok(outside.startsWith(path.resolve(os.tmpdir()) + path.sep + 'novvin-backfill-outside-')); await fs.promises.rm(outside,{recursive:true,force:true}) })
    const file = path.join(outside,'source.bin'); await fs.promises.writeFile(file,inert)
    await f.add({ file })
    const junction = path.join(f.root,'alias'); await fs.promises.symlink(outside,junction,process.platform==='win32'?'junction':'dir')
    await f.add({ file:path.join(junction,'source.bin') })
    const prepared = await f.plan()
    assert.equal(prepared.plan.entries.length,0); assert.equal(prepared.plan.blocked.length,2)
    assert.ok(prepared.plan.blocked.every(row=>row.code==='HISTORICAL_AV_PATH_INVALID'))
    assert.deepEqual(await fs.promises.readFile(file),inert)
  })
  await t.test('source changed during scanner cannot publish its stale copy', async t => {
    let source
    const f = await fixture(t, { scannerAction: async () => { await fs.promises.appendFile(source,'changed') } })
    const object = await f.add(); source=object.source
    const result = await f.apply(await f.plan())
    assert.equal(result.results[0].code,'HISTORICAL_AV_SOURCE_CHANGED')
    assert.equal((await f.row(object.id)).scan_state,'legacy_unverified')
    assert.equal(await fileCount(path.join(f.root,'.objects')),0)
  })
  await t.test('CAS rejects a real concurrent metadata write after validation and rolls back revision', async t => {
    let race = true
    const f = await fixture(t, { poolAction: async (client, sql, values, pool) => {
      if (sql.startsWith('UPDATE fallback_storage_objects o SET') && race) {
        race=false; await pool.query("UPDATE fallback_storage_objects SET metadata='{" + '"visibility":"private","changed":true' + "}'::jsonb WHERE id=$1",[values[0]])
      }
      return client.query(sql,values)
    } }), object = await f.add()
    const result = await f.apply(await f.plan())
    assert.equal(result.results[0].code,'HISTORICAL_AV_OBJECT_CHANGED')
    const row = await f.row(object.id)
    assert.equal(row.scan_state,'legacy_unverified'); assert.equal(row.metadata.changed,true)
    assert.equal(await fileCount(path.join(f.root,'.objects')),0)
    assert.equal((await f.reservations()).length,0)
  })
  await t.test('duplicate workers use the real shared object lock and only one revision commits', async t => {
    const entered = deferred(), release = deferred()
    const f = await fixture(t, { scannerAction: async () => { entered.resolve(); await release.promise } }), object = await f.add()
    const prepared = await f.plan(), first = f.apply(prepared)
    await entered.promise
    let second
    try { second = await f.apply(prepared) } finally { release.resolve() }
    const completed = await first
    assert.equal(second.results[0].code,'STORAGE_OBJECT_BUSY'); assert.equal(second.complete,false)
    assert.equal(completed.complete,true); assert.equal((await f.row(object.id)).scan_state,'clean')
    assert.equal((await f.pool.query('SELECT count(*)::int AS n FROM fallback_storage_scan_attempts')).rows[0].n,1)
    assert.equal((await f.reservations()).length,0)
  })
  await t.test('real terminated admission session prevents publication after scan', async t => {
    const entered = deferred(), release = deferred()
    const f = await fixture(t, { scannerAction: async () => { entered.resolve(); await release.promise } }), object = await f.add()
    const work = f.apply(await f.plan()); await entered.promise
    const connected = await f.leasePool.connect()
    // All owned lease sessions have a dedicated fixture application_name.
    const ended = new Promise(resolve => {
      const active = [...f.leasePool._clients].find(client => client !== connected)
      assert.ok(active); active.once('end',resolve)
    })
    connected.release()
    const victims = (await f.admin.query('SELECT pid FROM pg_stat_activity WHERE application_name=$1 AND state=\'idle\' ORDER BY backend_start LIMIT 1',[f.appName])).rows
    assert.equal(victims.length,1)
    await f.admin.query('SELECT pg_terminate_backend($1)',[victims[0].pid]); await ended
    release.resolve()
    const result = await work
    assert.equal(result.results[0].code,'STORAGE_UPLOAD_RESERVATION_LOST')
    assert.equal((await f.row(object.id)).scan_state,'legacy_unverified')
    assert.equal(await fileCount(path.join(f.root,'.objects')),0)
  })
  await t.test('real successful COMMIT with simulated lost acknowledgment is reconciled by row, journal and bytes', async t => {
    let failCommit = true
    const f = await fixture(t, { poolAction: async (client,sql,values) => {
      const response = await client.query(sql,values)
      if (sql==='COMMIT' && failCommit) { failCommit=false; throw fail('SYNTHETIC_COMMIT_ACK_LOST') }
      return response
    } }), object = await f.add()
    const result = await f.apply(await f.plan())
    assert.equal(result.complete,true); assert.equal(result.results[0].commitAcknowledgmentRecovered,true)
    assert.equal(cleanStorageRow(await f.row(object.id)),true)
    assert.equal((await f.reservations()).length,0); assert.deepEqual(await fs.promises.readFile(object.source),inert)
  })
  await t.test('unconfirmed COMMIT keeps inert revision and persistent byte budget; never asserts clean', async t => {
    let failCommit = true
    const f = await fixture(t, { poolAction: async (client,sql,values) => {
      if (sql==='COMMIT' && failCommit) { failCommit=false; throw fail('SYNTHETIC_BEFORE_COMMIT_FAILURE') }
      return client.query(sql,values)
    } }), object = await f.add()
    const result = await f.apply(await f.plan())
    assert.equal(result.complete,false); assert.equal(result.results[0].state,'uncertain')
    assert.equal((await f.row(object.id)).scan_state,'legacy_unverified')
    const reservations = await f.reservations()
    assert.equal(reservations.length,1); assert.equal(reservations[0].active,false)
    assert.equal(Number(reservations[0].reserved_bytes),inert.length*4)
    assert.equal(reservations[0].expires_at,Infinity)
    assert.equal((await f.admission.reconcileExpired()).released,0)
    assert.equal(await fileCount(path.join(f.root,'.objects')),1)
    assert.deepEqual(await fs.promises.readFile(object.source),inert)
    const events = (await fs.promises.readFile(result.privateJournal,'utf8')).trim().split('\n').map(JSON.parse)
    assert.equal(events[0].planDigest,result.planDigest)
    assert.equal(events.at(-1).state,'uncertain'); assert.equal(events.at(-1).reservation,reservations[0].id)
    assert.ok(events.some(event=>event.stage==='sealed'&&event.proof.revision))
    const recovery = await reconcileHistoricalAvBackfill(f.pool,{root:f.root,privateJournal:result.privateJournal,admission:f.admission,scanner:f.scanner})
    assert.equal(recovery.dryRun,true); assert.equal(recovery.results[0].state,'blocked')
    assert.equal(recovery.results[0].code,'HISTORICAL_AV_MANUAL_RECONCILIATION_REQUIRED')
    assert.equal((await f.reservations()).length,1)
  })
  await t.test('durable journal recovers confirmed COMMIT only after reviewed digest and does not unlink old source', async t => {
    let failCommit = true, failConfirmation = false
    const f = await fixture(t, { poolAction: async (client,sql,values) => {
      if (failConfirmation && sql==='SELECT * FROM fallback_storage_objects WHERE id=$1') throw fail('SYNTHETIC_CONFIRMATION_OUTAGE')
      const response=await client.query(sql,values)
      if (sql==='COMMIT'&&failCommit) {failCommit=false;failConfirmation=true;throw fail('SYNTHETIC_COMMIT_ACK_LOST')}
      return response
    } }), object=await f.add()
    const result=await f.apply(await f.plan())
    assert.equal(result.results[0].state,'uncertain'); assert.equal(cleanStorageRow(await f.row(object.id)),true)
    assert.equal((await f.reservations()).length,1)
    const options={root:f.root,privateJournal:result.privateJournal,admission:f.admission,scanner:f.scanner}
    const planned=await reconcileHistoricalAvBackfill(f.pool,options)
    assert.equal(planned.dryRun,true); assert.equal(planned.results[0].state,'confirmed_clean')
    assert.equal((await f.reservations()).length,1)
    await assert.rejects(reconcileHistoricalAvBackfill(f.pool,{...options,reviewedJournalDigest:'0'.repeat(64)}),{code:'HISTORICAL_AV_JOURNAL_NOT_REVIEWED'})
    const applied=await reconcileHistoricalAvBackfill(f.pool,{...options,reviewedJournalDigest:planned.journalDigest})
    assert.equal(applied.results[0].state,'released_confirmed_clean'); assert.equal((await f.reservations()).length,0)
    assert.deepEqual(await fs.promises.readFile(object.source),inert)
    assert.deepEqual(await fs.promises.readFile((await f.row(object.id)).local_path),inert)
  })
  await t.test('historical claimed MIME cannot approve HTML, APK or executable bytes',async t=>{
    const cases=[{name:'active.html',bytes:Buffer.from('<html><script>inert fixture</script></html>'),type:'image/png',code:'HISTORICAL_AV_ACTIVE_FILE_BLOCKED'},
      {name:'package.apk',bytes:Buffer.from([0x50,0x4b,0x03,0x04,1,2,3,4]),type:'application/octet-stream',code:'HISTORICAL_AV_ACTIVE_FILE_BLOCKED'},
      {name:'fake.png',bytes:Buffer.from('<html>inert fixture</html>'),type:'image/png',code:'UNSUPPORTED_OR_MISMATCHED_MEDIA_TYPE'},
      {name:'fake.txt',bytes:Buffer.from([0x4d,0x5a,1,2,3,4]),type:'text/plain',code:'EXECUTABLE_UPLOAD_BLOCKED'}]
    for(const sample of cases){const f=await fixture(t),object=await f.add(sample);await f.pool.query('UPDATE fallback_storage_objects SET content_type=$2 WHERE id=$1',[object.id,sample.type]);const result=await f.apply(await f.plan());assert.equal(result.results[0].code,sample.code);assert.equal((await f.row(object.id)).scan_state,'legacy_unverified');assert.equal(f.scans(),0);assert.equal((await f.reservations()).length,0);assert.deepEqual(await fs.promises.readFile(object.source),sample.bytes)}
  })
  await t.test('unknown bucket is blocked and messaging byte limit cannot be widened to500MiB',async t=>{
    const f=await fixture(t),object=await f.add();await f.pool.query("UPDATE fallback_storage_objects SET bucket_id='unknown-audit' WHERE id=$1",[object.id]);const unknown=await f.plan();assert.equal(unknown.plan.entries.length,0);assert.equal(unknown.plan.blocked[0].code,'BUCKET_NOT_ALLOWED');assert.equal((await f.row(object.id)).scan_state,'legacy_unverified')
    await f.pool.query("UPDATE fallback_storage_objects SET bucket_id='chat-media',size_bytes=$2 WHERE id=$1",[object.id,60*1024**2+1]);const handle=await fs.promises.open(object.source,'r+');await handle.truncate(60*1024**2+1);await handle.close();const result=await f.apply(await f.plan());assert.equal(result.results[0].code,'UPLOAD_TOO_LARGE');assert.equal((await f.row(object.id)).scan_state,'legacy_unverified');assert.equal(f.scans(),0);assert.equal((await f.reservations()).length,0)
  })
  await t.test('file and directory fsync failures never commit clean and preserve original source',async t=>{
    for(const stage of ['copy','revision','journal']) {
      const f=await fixture(t),object=await f.add(),prepared=await f.plan(),originalOpen=fs.promises.open
      let injected=false,result
      fs.promises.open=async function(file,...args){const handle=await originalOpen.call(this,file,...args)
        const filename=String(file),match=stage==='copy'?path.basename(filename).startsWith('admitted-'):stage==='revision'?filename.endsWith('.blob'):filename.endsWith('.jsonl')
        if(match&&!injected){handle.sync=async()=>{injected=true;throw Object.assign(new Error('Injected fsync failure'),{code:'EIO'})}}
        return handle
      }
      try { if(stage==='journal')await assert.rejects(f.apply(prepared),{code:'EIO'});else result=await f.apply(prepared) }
      finally {fs.promises.open=originalOpen}
      assert.equal(injected,true);assert.equal((await f.row(object.id)).scan_state,'legacy_unverified');assert.deepEqual(await fs.promises.readFile(object.source),inert)
      assert.equal((await f.reservations()).length,0);assert.equal(await fileCount(path.join(f.root,'.objects')),0)
      if(result){assert.equal(result.complete,false);assert.equal(result.results[0].code,'EIO')}
    }
  })
})
