import { fallbackApiJson, readFallbackSession } from './auth/fallbackAuth'
import { textOf, type GenericRow } from './utils'
export type ProfileVisit={id:string;name:string;avatar:string;time:string}
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
export async function loadOwnProfileVisits(profileId:string):Promise<{enabled:boolean;items:ProfileVisit[]}>{
  const account=readFallbackSession()?.user.id
  if(!account)throw new Error('AUTH_REQUIRED')
  const init={expectedAccountId:account}
  const own=await fallbackApiJson<{profile:GenericRow}>('/v1/data/profile/me',init)
  if(own.profile.profile_view_history_enabled===false)return {enabled:false,items:[]}
  const rows=await fallbackApiJson<GenericRow[]>(`/rest/v1/profile_views?profile_id=eq.${encodeURIComponent(profileId)}&select=viewer_id,viewed_at&order=viewed_at.desc&limit=50`,init)
  const ids=[...new Set(rows.map(row=>textOf(row,['viewer_id'],'')).filter(id=>uuid.test(id)))].slice(0,50)
  if(!ids.length)return {enabled:true,items:[]}
  const profiles=await fallbackApiJson<GenericRow[]>(`/rest/v1/profiles?id=in.(${ids.join(',')})&select=id,display_name,full_name,avatar_url`,init)
  const byId=new Map(profiles.map(row=>[textOf(row,['id'],''),row]))
  return {enabled:true,items:ids.map(id=>({id,name:textOf(byId.get(id)||{},['display_name','full_name'],'Usuario'),avatar:textOf(byId.get(id)||{},['avatar_url'],''),time:textOf(rows.find(row=>row.viewer_id===id)||{},['viewed_at'],'')}))}
}
export async function recordWebProfileVisit(profileId:string,targetEnabled:boolean){
  const session=readFallbackSession(),viewer=session?.user.supabaseUserId||session?.user.id
  if(!session||!viewer||viewer===profileId||!uuid.test(profileId)||!targetEnabled)return
  const own=await fallbackApiJson<{profile:GenericRow}>('/v1/data/profile/me',{expectedAccountId:session.user.id})
  if(own.profile.profile_view_history_enabled===false)return
  await fallbackApiJson('/rest/v1/profile_views?on_conflict=profile_id,viewer_id',{method:'POST',expectedAccountId:session.user.id,headers:{Prefer:'resolution=merge-duplicates,return=minimal'},body:JSON.stringify({profile_id:profileId,viewer_id:viewer,viewed_at:new Date().toISOString()})})
}
