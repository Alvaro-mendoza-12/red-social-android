'use strict'
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const os = require('node:os')
const path = require('node:path')
const crypto = require('node:crypto')
const { configuration, identity, openSource, replicate, safeError } = require('../src/storage-r2-mirror')
const { revisionFilePath } = require('../src/storage-scan-coordinator')

function proof(bytes = Buffer.from('verified media')) {
  return { id: crypto.randomUUID(), bucket_id: 'chat-media', name: 'owner/photo.jpg', object_revision: crypto.randomUUID(),
    scan_state: 'clean', scan_sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
    scan_bytes: bytes.length, size_bytes: bytes.length, content_type: 'image/jpeg', scan_engine: 'fixture',
    scan_signature_version: 'fixture-1', scan_at: new Date().toISOString(), scan_policy_sha256: 'a'.repeat(64) }
}
async function fixture(t, row = proof(), bytes = Buffer.from('verified media')) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'novvin-r2-'))
  t.after(() => fs.rm(root, { recursive: true, force: true }))
  const file = revisionFilePath(root, row.object_revision)
  await fs.mkdir(path.dirname(file), { recursive: true }); await fs.writeFile(file, bytes)
  row.local_path = file
  return { root, row, file }
}
test('disabled configuration needs no cloud account and never enables itself', () => {
  assert.deepEqual(configuration({}), { enabled: false })
  assert.throws(() => configuration({ R2_MIRROR_ENABLED: 'yes' }), /INVALID/)
})
test('enabled configuration restricts endpoint and requires explicit credentials and local root', () => {
  const env = { R2_MIRROR_ENABLED: 'true', R2_ENDPOINT: `https://${'a'.repeat(32)}.r2.cloudflarestorage.com`,
    R2_BUCKET: 'novvin-media', R2_ACCESS_KEY_ID: 'test', R2_SECRET_ACCESS_KEY: 'test', STORAGE_ROOT: path.resolve(os.tmpdir()) }
  assert.equal(configuration(env).maxBytes, 9_000_000_000)
  for (const endpoint of ['http://example.com','https://example.com','https://user@'+ 'a'.repeat(32)+'.r2.cloudflarestorage.com','https://'+ 'a'.repeat(32)+'.r2.cloudflarestorage.com/other']) {
    assert.throws(() => configuration({ ...env, R2_ENDPOINT: endpoint }))
  }
  assert.throws(() => configuration({ ...env, R2_SECRET_ACCESS_KEY: '' }), /CREDENTIALS_REQUIRED/)
  assert.throws(() => configuration({ ...env, R2_MIRROR_MAX_BYTES: '-1' }), /INTEGER_INVALID/)
})
test('remote identity changes with revision, hash, logical path or MIME type and contains no filename', () => {
  const row = proof(), original = identity(row)
  for (const delta of [{object_revision:crypto.randomUUID()}, {name:'other.jpg'}, {content_type:'image/png'}]) {
    assert.notEqual(identity({...row,...delta}).key, original.key)
  }
  assert.equal(original.key.includes('owner'), false)
  for (const scan_state of ['legacy_unverified','scanning','rejected','scan_failed']) assert.throws(() => identity({...row,scan_state}), /NOT_CLEAN/)
})
test('real source read verifies scan proof and retains local bytes', async t => {
  const {root,row,file}=await fixture(t)
  const handle=await openSource(root,row); await handle.close()
  assert.equal((await fs.readFile(file)).toString(),'verified media')
  await fs.writeFile(file,'corrupt media!')
  await assert.rejects(openSource(root,row), /HASH_MISMATCH|SIZE_MISMATCH/)
  await assert.rejects(openSource(root,{...row,local_path:path.resolve(root,'elsewhere')}), /PATH_MISMATCH/)
})
test('all current registry buckets participate, including private attachments and report evidence', async t => {
  const { allowedBuckets } = require('../src/storage-routes')
  for (const bucket_id of allowedBuckets) {
    const { root,row }=await fixture(t,{...proof(),bucket_id})
    const actions=[]
    const repository={reserve:async()=>true,current:async()=>row,markReady:async()=>actions.push('ready'),retry:async()=>assert.fail('unexpected retry')}
    const remote={putVerified:async()=>actions.push('uploaded')}
    assert.deepEqual(await replicate({config:{root,maxBytes:1000},repository,remote,row}),{state:'ready'})
    assert.deepEqual(actions,['uploaded','ready'])
  }
})
test('budget denial creates no cloud write and leaves local file intact', async t => {
  const {root,row,file}=await fixture(t)
  const result=await replicate({config:{root,maxBytes:1},row,repository:{reserve:async()=>false},remote:{putVerified:()=>assert.fail('cloud write')}})
  assert.deepEqual(result,{state:'budget-paused'}); assert.equal((await fs.stat(file)).size,Number(row.size_bytes))
})
test('network failure persists retry without completing the replica or deleting local file', async t => {
  const {root,row,file}=await fixture(t);let retry
  const result=await replicate({config:{root,maxBytes:1000},row,repository:{reserve:async()=>true,retry:async(...args)=>{retry=args},markReady:()=>assert.fail()},
    remote:{putVerified:async()=>{throw Error('secret endpoint with credentials')}}})
  assert.equal(result.state,'retry'); assert.equal(retry[1],'R2_REMOTE_OR_DATABASE_FAILURE');assert.ok(await fs.stat(file))
})
test('deletion or replacement during upload never becomes a ready current replica', async t => {
  const {root,row}=await fixture(t)
  for(const current of [null,{...row,object_revision:crypto.randomUUID()},{...row,scan_state:'rejected'}]) {
    let obsolete=false
    const result=await replicate({config:{root,maxBytes:1000},row,repository:{reserve:async()=>true,current:async()=>current,
      markDeleting:async()=>{obsolete=true},markReady:()=>assert.fail()},remote:{putVerified:async()=>{}}})
    assert.equal(result.state,'obsolete');assert.equal(obsolete,true)
  }
})
test('errors never expose request signatures or database connection strings',()=>{
  assert.equal(safeError({message:'postgres://password',code:'23505'}),'R2_REMOTE_OR_DATABASE_FAILURE')
  assert.equal(safeError({$metadata:{httpStatusCode:403}}),'R2_HTTP_403')
})
module.exports={proof,fixture}
