import { useSyncExternalStore } from 'react'
import type { GenericRow } from './utils'

export type InboxScope = 'chat' | 'group' | 'community'
export type InboxCounts = { chats: number; groups: number; communities: number; archivedChats: number }
export const inboxActivityEvent = 'novvin:inbox-activity'
export type InboxActivity = { accountId: string; scope: InboxScope; id: string; action: 'read' | 'message' | 'changed'; message?: GenericRow }
type UnreadItem = { id: string; unreadCount: number; updatedAt: string; isArchived?: boolean; unreadMessageIds?: string[] }
type Snapshot = { requestedAt: number; items: Map<string, UnreadItem> }
type AccountState = {
  counts: InboxCounts
  unread: Map<InboxScope, Set<string>>
  snapshots: Map<InboxScope, Snapshot>
  archived: Set<string>
  read: Map<string, { through: number; pending: boolean; confirmedAt: number; token: number; messageIds?: string[] }>
  seen: Map<string, number>
  latest: Map<string, GenericRow>
  remoteReads: Map<string, Set<string>>
  createdMessages: Set<string>
  listeners: Set<() => void>
}
const empty: InboxCounts = { chats: 0, groups: 0, communities: 0, archivedChats: 0 }
let readToken = 0
const states = new Map<string, AccountState>()
const tab = (scope: InboxScope) => scope === 'chat' ? 'chats' : scope === 'group' ? 'groups' : 'communities'
const cleanId = (id: string) => id.trim().toLowerCase()
const key = (scope: InboxScope, id: string) => scope + ':' + cleanId(id)
const millis = (value: unknown) => Date.parse(String(value || '')) || 0

function state(accountId: string) {
  let value = states.get(accountId)
  if (!value) {
    value = { counts: empty, unread: new Map(), snapshots: new Map(), archived: new Set(), read: new Map(), seen: new Map(), latest: new Map(), remoteReads: new Map(), createdMessages: new Set(), listeners: new Set() }
    try {
      const saved=JSON.parse(localStorage.getItem(`novvin:read-cursors:${accountId}`)||'[]')
      if(Array.isArray(saved))for(const entry of saved.slice(-500))if(Array.isArray(entry)&&/^(chat|group|community):[\w-]{1,160}$/.test(entry[0])&&Number.isFinite(entry[1])&&entry[1]>Date.now()-30*86400000&&entry[1]<=Date.now()+86400000){
        value.read.set(entry[0],{through:entry[1],pending:false,confirmedAt:0,token:0})
        if(Array.isArray(entry[2]))value.remoteReads.set(entry[0],new Set(entry[2].filter((id:unknown):id is string=>typeof id==='string'&&/^[\w:-]{1,200}$/.test(id)).slice(-100).map((id:string)=>id.toLowerCase())))
      }
    }catch{/* Storage may be unavailable; authoritative reads still work. */}
    states.set(accountId, value)
  }
  return value
}

function persistReadCursors(accountId:string){
  const current=state(accountId)
  let budget=4000
  const entries=Array.from(current.read).filter(([,value])=>!value.pending&&value.through>0).sort((a,b)=>b[1].through-a[1].through).slice(0,500).map(([id,value])=>{
    // Keep recent confirmed incoming IDs across reloads. An outgoing activity can
    // advance updatedAt without changing which incoming messages were read.
    const ids=budget>0?Array.from(current.remoteReads.get(id)||[]).slice(-Math.min(100,budget)):[];budget-=ids.length
    return ids.length?[id,value.through,ids]:[id,value.through]
  }).reverse()
  try{localStorage.setItem(`novvin:read-cursors:${accountId}`,JSON.stringify(entries))}catch{/* Optional cache. */}
}

function publish(accountId: string, scope: InboxScope) {
  const s = state(accountId), snapshot = s.snapshots.get(scope)
  if (snapshot) {
    const unread = new Set<string>(), archived = new Set<string>()
    for (const item of snapshot.items.values()) {
      if (inboxUnreadCount(accountId, scope, item, snapshot.requestedAt) <= 0) continue
      if (scope === 'chat' && item.isArchived) archived.add(item.id)
      else unread.add(item.id)
    }
    s.unread.set(scope, unread)
    if (scope === 'chat') s.archived = archived
  }
  // A row can change from one to two messages while navigation stays at one chat.
  s.counts = { ...s.counts, [tab(scope)]: s.unread.get(scope)?.size || 0, archivedChats: s.archived.size }
  s.listeners.forEach(fn => fn())
}

function emit(detail: InboxActivity) { window.dispatchEvent(new CustomEvent(inboxActivityEvent, { detail })) }
export function useInboxUnreadCounts(accountId: string) {
  return useSyncExternalStore(fn => { const listeners = state(accountId).listeners; listeners.add(fn); return () => { listeners.delete(fn) } }, () => state(accountId).counts, () => empty)
}

/** Tab title counts messages; navigation continues to count conversations. */
export function useInboxUnreadMessageTotal(accountId: string) {
  return useSyncExternalStore(fn => { const listeners = state(accountId).listeners; listeners.add(fn); return () => { listeners.delete(fn) } }, () => {
    let total = 0
    for (const [scope, snapshot] of state(accountId).snapshots) for (const item of snapshot.items.values()) {
      if (scope === 'chat' && item.isArchived) continue
      total += inboxUnreadCount(accountId, scope, item, snapshot.requestedAt)
    }
    return total
  }, () => 0)
}

export function inboxUnreadCount(accountId: string, scope: InboxScope, item: UnreadItem, requestedAt = Infinity) {
  const read = state(accountId).read.get(key(scope, item.id))
  if (read && (millis(item.updatedAt) <= read.through || (!read.pending && read.confirmedAt > 0 && requestedAt <= read.confirmedAt))) return 0
  const count = Number(item.unreadCount)
  const ids = state(accountId).remoteReads.get(key(scope,item.id))
  const readCount = ids && item.unreadMessageIds ? new Set(item.unreadMessageIds.filter(id=>ids.has(id.toLowerCase()))).size : 0
  return Number.isFinite(count) ? Math.max(0, Math.floor(count) - readCount) : 0
}

export function applyRemoteConversationRead(accountId: string, scope: InboxScope, id: string, messageIds: string[], through?: string) {
  const s=state(accountId), k=key(scope,id), at=millis(through)
  if(at){const old=s.read.get(k);s.read.set(k,{through:Math.max(old?.through||0,at),pending:false,confirmedAt:old?.confirmedAt||0,token:++readToken})}
  const ids=s.remoteReads.get(k)||new Set<string>()
  for(const messageId of messageIds)ids.add(messageId.toLowerCase())
  while(ids.size>1000)ids.delete(ids.values().next().value!)
  s.remoteReads.set(k,ids)
  if(at)persistReadCursors(accountId)
  publish(accountId,scope)
  emit({accountId,scope,id,action:'changed'})
}

/** Rows, filters and navigation project the same accepted snapshot. */
export function currentInboxUnreadCount(accountId: string, scope: InboxScope, item: UnreadItem) {
  const snapshot = state(accountId).snapshots.get(scope)
  if (!snapshot) return inboxUnreadCount(accountId, scope, item)
  const current = snapshot.items.get(cleanId(item.id))
  return current ? inboxUnreadCount(accountId, scope, current, snapshot.requestedAt) : 0
}

export function hasUnrepresentedInboxUnread(accountId: string, scope: InboxScope, ids: string[]) {
  const s = state(accountId), listed = new Set(ids.map(cleanId))
  return [...(s.unread.get(scope) || []), ...(scope === 'chat' ? s.archived : [])].some(id => !listed.has(id))
}

export function applyInboxUnreadSnapshot(accountId: string, scope: InboxScope, items: UnreadItem[], requestedAt = Infinity) {
  const s = state(accountId), startedAt = Number.isFinite(requestedAt) ? requestedAt : Date.now()
  const previous = s.snapshots.get(scope)
  // Shell and list loads can finish in the opposite order from their requests.
  if (previous && startedAt < previous.requestedAt) return false
  const byId = new Map<string, UnreadItem>()
  for (const item of items) {
    const id = cleanId(item.id)
    if (!id) continue
    const old = byId.get(id)
    if (!old || millis(item.updatedAt) >= millis(old.updatedAt)) byId.set(id, { ...item, id })
    const k = key(scope, id)
    s.seen.set(k, Math.max(s.seen.get(k) || 0, millis(item.updatedAt)))
  }
  s.snapshots.set(scope, { requestedAt: startedAt, items: byId })
  publish(accountId, scope)
  return true
}

export function markInboxConversationRead(accountId: string, scope: InboxScope, id: string, through?: string) {
  if (!accountId || !id) return
  const s = state(accountId), k = key(scope, id), old = s.read.get(k)
  const at = Math.max(s.seen.get(k) || 0, millis(through))
  const changed = !old || Boolean(s.unread.get(scope)?.has(cleanId(id))) || (scope === 'chat' && s.archived.has(cleanId(id))) || at > (old?.through || 0)
  const token = ++readToken
  const messageIds = s.snapshots.get(scope)?.items.get(cleanId(id))?.unreadMessageIds || []
  s.read.set(k, { through: Math.max(old?.through || 0, at), pending: true, confirmedAt: old?.confirmedAt || 0, token, messageIds })
  s.unread.get(scope)?.delete(cleanId(id)); if (scope === 'chat') s.archived.delete(cleanId(id))
  publish(accountId, scope)
  if (changed) emit({ accountId, scope, id, action: 'read' })
  return token
}

export function confirmInboxConversationRead(accountId: string, scope: InboxScope, id: string, token: number | undefined, through?:string) {
  const read = state(accountId).read.get(key(scope, id))
  if (read && read.token === token) {
    read.pending = false; read.confirmedAt = Date.now(); read.through = Math.max(read.through, millis(through))
    // The latest activity may later become our own sticker. Keep confirmed incoming
    // IDs read even when that outgoing timestamp advances beyond the old cursor.
    const s = state(accountId), k = key(scope, id), ids = s.remoteReads.get(k) || new Set<string>()
    for (const messageId of read.messageIds || []) ids.add(messageId.toLowerCase())
    while (ids.size > 1000) ids.delete(ids.values().next().value!)
    s.remoteReads.set(k, ids)
    persistReadCursors(accountId); publish(accountId, scope)
  }
}

export function notifyInboxMessage(accountId: string, scope: InboxScope, id: string, message: GenericRow, created = false) {
  if (!accountId || !id) return
  const s = state(accountId), k = key(scope, id), old = s.latest.get(k), time = millis(message.created_at || message.createdAt)
  if (created) {
    const messageId = cleanId(String(message.id || '')), sender = cleanId(String(message.sender_id || message.senderId || ''))
    const eventKey = `${k}:${messageId}`, snapshot = s.snapshots.get(scope), item = snapshot?.items.get(cleanId(id))
    // Only creation events for known conversations can add unread. Updates,
    // outgoing sends and replayed events must never fabricate another message.
    if (messageId && sender && sender !== cleanId(accountId) && time && item && snapshot &&
      !s.createdMessages.has(eventKey) && !item.unreadMessageIds?.some(value => cleanId(value) === messageId) &&
      time > millis(item.updatedAt) && !message.deleted_for_all && !message.deletedForAll && !message.read_at && !message.readAt &&
      !['pending', 'failed', 'read', 'seen'].includes(String(message.status || '').toLowerCase())) {
      s.createdMessages.add(eventKey)
      while (s.createdMessages.size > 4096) s.createdMessages.delete(s.createdMessages.values().next().value!)
      const count = inboxUnreadCount(accountId, scope, item, snapshot.requestedAt)
      const readIds = s.remoteReads.get(k)
      const ids = count > 0 ? (item.unreadMessageIds || []).filter(value => !readIds?.has(cleanId(value))) : []
      snapshot.items.set(cleanId(id), { ...item, unreadCount: count + 1, updatedAt: String(message.created_at || message.createdAt), unreadMessageIds: [...ids, messageId] })
      // An HTTP response already in flight predates this event. Keep the local
      // projection until the next request, which still reconciles with the server.
      snapshot.requestedAt = Math.max(snapshot.requestedAt, Date.now() + 1)
      publish(accountId, scope)
    }
  }
  if (old && millis(old.created_at || old.createdAt) > time) return
  if (message.status !== 'pending') s.seen.set(k, Math.max(s.seen.get(k) || 0, time))
  s.latest.set(k, message); emit({ accountId, scope, id, action: 'message', message })
}
export function latestInboxMessage(accountId: string, scope: InboxScope, id: string) { return state(accountId).latest.get(key(scope, id)) }
export function invalidateInboxPreview(accountId: string, scope: InboxScope, id: string) { state(accountId).latest.delete(key(scope, id)); emit({ accountId, scope, id, action: 'changed' }) }
export function acknowledgeInboxPreview(accountId: string, scope: InboxScope, id: string, updatedAt: string) {
  const s = state(accountId), k = key(scope, id), latest = s.latest.get(k)
  if (latest && millis(updatedAt) >= millis(latest.created_at || latest.createdAt) && latest.status !== 'pending') s.latest.delete(k)
}
export function inboxMessageTarget(value: unknown): { scope: InboxScope; id: string } | null {
  const id = String(value || '').trim(), match = /^room:(group|community):(.+)$/.exec(id)
  return match ? { scope: match[1] as InboxScope, id: match[2] } : id ? { scope: 'chat', id } : null
}

