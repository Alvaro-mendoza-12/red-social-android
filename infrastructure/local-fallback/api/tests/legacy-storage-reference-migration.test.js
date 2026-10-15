'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path'),test=require('node:test');
const {migrationPolicy,canonicalReference,canonicalOwner,transformSourceRow,applyLegacyReferenceMigration}=require('../src/legacy-storage-reference-migration');
const owner='11111111-1111-4111-8111-111111111111',legacy='22222222-2222-4222-8222-222222222222',foreign='33333333-3333-4333-8333-333333333333';
const old='https://historic-a.example.invalid',canonical='https://current.example.invalid';
const root=fs.mkdtempSync(path.join(os.tmpdir(),'legacy-storage-reference-'));
const policy=migrationPolicy({canonicalBase:canonical,historicalOrigins:[old],evidenceDigest:'a'.repeat(64)});
const object={bucket_id:'chat-media',name:owner+'/file.png',owner_id:owner,public_url:old+'/storage/v1/object/public/chat-media/'+owner+'/file.png',size_bytes:3};
const users=[{id:owner,supabase_user_id:legacy},{id:foreign}];
const context={policy,ownerId:owner,users,registry:[object],storageRoot:root};
fs.mkdirSync(path.join(root,'chat-media',owner),{recursive:true});fs.writeFileSync(path.join(root,'chat-media',object.name),'abc');
test.after(()=>fs.rmSync(root,{recursive:true,force:true}));
test('exact reviewed origin plus registry owner path and physical size permits canonical reference',()=>{
 const result=canonicalReference(object.public_url,context);assert.equal(result.changed,true);assert.equal(result.value,object.public_url.replace(old,canonical));assert.equal(result.proof.physicalBytes,3);
});
test('unreviewed origin wildcard and credential origin never acquire admission',()=>{
 assert.equal(canonicalReference(object.public_url.replace(old,'https://historic-b.example.invalid'),context).blocked,'UNREVIEWED_ORIGIN');
 assert.throws(()=>migrationPolicy({canonicalBase:canonical,historicalOrigins:['https://*.example.invalid'],evidenceDigest:'a'.repeat(64)}));
 assert.throws(()=>migrationPolicy({canonicalBase:canonical,historicalOrigins:['https://user:pass@historic-a.example.invalid'],evidenceDigest:'a'.repeat(64)}));
});
test('registry absence duplicate registry owner mismatch and unrelated register URL remain unchanged',()=>{
 for(const [ctx,reason] of [[{...context,registry:[]},'NO_REGISTRY_OBJECT'],[{...context,registry:[object,object]},'AMBIGUOUS_REGISTRY_OBJECT'],
  [{...context,ownerId:foreign},'OWNER_PROVENANCE_MISMATCH'],[{...context,registry:[{...object,public_url:object.public_url+'/other'}]},'REGISTRY_REFERENCE_PROVENANCE_MISMATCH']]){
  const result=canonicalReference(object.public_url,ctx);assert.equal(result.changed,false);assert.equal(result.value,object.public_url);assert.equal(result.blocked,reason);
 }
});
test('unique imported identity alias resolves while ambiguous aliases are rejected',()=>{
 assert.equal(canonicalOwner(legacy,users),owner);assert.equal(canonicalOwner(legacy,[...users,{id:foreign,supabase_user_id:legacy}]),null);
 assert.equal(canonicalReference(object.public_url,{...context,ownerId:legacy}).changed,true);
});
test('path traversal double encoding malformed signed URL and fragment remain blocked',()=>{
 for(const raw of [old+'/storage/v1/object/public/chat-media/%2e%2e/file.png',object.public_url.replace('file.png','%252e%252e'),object.public_url+'#fragment',object.public_url.replace('file.png','%2Fetc')])assert.equal(canonicalReference(raw,context).changed,false);
});
test('physical missing file and registry size mismatch never produce changed reference',()=>{
 assert.equal(canonicalReference(object.public_url,{...context,registry:[{...object,size_bytes:4}]}).blocked,'LEGACY_MIGRATION_SIZE_MISMATCH');
 assert.equal(canonicalReference(object.public_url,{...context,storageRoot:path.join(root,'missing')}).changed,false);
});
test('physical symlink is denied',()=>{
 if(process.platform==='win32')return;
 const link=path.join(root,'chat-media',owner,'link.png');fs.symlinkSync(path.join(root,'chat-media',object.name),link);
 const linked={...object,name:owner+'/link.png',public_url:object.public_url.replace('file.png','link.png')};
 assert.equal(canonicalReference(linked.public_url,{...context,registry:[linked]}).blocked,'LEGACY_MIGRATION_SYMLINK_DENIED');
});
test('only known media fields are transformed while audience privacy text and nested music are preserved',()=>{
 const row={id:owner,user_id:owner,visibility:'followers',content:object.public_url,media_urls:JSON.stringify([object.public_url,'https://outside.example/photo.png']),audience_ids:[foreign]};
 const result=transformSourceRow('fallback_posts',row,context);assert.equal(result.proofs.length,1);assert.deepEqual(Object.keys(result.changed),['media_urls']);
 assert.equal(JSON.parse(result.changed.media_urls)[0],object.public_url.replace(old,canonical));assert.equal(row.visibility,'followers');assert.equal(row.content,object.public_url);
 const nested=transformSourceRow('fallback_profile_overrides',{user_id:owner,profile:{favoriteMusicUrl:object.public_url,music_visibility:'followers',about:object.public_url}},context);
 assert.equal(nested.changed.profile.favoriteMusicUrl,object.public_url.replace(old,canonical));assert.equal(nested.changed.profile.music_visibility,'followers');assert.equal(nested.changed.profile.about,object.public_url);
});
test('apply requires matching reviewed digest before any database query',async()=>{
 let calls=0;const db={async query(){calls++;throw Error('must not run');}};
 await assert.rejects(applyLegacyReferenceMigration(db,{version:1,digest:'a'.repeat(64),plans:[],policy},{reviewedPlanDigest:'b'.repeat(64)}),e=>e.code==='LEGACY_MIGRATION_REVIEWED_PLAN_REQUIRED');assert.equal(calls,0);
});
