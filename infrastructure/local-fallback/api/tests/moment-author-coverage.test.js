'use strict'
const test=require('node:test'),assert=require('node:assert/strict')
const {buildMomentsFeed,requestedMomentOwners}=require('../src/moments-routes')
const owner='11111111-1111-4111-8111-111111111111',viewer='22222222-2222-4222-8222-222222222222'
const moment=(id,extra={})=>({id,user_id:owner,media_url:'https://example.invalid/test.webp',media_type:'image',visibility:'all',visibility_type:'public',created_at:new Date(),expires_at:new Date(Date.now()+3600000),...extra})
function poolFor(local,imported=[],views=[]){return {async query(sql,args=[]){
 if(sql.includes('to_regclass'))return {rows:[{oid:args[0]==='supabase_import.moments'?'moments':null}]}
 if(sql.includes('FROM supabase_import.moments'))return {rows:imported.map(row=>({row}))}
 if(sql.includes('FROM fallback_moments'))return {rows:sql.includes('ANY($1::text[])')?local:[]}
 if(sql.includes('FROM fallback_moment_views'))return {rows:views}
 return {rows:[]}
}}}
test('owner selection is bounded, UUID only, unique and rejects malformed requests',()=>{
 assert.deepEqual(requestedMomentOwners(),[]);assert.deepEqual(requestedMomentOwners(owner+','+owner.toUpperCase()),[owner])
 for(const input of ['x',owner+',', [owner],Array(101).fill(owner).join(',')])assert.equal(requestedMomentOwners(input),null)
})
test('author local moments are included beyond the discovery window without friendship',async()=>{
 const feed=await buildMomentsFeed(poolFor([moment('local')]),{ids:new Set([viewer])},[owner])
 assert.equal(feed.length,1);assert.equal(feed[0].id,'local')
})
test('author imported moments are included beyond the imported discovery window',async()=>{
 const feed=await buildMomentsFeed(poolFor([],[moment('imported')]),{ids:new Set([viewer])},[owner])
 assert.equal(feed.length,1);assert.equal(feed[0].id,'imported')
})
test('author supplementation preserves privacy, exclusions and expiry',async()=>{
 const feed=await buildMomentsFeed(poolFor([moment('private',{visibility_type:'private'}),moment('followers',{visibility:'followers'}),moment('expired',{expires_at:new Date(0)}),moment('excluded',{visibility:'except_selected',excluded_ids:[viewer]})]),{ids:new Set([viewer])},[owner])
 assert.deepEqual(feed,[])
})
test('author supplementation reports seen state and local revisions override imported duplicates',async()=>{
 const feed=await buildMomentsFeed(poolFor([moment('same',{caption:'current'})],[moment('same',{caption:'old'})],[{moment_id:'same',user_id:viewer}]),{ids:new Set([viewer])},[owner])
 assert.equal(feed.length,1);assert.equal(feed[0].caption,'current');assert.equal(feed[0].viewed_by_me,true)
})
