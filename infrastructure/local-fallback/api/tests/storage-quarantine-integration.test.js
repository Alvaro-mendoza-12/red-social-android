'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const os = require('node:os')
const crypto = require('node:crypto')
const Module = require('node:module')
const { Readable } = require('node:stream')
const { createStorageScanCoordinator, digestFile, cleanStorageRow, ensureStorageScanSchema } = require('../src/storage-scan-coordinator')
const actor = '11111111-1111-4111-8111-111111111111'
const harmless = Buffer.from('Inert quarantine integration fixture.')
const sourceFile = path.join(__dirname, '../src/storage-routes.js')
const sourceText = fs.readFileSync(sourceFile, 'utf8')

function response() {
  return { statusCode: 200, headers: {}, ended: false, status(code) { this.statusCode = code; return this },
    json(value) { this.body = value; this.ended = true; return this }, end() { this.ended = true; return this },
    setHeader(key, value) { this.headers[key.toLowerCase()] = value }, getHeader(key) { return this.headers[key.toLowerCase()] }, removeHeader(key) { delete this.headers[key.toLowerCase()] } }
}
function createPool({ failCommit = false, raceDelete = false } = {}) {
  const objects = new Map(), attempts = new Map(), uploads = new Map(), queries = []
  let pending = null
  const pool = { objects, attempts, uploads, queries, async connect() { return { query: pool.query, release() {} } }, async query(sql, values = []) {
    const query = sql.replace(/\s+/g, ' ').trim(); queries.push(query)
    if (query === 'BEGIN') { pending = new Map(objects); return { rows: [] } }
    if (query === 'ROLLBACK') { pending = null; return { rows: [] } }
    if (query === 'COMMIT') { if (failCommit) throw Error('INERT_COMMIT_FAILURE'); if (pending) { objects.clear(); for (const [key, row] of pending) objects.set(key, row) }; pending = null; return { rows: [] } }
    if (query.startsWith('SELECT pg_try_advisory_lock')) return { rows: [{ acquired: true }] }
    if (query.startsWith('SELECT pg_advisory_unlock')) return { rows: [{ acquired: true }] }
    if (query.startsWith('INSERT INTO fallback_storage_scan_attempts')) { attempts.set(values[0], { state: 'scanning', source_sha256: values[4] }); return { rows: [] } }
    if (query.startsWith('UPDATE fallback_storage_scan_attempts')) { const row = attempts.get(values[0]); if (row) row.state = query.includes("state='clean'") ? 'clean' : values[1]; return { rows: [] } }
    if (query.startsWith('SELECT owner_id FROM fallback_storage_objects')) return { rows: [...objects.values()].filter(row => row.bucket_id === values[0] && row.name === values[1]).map(row => ({ owner_id: row.owner_id })) }
    if (query.startsWith('INSERT INTO fallback_storage_objects')) {
      assert.equal(values.length, 16, 'The real INSERT must persist all scan columns, not metadata-only claims')
      assert.match(query, /scan_state, object_revision, scan_sha256/)
      const row = { id: crypto.randomUUID(), bucket_id: values[0], name: values[1], owner_id: values[2], content_type: values[3], size_bytes: values[4], public_url: values[5], local_path: values[6], metadata: values[7],
        scan_state: values[8], object_revision: values[9], scan_sha256: values[10], scan_bytes: values[11], scan_engine: values[12], scan_signature_version: values[13], scan_at: values[14], scan_policy_sha256: values[15] }
      ;(pending || objects).set(row.bucket_id + '/' + row.name, row); return { rows: [row] }
    }
    if (query.startsWith('SELECT * FROM fallback_storage_objects')) return { rows: [...objects.values()].filter(row => row.bucket_id === values[0] && (Array.isArray(values[1]) ? values[1].includes(row.name) : row.name === values[1])) }
    if (query.startsWith('DELETE FROM fallback_storage_objects')) {
      assert.match(query,/local_path = \$3/);assert.match(query,/object_revision IS NOT DISTINCT FROM \$4/)
      if (raceDelete) return {rows:[]}
      const key=values[0]+'/'+values[1], row=objects.get(key)
      if (!row || row.local_path!==values[2] || (row.object_revision||null)!==values[3]) return {rows:[]}
      objects.delete(key);return {rows:[{id:row.id}]}
    }
    if (query.startsWith('INSERT INTO fallback_resumable_uploads')) {
      const row = { id: values[0], owner_id: values[1], bucket_id: values[2], object_name: values[3], content_type: values[4], upload_length: values[5], upload_offset: 0, allow_overwrite: values[7], expires_at: values[8], state: 'uploading' }; uploads.set(row.id, row); return { rows: [] }
    }
    if (query.startsWith('SELECT * FROM fallback_resumable_uploads')) { const row = uploads.get(values[0]); return { rows: row && row.owner_id === values[1] ? [{ ...row }] : [] } }
    if (query.startsWith('UPDATE fallback_resumable_uploads')) {
      const row = uploads.get(values[0]); assert.ok(row)
      if (query.includes('SET upload_offset')) Object.assign(row, { upload_offset: values[1], state: values[2], expires_at: values[3] })
      else if (query.includes("state = 'completed'")) Object.assign(row, { state: 'completed', stored_object_path: values[1] })
      else if (query.includes("state = 'rejected'")) row.state = 'rejected'
      else if (query.includes("state = 'finalizing'")) row.state = 'finalizing'
      else throw Error('UNEXPECTED_UPLOAD_UPDATE')
      return { rows: [] }
    }
    if (query.startsWith('DELETE FROM fallback_resumable_uploads')) return { rows: [] }
    // Reference queries cannot contain any real rows in this fixture.
    if (query.includes('fallback_posts') || query.includes('fallback_compat_rows') || query.includes('fallback_videos') || query.includes('fallback_messages')) return { rows: [] }
    throw Error('UNEXPECTED_QUERY: ' + query.slice(0, 150))
  } }
  return pool
}

async function fixture(mode = 'clean', options = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'q-'))
  process.env.NODE_ENV = 'test'; process.env.STORAGE_ROOT = root; process.env.MAX_UPLOAD_BYTES = '128'
  process.env.STORAGE_SCAN_ENFORCE = options.environmentEnforced === false ? 'false' : 'true'; process.env.STORAGE_ACCEL_PREFIX = ''; process.env.PUBLIC_BASE_URL = 'https://audit.invalid'
  const loaded = new Module(sourceFile, module); loaded.filename = sourceFile; loaded.paths = Module._nodeModulePaths(path.dirname(sourceFile)); loaded._compile(sourceText, sourceFile)
  const pool = createPool(options); let scans = 0, openGate
  const paused = new Promise(resolve => { openGate = resolve })
  const scanner = { maxBytes: options.scanMaxBytes ?? 128, async readiness() { if (mode === 'unavailable') throw Object.assign(Error('STORAGE_SCAN_UNAVAILABLE'), { status: 503, code: 'STORAGE_SCAN_UNAVAILABLE' }) }, async scan(file, { expectedSize }) {
    scans += 1
    if (mode === 'paused') await paused
    if (mode === 'rejected') throw Object.assign(Error('STORAGE_FILE_REJECTED'), { status: 415, code: 'STORAGE_FILE_REJECTED' })
    const measured = await digestFile(file); assert.equal(measured.bytes, expectedSize)
    if (mode === 'tampered') await fs.promises.appendFile(file, 'changed')
    return { verdict: 'clean', sha256: mode === 'bad-hash' ? '0'.repeat(64) : measured.sha256, bytesScanned: measured.bytes,
      engine: 'INERT_TEST_ADAPTER', signatureVersion: 'synthetic', scannedAt: new Date().toISOString(), policySha256: 'a'.repeat(64) }
  } }
  const coordinator = createStorageScanCoordinator({ root, enforced: options.enforced ?? true, scanner })
  const routes = [], app = Object.fromEntries(['get','post','put','delete','head','patch','options'].map(method => [method, (matcher, ...handlers) => routes.push({ method, matcher, handlers })]))
  loaded.exports.registerStorageRoutes(app, { pool, storageScanCoordinator: coordinator, storageFinalizationConnection: () => pool.connect(), storageUploadAdmission: { async reserve({ id = crypto.randomUUID(), ownerId }) { return { id, assertAlive() {}, async finish() {} } }, async claimTus(id) { return { id, assertAlive() {}, async finish() {} } } }, authRequired(req, res, next) { req.auth = { sub: actor }; next() } })
  await fs.promises.mkdir(path.join(root, '.tmp'), { recursive: true })
  return { root, pool, routes, storage: loaded.exports, coordinator, openGate, scans: () => scans,
    cleanup() { assert.ok(root.startsWith(path.resolve(os.tmpdir()) + path.sep + 'q-')); fs.rmSync(root, { recursive: true, force: true }) } }
}
async function invoke(f, method, url, body = Buffer.alloc(0), headers = {}) {
  const route = f.routes.find(candidate => candidate.method === method && (typeof candidate.matcher === 'string' ? candidate.matcher === url : candidate.matcher.test(url)))
  assert.ok(route, method + ' ' + url)
  const match = typeof route.matcher === 'string' ? null : route.matcher.exec(url)
  const req = Readable.from([body]); Object.assign(req, { method: method.toUpperCase(), url, params: match ? Object.fromEntries(match.slice(1).map((value, index) => [index, value])) : {}, query: {},
    headers: { 'content-length': String(body.length), ...headers }, protocol: 'https', get: () => 'audit.invalid' })
  const res = response(); let caught
  for (const handler of route.handlers.slice(0, -1)) await new Promise((resolve, reject) => handler(req, res, cause => cause ? reject(cause) : resolve()))
  await route.handlers.at(-1)(req, res, cause => { caught = cause })
  if (caught) { res.statusCode = caught.status || 500; res.body = { error: caught.message } }
  return res
}
function multipart() {
  const boundary = 'inert-quarantine-fixture'
  return { body: Buffer.concat([Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="bucket"\r\n\r\nchat-media\r\n--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="safe.txt"\r\nContent-Type: text/plain\r\n\r\n`), harmless, Buffer.from(`\r\n--${boundary}--\r\n`)]), headers: { 'content-type': `multipart/form-data; boundary=${boundary}` } }
}

test('schema initialization serializes and commits the same idempotent DDL on repeat, even off', async () => {
  const statements = []; let released = 0
  const pool = { async connect() { return { async query(sql) { statements.push(sql); return {rows:[]} }, release() { released++ } } } }
  await ensureStorageScanSchema(pool); await ensureStorageScanSchema(pool)
  assert.equal(released,2); assert.equal(statements.filter(sql=>sql==='COMMIT').length,2)
  const ddl = statements.filter(sql=>sql.includes('ALTER TABLE fallback_storage_objects'))
  assert.equal(ddl.length,2); assert.equal(ddl[0],ddl[1]); assert.match(ddl[0],/IF NOT EXISTS \(SELECT 1 FROM pg_constraint/)
  assert.match(ddl[0],/conrelid='fallback_storage_objects'::regclass/)
})
test('schema initialization rolls back and fails explicitly if DDL fails', async () => {
  const statements = [];let released=false
  const pool={async connect(){return{async query(sql){statements.push(sql);if(sql.includes('ALTER TABLE'))throw Error('INERT_DDL_FAILURE');return{rows:[]}},release(){released=true}}}}
  await assert.rejects(ensureStorageScanSchema(pool),/STORAGE_SCAN_SCHEMA_UNAVAILABLE/)
  assert.ok(statements.includes('ROLLBACK'));assert.ok(!statements.includes('COMMIT'));assert.equal(released,true)
})
test('a prepared schema requires no ALTER permission for a DML-only runtime',async()=>{
  const statements=[];const pool={async connect(){return{async query(sql){statements.push(sql);if(sql.includes('ALTER TABLE'))throw Error('DDL_MUST_NOT_BE_NEEDED');return{rows:sql.includes('AS ready')?[{ready:true}]:[]}},release(){}}}}
  await ensureStorageScanSchema(pool)
  assert.ok(statements.includes('COMMIT'));assert.ok(!statements.some(sql=>sql.includes('ALTER TABLE')))
})
test('clean row proof rejects null, zero-size and blank engine/signatures',()=>{
  const valid={scan_state:'clean',object_revision:crypto.randomUUID(),scan_sha256:'a'.repeat(64),scan_bytes:1,size_bytes:1,scan_engine:'fixture',scan_signature_version:'fixture',scan_at:new Date().toISOString(),scan_policy_sha256:'b'.repeat(64)}
  assert.equal(cleanStorageRow(valid),true)
  for(const malformed of [{scan_bytes:null,size_bytes:0},{scan_bytes:0,size_bytes:0},{scan_sha256:null},{scan_policy_sha256:null},{scan_engine:' '},{scan_signature_version:''}]) assert.equal(Boolean(cleanStorageRow({...valid,...malformed})),false)
})
test('revoked immutable revision remains resolvable for owner deletion',async()=>{
  const f=await fixture()
  try{
    assert.equal((await invoke(f,'put','/storage/v1/object/chat-media/safe.txt',harmless,{'content-type':'text/plain'})).statusCode,200)
    const row=[...f.pool.objects.values()][0];row.scan_state='revoked'
    assert.equal(f.storage.storedObjectFilePath(row,row.bucket_id,row.name),row.local_path)
    assert.equal((await invoke(f,'delete','/v1/storage/objects/chat-media/'+actor+'/safe.txt')).statusCode,200)
    assert.equal(f.pool.objects.size,0);assert.equal(fs.existsSync(row.local_path),false)
  }finally{f.cleanup()}
})
test('owner delete detects replacement and never unlinks the prior revision on CAS failure',async()=>{
  const f=await fixture('clean',{raceDelete:true})
  try{
    assert.equal((await invoke(f,'put','/storage/v1/object/chat-media/safe.txt',harmless,{'content-type':'text/plain'})).statusCode,200)
    const row=[...f.pool.objects.values()][0]
    const result=await invoke(f,'delete','/v1/storage/objects/chat-media/'+actor+'/safe.txt')
    assert.equal(result.statusCode,409);assert.equal(result.body.error,'STORAGE_OBJECT_CHANGED')
    assert.equal(f.pool.objects.size,1);assert.equal(fs.existsSync(row.local_path),true)
  }finally{f.cleanup()}
})
test('route-injected off coordinator consistently controls public reads despite enabled environment',async()=>{
  const f=await fixture('clean',{enforced:false})
  try{
    const legacyPath=f.storage.objectFilePath('chat-media',actor+'/legacy.txt');fs.mkdirSync(path.dirname(legacyPath),{recursive:true});fs.writeFileSync(legacyPath,harmless)
    f.pool.objects.set('chat-media/'+actor+'/legacy.txt',{bucket_id:'chat-media',name:actor+'/legacy.txt',owner_id:actor,local_path:legacyPath,content_type:'text/plain',size_bytes:harmless.length,scan_state:'legacy_unverified'})
    // A conditional request avoids creating a fake response writable stream.
    const result=await invoke(f,'get','/storage/v1/object/public/chat-media/'+actor+'/legacy.txt',Buffer.alloc(0),{'if-none-match':'*'})
    assert.notEqual(result.statusCode,404)
  }finally{f.cleanup()}
})
test('publication and owner deletion acquire the same canonical advisory lock',async()=>{
  const f=await fixture()
  try{
    const keys=[],query=f.pool.query;f.pool.query=async(sql,values)=>{if(sql.includes('pg_try_advisory_lock'))keys.push(values[0]);return query(sql,values)}
    assert.equal((await invoke(f,'put','/storage/v1/object/chat-media/safe.txt',harmless,{'content-type':'text/plain'})).statusCode,200)
    assert.equal((await invoke(f,'delete','/v1/storage/objects/chat-media/'+actor+'/safe.txt')).statusCode,200)
    assert.deepEqual(keys,['chat-media/'+actor+'/safe.txt','chat-media/'+actor+'/safe.txt'])
  }finally{f.cleanup()}
})
test('route-injected on coordinator gates reads and stream limits even when global environment is off',async()=>{
  const f=await fixture('clean',{environmentEnforced:false,scanMaxBytes:8})
  try{
    const legacyPath=f.storage.objectFilePath('chat-media',actor+'/legacy.txt');fs.mkdirSync(path.dirname(legacyPath),{recursive:true});fs.writeFileSync(legacyPath,harmless)
    f.pool.objects.set('chat-media/'+actor+'/legacy.txt',{bucket_id:'chat-media',name:actor+'/legacy.txt',owner_id:actor,local_path:legacyPath,content_type:'text/plain',size_bytes:harmless.length,scan_state:'legacy_unverified'})
    assert.equal((await invoke(f,'get','/storage/v1/object/public/chat-media/'+actor+'/legacy.txt')).statusCode,404)
    assert.equal((await invoke(f,'put','/storage/v1/object/chat-media/safe.txt',harmless,{'content-type':'text/plain'})).statusCode,413)
    assert.equal(f.scans(),0)
  }finally{f.cleanup()}
})

test('real multipart/POST/PUT callbacks persist server-owned clean proof and preserve success contracts', async t => {
  for (const kind of ['multipart', 'post', 'put']) await t.test(kind, async () => {
    const f = await fixture()
    try {
      const form = multipart()
      const result = kind === 'multipart' ? await invoke(f, 'post', '/v1/storage/upload', form.body, form.headers) : await invoke(f, kind, '/storage/v1/object/chat-media/safe.txt', harmless, { 'content-type': 'text/plain' })
      assert.equal(result.statusCode, kind === 'multipart' ? 201 : 200, JSON.stringify(result.body))
      assert.equal(f.scans(), 1)
      const row = [...f.pool.objects.values()][0]; assert.ok(cleanStorageRow(row)); assert.equal(fs.readFileSync(row.local_path).equals(harmless), true)
      assert.match(row.local_path, /\.objects[\\/]/); assert.equal(row.scan_sha256, crypto.createHash('sha256').update(harmless).digest('hex'))
      assert.equal(fs.readdirSync(path.join(f.root, '.tmp')).length, 0); assert.equal(fs.readdirSync(path.join(f.root, '.quarantine')).length, 0)
      assert.ok(f.pool.queries.includes('COMMIT')); assert.equal([...f.pool.attempts.values()][0].state, 'clean')
    } finally { f.cleanup() }
  })
})
test('no public URL or committed storage row before clean result', async () => {
  const f = await fixture('paused')
  try {
    const running = invoke(f, 'put', '/storage/v1/object/chat-media/safe.txt', harmless, { 'content-type': 'text/plain' })
    while (f.scans() === 0) await new Promise(resolve => setImmediate(resolve))
    assert.equal(f.pool.objects.size, 0)
    const read = await invoke(f, 'get', `/storage/v1/object/public/chat-media/${actor}/safe.txt`)
    assert.equal(read.statusCode, 404)
    f.openGate(); assert.equal((await running).statusCode, 200)
  } finally { f.openGate(); f.cleanup() }
})
test('scanner errors, wrong hash, byte changes and failed commit never publish', async t => {
  for (const [mode, status, options] of [['unavailable',503,{}], ['rejected',415,{}], ['bad-hash',503,{}], ['tampered',503,{}], ['clean',500,{ failCommit:true }]]) await t.test(mode + (options.failCommit ? '-commit' : ''), async () => {
    const f = await fixture(mode, options)
    try {
      const result = await invoke(f, 'put', '/storage/v1/object/chat-media/safe.txt', harmless, { 'content-type': 'text/plain' })
      assert.equal(result.statusCode, status); assert.equal(f.pool.objects.size, 0)
      if (fs.existsSync(path.join(f.root, '.quarantine'))) assert.equal(fs.readdirSync(path.join(f.root, '.quarantine')).length, 0)
    } finally { f.cleanup() }
  })
})
test('legacy and rejected rows cannot be read through public/alias GET, variants or conditional headers', async t => {
  const f = await fixture()
  try {
    const legacyPath = f.storage.objectFilePath('chat-media', actor + '/legacy.txt'); fs.mkdirSync(path.dirname(legacyPath), {recursive:true}); fs.writeFileSync(legacyPath, harmless)
    f.pool.objects.set('chat-media/' + actor + '/legacy.txt', {bucket_id:'chat-media',name:actor+'/legacy.txt',owner_id:actor,local_path:legacyPath,content_type:'text/plain',size_bytes:harmless.length,scan_state:'legacy_unverified'})
    for (const prefix of ['public/', '']) await t.test(prefix || 'alias', async () => {
      const result = await invoke(f, 'get', `/storage/v1/object/${prefix}chat-media/${actor}/legacy.txt`, Buffer.alloc(0), { range:'bytes=0-1', 'if-none-match':'*' })
      assert.equal(result.statusCode, 404); assert.equal(result.headers['x-accel-redirect'], undefined)
    })
  } finally { f.cleanup() }
})
test('real TUS creation/chunk/finalization gives completed only after synchronous clean scan', async t => {
  for (const [mode,status,state] of [['clean',204,'completed'],['rejected',415,'rejected'],['unavailable',503,'finalizing']]) await t.test(mode, async () => {
    const f = await fixture(mode)
    try {
      const metadata = Object.entries({bucketName:'chat-media',objectName:'safe.txt',contentType:'text/plain'}).map(([key,value])=>key+' '+Buffer.from(value).toString('base64')).join(',')
      const created = await invoke(f, 'post', '/storage/v1/upload/resumable', Buffer.alloc(0), { 'tus-resumable':'1.0.0','upload-length':String(harmless.length),'upload-metadata':metadata })
      assert.equal(created.statusCode,201)
      const uploaded = await invoke(f,'patch',created.headers.location,harmless,{'tus-resumable':'1.0.0','upload-offset':'0','content-type':'application/offset+octet-stream'})
      assert.equal(uploaded.statusCode,status, JSON.stringify(uploaded.body)); assert.equal([...f.pool.uploads.values()][0].state,state)
      assert.equal(f.pool.objects.size, mode === 'clean' ? 1 : 0)
      if (mode === 'clean') { assert.equal(uploaded.headers['upload-state'],'completed'); assert.match(uploaded.headers['upload-object-path'], /safe\.txt$/) }
    } finally { f.cleanup() }
  })
})

test('schema initializer never reconnects or releases a borrowed pg client',async()=>{
  const statements=[];let released=0,connected=0
  const client={release(){released++},async connect(){connected++;throw Error('CLIENT_ALREADY_CONNECTED')},async query(sql){statements.push(sql);return{rows:sql.includes('AS ready')?[{ready:true}]:[]}}}
  await ensureStorageScanSchema(client)
  assert.equal(connected,0);assert.equal(released,0);assert.ok(statements.includes('BEGIN'));assert.ok(statements.includes('COMMIT'))
})
test('borrowed startup client is retained on DDL failure and only its helper transaction rolls back',async()=>{
  const statements=[];let released=0,connected=0
  const client={release(){released++},async connect(){connected++;throw Error('CLIENT_ALREADY_CONNECTED')},async query(sql){statements.push(sql);if(sql.includes('ALTER TABLE'))throw Error('INERT_DDL_FAILURE');return{rows:[]}}}
  await assert.rejects(ensureStorageScanSchema(client),/STORAGE_SCAN_SCHEMA_UNAVAILABLE/)
  assert.equal(connected,0);assert.equal(released,0);assert.ok(statements.includes('ROLLBACK'));assert.ok(!statements.includes('COMMIT'))
})
test('caller-owned transaction is never begun committed or rolled back by scan initializer',async()=>{
  for(const fail of [false,true]){
    const statements=[]
    const client={release(){throw Error('LEASE_IS_BORROWED')},async query(sql){statements.push(sql);if(fail&&sql.includes('ALTER TABLE'))throw Error('INERT_DDL_FAILURE');return{rows:sql.includes('AS ready')&&!fail?[{ready:true}]:[]}}}
    if(fail)await assert.rejects(ensureStorageScanSchema(client,{callerTransaction:true}),/STORAGE_SCAN_SCHEMA_UNAVAILABLE/)
    else await ensureStorageScanSchema(client,{callerTransaction:true})
    assert.ok(!statements.some(sql=>['BEGIN','COMMIT','ROLLBACK'].includes(sql)))
  }
})
