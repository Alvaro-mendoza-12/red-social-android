'use strict'
const test=require('node:test')
const assert=require('node:assert/strict')
const { anonymousPairBlocked, validateAnonymousMedia }=require('../src/proximity-routes')
const user={id:'11111111-1111-4111-8111-111111111111'}
const peer='22222222-2222-4222-8222-222222222222'
const importedPeer='33333333-3333-4333-8333-333333333333'
const session='44444444-4444-4444-8444-444444444444'
const identity={ids:new Set([user.id]),values:[user.id]}
const url=objectPath=>'https://media.example.invalid/storage/v1/object/public/chat-media/'+objectPath
function db(rows){return {async query(sql,params){assert.ok(sql.includes('fallback_storage_objects'));assert.equal(params[1],user.id);return {rows}}}}
for(const reverse of [false,true])test(`block matches canonical and imported peer identity, reverse=${reverse}`,async()=>{
 const pool={async query(sql){
  if(sql.includes('fallback_user_blocks'))return {rows:[{blocker_id:reverse?importedPeer:user.id,blocked_id:reverse?user.id:importedPeer}]}
  assert.ok(sql.includes('fallback_users'));return {rows:[{id:peer,supabase_user_id:importedPeer}]}
 }}
 assert.equal(await anonymousPairBlocked(pool,identity,peer),true)
})
test('a different blocked user does not block this pair',async()=>{
 const pool={async query(sql){return {rows:sql.includes('fallback_user_blocks')?[{blocker_id:user.id,blocked_id:'another-id'}]:[{id:peer,supabase_user_id:importedPeer}]}}}
 assert.equal(await anonymousPairBlocked(pool,identity,peer),false)
})
test('text may not carry an attachment URL',async()=>{
 assert.equal(await validateAnonymousMedia(null,user,identity,session,'text',null,'text'),true)
 assert.equal(await validateAnonymousMedia(null,user,identity,session,'text','https://untrusted.example.invalid','text'),false)
})
test('sticker text fallback remains available without a URL',async()=>{
 assert.equal(await validateAnonymousMedia(null,user,identity,session,'sticker',null,'🙂'),true)
 assert.equal(await validateAnonymousMedia(null,user,identity,session,'sticker',null,''),false)
})
test('arbitrary external URLs are rejected without querying storage',async()=>{
 assert.equal(await validateAnonymousMedia(null,user,identity,session,'audio','https://untrusted.example.invalid/audio.mp3'),false)
})
for(const [type,mime] of [['audio','audio/wav'],['image','image/jpeg']]){
 test(`${type} is bound to owner and active conversation path`,async()=>{
  const name=`${user.id}/anonymous/${session}/safe`
  assert.equal(await validateAnonymousMedia(db([{name,content_type:mime}]),user,identity,session,type,url(name)),true)
 })
 test(`${type} from another conversation is rejected`,async()=>{
  const name=`${user.id}/anonymous/another-session/safe`
  assert.equal(await validateAnonymousMedia(db([{name,content_type:mime}]),user,identity,session,type,url(name)),false)
 })
 test(`${type} MIME must match the registered object`,async()=>{
  const name=`${user.id}/anonymous/${session}/wrong.pdf`
  assert.equal(await validateAnonymousMedia(db([{name,content_type:'application/pdf'}]),user,identity,session,type,url(name)),false)
 })
}
test('a missing owned object is rejected',async()=>{
 const name=`${user.id}/anonymous/${session}/missing.wav`
 assert.equal(await validateAnonymousMedia(db([]),user,identity,session,'audio',url(name)),false)
})
