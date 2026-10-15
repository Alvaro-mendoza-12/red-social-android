'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {loadCatalogReferences,catalogReferenceAllows,matchesObject}=require('../src/storage-catalog-read');
const origin='https://api.example.invalid',object={bucket_id:'stickers',name:'creator/hello.webp',owner_id:'creator'};
const url=origin+'/storage/v1/object/public/stickers/creator/hello.webp';
const item={id:'item',pack_id:'pack',media_url:url};
const pack={id:'pack',owner_id:'creator',is_public:true};
function fixture(change={}) {
 const state={legacyItems:[],legacyPacks:[],items:[item],packs:[pack],blocked:false,suspended:false,removed:false,profile:{},...change};
 const db={async query(sql,p=[]) {
  if(sql.includes('to_regclass'))return{rows:[{relation:p[0]==='supabase_import.sticker_pack_items'?state.legacyItems.length>0:p[0]==='supabase_import.sticker_packs'?state.legacyPacks.length>0:false}]};
  if(sql.includes('FROM fallback_compat_rows')||sql.includes('FROM supabase_import.')) {
   const imported=sql.includes('FROM supabase_import.');
   const table=imported?(sql.includes('sticker_pack_items')?'sticker_pack_items':'sticker_packs'):p.at(-1);
   let values=table==='sticker_pack_items'?(imported?state.legacyItems:state.items):(imported?state.legacyPacks:state.packs);
   values=values.filter(row=>sql.includes('split_part')?p[0].includes((row.media_url||row.url||'').split('?')[0]):p[0].includes(row.id.toLowerCase()));
   return{rows:values.slice(0,65).map(row=>({row}))};
  }
  if(sql.includes('FROM fallback_users u'))return{rows:state.missingCreator?[]:[{id:'creator',supabase_user_id:'old-creator',profile:state.profile,override_user_id:'creator'}]};
  if(sql.includes('fallback_deleted_identities'))return{rows:state.removed?[{}]:[]};
  if(sql.includes('fallback_profile_moderation'))return{rows:state.suspended?[{is_suspended:true}]:[]};
  if(sql.includes('fallback_user_blocks'))return{rows:state.blocked?[{}]:[]};
  if(sql.includes('fallback_follows'))return{rows:[]};
  throw new Error('Unexpected SQL '+sql);
 }};return{state,db};
}
test('catalog identity requires trusted HTTPS origin and exact bucket/path',()=>{
 assert.equal(matchesObject(item,object,[origin]),true);
 for(const media_url of [url.replace('api.example.invalid','evil.invalid'),url.replace('stickers/creator','chat-media/creator'),url.replace('hello.webp','other.webp'),url+'#fragment'])assert.equal(matchesObject({...item,media_url},object,[origin]),false);
});
test('current catalog loads explicit relationship beyond the upload preview TTL',async()=>{
 const f=fixture();const loaded=await loadCatalogReferences(f.db,object,[origin]);assert.equal(loaded.known,true);assert.equal(loaded.references.length,1);
 assert.equal(await catalogReferenceAllows(f.db,loaded.references[0],{...object,created_at:'2000-01-01'},[origin],['viewer'],Date.now()),true);
});
test('private pack admits its uploader and denies another account',async()=>{
 const f=fixture({packs:[{...pack,is_public:false}]});const ref=(await loadCatalogReferences(f.db,object,[origin])).references[0];
 assert.equal(await catalogReferenceAllows(f.db,ref,object,[origin],['creator'],Date.now()),true);
 assert.equal(await catalogReferenceAllows(f.db,ref,object,[origin],['viewer'],Date.now()),false);
});
test('a catalog URL cannot publish another uploader object',async()=>{
 const f=fixture();assert.equal(await catalogReferenceAllows(f.db,{item,pack},{...object,owner_id:'victim'},[origin],['creator'],Date.now()),false);
});
for(const [name,change]of [['blocked',{blocked:true}],['suspended',{suspended:true}],['deleted-creator',{removed:true}],['missing-creator',{missingCreator:true}],['private-profile',{profile:{is_private:true}}]])test(name+' creator is not publicized through catalog',async()=>{
 const f=fixture(change);assert.equal(await catalogReferenceAllows(f.db,{item,pack},object,[origin],['viewer'],Date.now()),false);
});
test('deleted item, deleted pack and missing pack remain denied',async()=>{
 const f=fixture();for(const ref of [{item:{...item,is_deleted:true},pack},{item,pack:{...pack,deleted_at:'2026-01-01'}},{item,pack:null}])assert.equal(await catalogReferenceAllows(f.db,ref,object,[origin],['creator'],Date.now()),false);
});
test('a local URL replacement overrides the imported item even when it no longer matches',async()=>{
 const f=fixture({legacyItems:[item],items:[{...item,media_url:url.replace('hello','changed')}]});const result=await loadCatalogReferences(f.db,object,[origin]);assert.equal(result.known,true);assert.deepEqual(result.references,[]);
});
test('current private pack override beats an imported public pack',async()=>{
 const f=fixture({legacyPacks:[pack],packs:[{...pack,is_public:false}]});const ref=(await loadCatalogReferences(f.db,object,[origin])).references[0];assert.equal(await catalogReferenceAllows(f.db,ref,object,[origin],['viewer'],Date.now()),false);
});
test('retired imported item remains retired with its current tombstone',async()=>{
 const f=fixture({legacyItems:[item],items:[{...item,is_deleted:true}]});const ref=(await loadCatalogReferences(f.db,object,[origin])).references[0];assert.equal(await catalogReferenceAllows(f.db,ref,object,[origin],['creator'],Date.now()),false);
});
test('per-object reference overflow is bounded and fails closed',async()=>{
 const f=fixture({items:Array.from({length:65},(_,i)=>({...item,id:'item-'+i}))});await assert.rejects(loadCatalogReferences(f.db,object,[origin]),{status:503,code:'STORAGE_REFERENCE_LIMIT'});
});

const musicObject={bucket_id:'community-music',name:'creator/song.m4a',owner_id:'creator'};
const musicUrl=origin+'/storage/v1/object/public/community-music/creator/song.m4a';
const musicTrack={id:'track',owner_id:'creator',audio_url:musicUrl,moderation_state:'APPROVED',visibility:'PUBLIC_REUSABLE'};
function musicFixture(change={}) {
  const f=fixture(change);const query=f.db.query;
  f.db.query=async(sql,p=[])=>sql.includes('FROM fallback_music_tracks')?{rows:[change.track||musicTrack]}:query(sql,p);
  return f;
}
test('approved community audio survives owner upload preview expiry',async()=>{
 const f=musicFixture();const loaded=await loadCatalogReferences(f.db,musicObject,[origin]);
 assert.equal(loaded.known,true);assert.equal(loaded.references.length,1);
 assert.equal(await catalogReferenceAllows(f.db,loaded.references[0],{...musicObject,created_at:'2000-01-01'},[origin],['viewer'],Date.now()),true);
});
test('private community track admits owner but not another viewer',async()=>{
 const f=musicFixture({track:{...musicTrack,visibility:'PRIVATE'}});const ref=(await loadCatalogReferences(f.db,musicObject,[origin])).references[0];
 assert.equal(await catalogReferenceAllows(f.db,ref,musicObject,[origin],['creator'],Date.now()),true);
 assert.equal(await catalogReferenceAllows(f.db,ref,musicObject,[origin],['viewer'],Date.now()),false);
});
for(const [name,change] of [['retired',{track:{...musicTrack,removed_at:'2026-01-01'}}],['disabled',{track:{...musicTrack,disabled_at:'2026-01-01'}}],['rejected',{track:{...musicTrack,moderation_state:'REJECTED'}}],['pending',{track:{...musicTrack,moderation_state:'PENDING'}}],['blocked',{blocked:true}],['suspended',{suspended:true}],['private-creator',{profile:{is_private:true}}]])test(name+' community audio remains denied to another user',async()=>{
 const f=musicFixture(change);const ref=(await loadCatalogReferences(f.db,musicObject,[origin])).references[0];
 assert.equal(await catalogReferenceAllows(f.db,ref,musicObject,[origin],['viewer'],Date.now()),false);
});
test('community catalog cannot publish an audio uploaded by another account',async()=>{
 const f=musicFixture();const ref=(await loadCatalogReferences(f.db,musicObject,[origin])).references[0];
 assert.equal(await catalogReferenceAllows(f.db,ref,{...musicObject,owner_id:'victim'},[origin],['creator'],Date.now()),false);
});
test('community catalog enforces trusted origin exact object and bounded reference count',async()=>{
 const f=musicFixture({track:{...musicTrack,audio_url:musicUrl.replace('api.example.invalid','evil.invalid')}});
 assert.deepEqual((await loadCatalogReferences(f.db,musicObject,[origin])).references,[]);
 f.db.query=async()=>({rows:Array.from({length:65},()=>musicTrack)});
 await assert.rejects(loadCatalogReferences(f.db,musicObject,[origin]),{code:'STORAGE_REFERENCE_LIMIT'});
});

test('approved owned community artwork in thumbnails has the same durable music authorization',async()=>{
 const cover={bucket_id:'thumbnails',name:'creator/music-cover.jpg',owner_id:'creator'};
 const f=musicFixture({track:{...musicTrack,cover_url:origin+'/storage/v1/object/public/thumbnails/'+cover.name},items:[],packs:[]});
 const ref=(await loadCatalogReferences(f.db,cover,[origin])).references[0];
 assert.equal(ref.kind,'music');assert.equal(await catalogReferenceAllows(f.db,ref,cover,[origin],['viewer'],Date.now()),true);
 assert.equal(await catalogReferenceAllows(f.db,ref,{...cover,owner_id:'victim'},[origin],['viewer'],Date.now()),false);
});
for(const state of ['PROCESSING','PENDING_REVIEW','CHANGES_REQUIRED'])test(state+' audio is available only to its owner before approval',async()=>{
 const f=musicFixture({track:{...musicTrack,moderation_state:state}});
 const ref=(await loadCatalogReferences(f.db,musicObject,[origin])).references[0];
 assert.equal(await catalogReferenceAllows(f.db,ref,musicObject,[origin],['creator'],Date.now()),true);
 assert.equal(await catalogReferenceAllows(f.db,ref,musicObject,[origin],['viewer'],Date.now()),false);
});
