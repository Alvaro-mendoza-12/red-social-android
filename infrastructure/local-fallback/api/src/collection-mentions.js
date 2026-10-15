'use strict'
// Match the public handle shown by Android, without exposing mail addresses.
function normalizeHandle(value) {
 return String(value||'').trim().replace(/^@/,'').normalize('NFD').replace(/(\p{Script=Latin})\p{M}+/gu,'$1')
  .normalize('NFC').toLowerCase().replace(/[^\p{L}\p{N}\p{M}_.-]+/gu,'_').replace(/^[_\-.]+|[_\-.]+$/g,'').slice(0,24).replace(/^[_\-.]+|[_\-.]+$/g,'')
}
function profileHandle(p) {
 const username=String(p.username||'').trim().replace(/^@/,'')
 const email=String(p.email||'').trim()
 if(/^[A-Za-z0-9_.-]{3,24}$/.test(username) &&
  (['chosen','migrated_name'].includes(p.username_origin) || (email && username.toLowerCase()!==email.split('@')[0].toLowerCase())))return username.toLowerCase()
 const name=[p.display_name,p.full_name].find(x=>String(x||'').trim()&&!String(x).includes('@'))
 return normalizeHandle(name)||'usuario'
}
function mentionHandles(content) {
 return [...new Set([...String(content||'').matchAll(/(^|[^\p{L}\p{N}_.-])@([\p{L}\p{N}](?:[\p{L}\p{N}\p{M}_.-]{0,22}[\p{L}\p{N}\p{M}])?)(?![\p{L}\p{N}\p{M}]|[_.-]+[\p{L}\p{N}\p{M}])/gu)].map(m=>normalizeHandle(m[2])))].slice(0,8)
}
function collectionMentionRecipients({content,profiles,memberIds,actorIds=[]}) {
 const memberSet=new Set(memberIds.map(id=>String(id).trim().toLowerCase()))
 const actorSet=new Set(actorIds.map(id=>String(id).trim().toLowerCase()))
 const byHandle=new Map()
 for(const profile of profiles) {
  const id=String(profile.id||'').trim().toLowerCase()
  if(!id||!memberSet.has(id))continue
  const handle=profileHandle(profile)
  if(!byHandle.has(handle))byHandle.set(handle,new Set())
  byHandle.get(handle).add(id)
 }
 return mentionHandles(content).flatMap(handle=>{
  const ids=byHandle.get(handle)
  if(ids?.size!==1)return []
  const [id]=ids;return actorSet.has(id)?[]:[id]
 })
}
module.exports={normalizeHandle,profileHandle,mentionHandles,collectionMentionRecipients}
