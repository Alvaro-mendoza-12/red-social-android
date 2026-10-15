'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const crypto = require('node:crypto')
process.env.STORAGE_SCAN_ENFORCE='true';process.env.PUBLIC_BASE_URL='https://audit.invalid';process.env.STORAGE_MEDIA_ORIGINS='https://audit.invalid';process.env.NODE_ENV='test'
const { trustedStorageUrl, scanEnforcementEnabled } = require('../src/storage-media-reference')
const { cleanStorageRow } = require('../src/storage-scan-coordinator')
const { currentUserOwnsStorageObject, currentUserMayReferenceMessageMedia, parsePublicStorageObjectUrl } = require('../src/data-routes')
const actor='11111111-1111-4111-8111-111111111111'
const good=`https://audit.invalid/storage/v1/object/public/chat-media/${actor}/inert.txt`
const ready={scan_state:'clean',object_revision:crypto.randomUUID(),scan_sha256:'a'.repeat(64),scan_bytes:10,size_bytes:10,scan_engine:'INERT',scan_signature_version:'test',scan_at:new Date().toISOString(),scan_policy_sha256:'b'.repeat(64)}
test('exact origin and unambiguous path are required by actual parser',()=>{
  assert.ok(trustedStorageUrl(good));assert.ok(parsePublicStorageObjectUrl(good,['chat-media']))
  for(const bad of [good.replace('audit.invalid','external.invalid'),good.replace('audit.invalid','audit.invalid:444'),good.replace('audit.invalid','account@audit.invalid'),good.replace('/storage/','/prefix/storage/'),good+'#fragment',good.replace('inert.txt','nested%2Ffile.txt')]){assert.equal(trustedStorageUrl(bad),false,bad);assert.equal(parsePublicStorageObjectUrl(bad,['chat-media']),null,bad)}
})
test('actual ownership validator requires SQL clean proof in addition to owner',async()=>{
  const pool={async query(sql){assert.match(sql,/scan_state = 'clean'/);assert.match(sql,/scan_sha256 ~/);assert.match(sql,/owner_id = \$2/);return{rows:[]}}}
  assert.equal(await currentUserOwnsStorageObject(pool,actor,good,['chat-media']),false)
})
test('actual media reference validator checks clean before owner, catalog or forwarding branches',async t=>{
  for(const isForwarded of [false,true])await t.test(String(isForwarded),async()=>{
    let calls=0;const pool={async query(sql){calls++;assert.match(sql,/SELECT \* FROM fallback_storage_objects/);return{rows:[{...ready,scan_state:'legacy_unverified'}]}}}
    assert.equal(await currentUserMayReferenceMessageMedia(pool,{id:actor},{ids:new Set([actor])},{mediaUrl:good,contentType:'sticker',allowedOwnedBuckets:['chat-media'],isForwarded,forwardedFromMessageId:crypto.randomUUID()}),false)
    assert.equal(calls,1)
  })
})
test('ready row validator rejects changed size, malformed revision and missing policy proof',()=>{
  assert.ok(cleanStorageRow(ready))
  for(const override of [{scan_bytes:11},{object_revision:'-'.repeat(36)},{scan_policy_sha256:'claimed-clean'},{scan_at:'invalid'},{scan_state:'scanning'}])assert.equal(Boolean(cleanStorageRow({...ready,...override})),false)
})
test('a misspelled enforcement flag never disables scanning silently',()=>{
  process.env.STORAGE_SCAN_ENFORCE='tru'
  try{assert.throws(scanEnforcementEnabled,/MODE_INVALID/)}finally{process.env.STORAGE_SCAN_ENFORCE='true'}
})
