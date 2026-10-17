import {useSyncExternalStore} from 'react'
import type {GenericRow} from './utils'

export type InboxScope='chat'|'group'|'community'
export type InboxCounts={chats:number;groups:number;communities:number}
export const inboxActivityEvent='novvin:inbox-activity'
export type InboxActivity={accountId:string;scope:InboxScope;id:string;action:'read'|'message'|'changed';message?:GenericRow}
const empty:InboxCounts={chats:0,groups:0,communities:0}
let readToken=0
const states=new Map<string,{counts:InboxCounts;unread:Map<string,Set<string>>;read:Map<string,{through:number;pending:boolean;confirmedAt:number;token:number}>;seen:Map<string,number>;latest:Map<string,GenericRow>;listeners:Set<()=>void>}>()
const tab=(scope:InboxScope)=>scope==='chat'?'chats':scope==='group'?'groups':'communities'
const key=(scope:InboxScope,id:string)=>scope+':'+id
const millis=(value:unknown)=>Date.parse(String(value||''))||0
function state(accountId:string){let value=states.get(accountId);if(!value){value={counts:empty,unread:new Map(),read:new Map(),seen:new Map(),latest:new Map(),listeners:new Set()};states.set(accountId,value)}return value}
function publish(accountId:string,scope:InboxScope){const s=state(accountId),name=tab(scope),count=s.unread.get(scope)?.size||0;if(s.counts[name]===count)return;s.counts={...s.counts,[name]:count};s.listeners.forEach(fn=>fn())}
function emit(detail:InboxActivity){window.dispatchEvent(new CustomEvent(inboxActivityEvent,{detail}))}
export function useInboxUnreadCounts(accountId:string){return useSyncExternalStore(fn=>{const listeners=state(accountId).listeners;listeners.add(fn);return()=>{listeners.delete(fn)}},()=>state(accountId).counts,()=>empty)}
export function inboxUnreadCount(accountId:string,scope:InboxScope,item:{id:string;unreadCount:number;updatedAt:string},requestedAt=Infinity){
  const read=state(accountId).read.get(key(scope,item.id))
  return read&&(read.pending||requestedAt<read.confirmedAt||millis(item.updatedAt)<=read.through)?0:Math.max(0,Number(item.unreadCount)||0)
}
export function applyInboxUnreadSnapshot(accountId:string,scope:InboxScope,items:Array<{id:string;unreadCount:number;updatedAt:string}>,requestedAt=Infinity){
  const s=state(accountId)
  items.forEach(item=>{const k=key(scope,item.id);s.seen.set(k,Math.max(s.seen.get(k)||0,millis(item.updatedAt)))})
  s.unread.set(scope,new Set(items.filter(item=>inboxUnreadCount(accountId,scope,item,requestedAt)>0).map(item=>item.id)));publish(accountId,scope)
}
export function markInboxConversationRead(accountId:string,scope:InboxScope,id:string,through?:string){
  if(!accountId||!id)return
  const s=state(accountId),k=key(scope,id),old=s.read.get(k),at=Math.max(s.seen.get(k)||0,millis(through)),changed=!old||Boolean(s.unread.get(scope)?.has(id))||at>(old?.through||0),token=++readToken
  s.read.set(k,{through:Math.max(old?.through||0,at),pending:true,confirmedAt:old?.confirmedAt||0,token});s.unread.get(scope)?.delete(id);publish(accountId,scope)
  if(changed)emit({accountId,scope,id,action:'read'})
  return token
}
export function confirmInboxConversationRead(accountId:string,scope:InboxScope,id:string,token:number|undefined){const read=state(accountId).read.get(key(scope,id));if(read&&read.token===token){read.pending=false;read.confirmedAt=Date.now()}}
export function notifyInboxMessage(accountId:string,scope:InboxScope,id:string,message:GenericRow){
  if(!accountId||!id)return
  const s=state(accountId),k=key(scope,id),old=s.latest.get(k),time=millis(message.created_at||message.createdAt)
  if(old&&millis(old.created_at||old.createdAt)>time)return
  if(message.status!=='pending')s.seen.set(k,Math.max(s.seen.get(k)||0,time))
  s.latest.set(k,message);emit({accountId,scope,id,action:'message',message})
}
export function latestInboxMessage(accountId:string,scope:InboxScope,id:string){return state(accountId).latest.get(key(scope,id))}
export function invalidateInboxPreview(accountId:string,scope:InboxScope,id:string){state(accountId).latest.delete(key(scope,id));emit({accountId,scope,id,action:'changed'})}
export function acknowledgeInboxPreview(accountId:string,scope:InboxScope,id:string,updatedAt:string){const s=state(accountId),k=key(scope,id),latest=s.latest.get(k);if(latest&&millis(updatedAt)>=millis(latest.created_at||latest.createdAt)&&latest.status!=='pending')s.latest.delete(k)}
export function inboxMessageTarget(value:unknown):{scope:InboxScope;id:string}|null{
  const id=String(value||'').trim(),match=/^room:(group|community):(.+)$/.exec(id)
  return match?{scope:match[1] as InboxScope,id:match[2]}:id?{scope:'chat',id}:null
}
