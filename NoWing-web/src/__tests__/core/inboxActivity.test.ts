import {renderHook,act} from '@testing-library/react'
import {applyRemoteConversationRead,applyInboxUnreadSnapshot,confirmInboxConversationRead,currentInboxUnreadCount,hasUnrepresentedInboxUnread,inboxUnreadCount,markInboxConversationRead,useInboxUnreadCounts,useInboxUnreadMessageTotal,notifyInboxMessage,latestInboxMessage,inboxMessageTarget} from '../../core/inboxActivity'

test('a created incoming message updates row, navigation and title counts before the follow-up HTTP response',()=>{
 const account='realtime-immediate',item={id:'chat',unreadCount:0,updatedAt:'2026-10-29T18:00:00Z'}
 const {result}=renderHook(()=>({nav:useInboxUnreadCounts(account),total:useInboxUnreadMessageTotal(account)}))
 act(()=>applyInboxUnreadSnapshot(account,'chat',[item],Date.now()-1000))
 const event={id:'new-message',sender_id:'peer',created_at:'2026-10-29T18:00:01Z',status:'sent'}
 act(()=>notifyInboxMessage(account,'chat','chat',event,true))
 expect(currentInboxUnreadCount(account,'chat',item)).toBe(1)
 expect(result.current.nav.chats).toBe(1);expect(result.current.total).toBe(1)
 act(()=>notifyInboxMessage(account,'chat','chat',event,true))
 expect(result.current.total).toBe(1)
 act(()=>notifyInboxMessage(account,'chat','chat',{...event,id:'second',created_at:'2026-10-29T18:00:02Z'},true))
 expect(result.current.total).toBe(2);expect(result.current.nav.chats).toBe(1)
})

test('an HTTP request started before the realtime event cannot roll its unread badge back',()=>{
 const account='realtime-response-race',item={id:'chat',unreadCount:0,updatedAt:'2026-10-29T18:00:00Z'},started=Date.now()-100
 applyInboxUnreadSnapshot(account,'chat',[item],started-10)
 notifyInboxMessage(account,'chat','chat',{id:'incoming',sender_id:'peer',created_at:'2026-10-29T18:00:01Z'},true)
 expect(applyInboxUnreadSnapshot(account,'chat',[item],started)).toBe(false)
 expect(currentInboxUnreadCount(account,'chat',item)).toBe(1)
 applyInboxUnreadSnapshot(account,'chat',[{...item,unreadCount:1,updatedAt:'2026-10-29T18:00:01Z',unreadMessageIds:['incoming']}],Date.now()+10)
 notifyInboxMessage(account,'chat','chat',{id:'incoming',sender_id:'peer',created_at:'2026-10-29T18:00:01Z'},true)
 expect(currentInboxUnreadCount(account,'chat',item)).toBe(1)
})

test('created events exclude outgoing, deleted, read and historical messages and retain archived separation',()=>{
 const account='realtime-filter',item={id:'chat',unreadCount:0,updatedAt:'2026-10-29T18:00:00Z',isArchived:true}
 const {result}=renderHook(()=>useInboxUnreadCounts(account))
 act(()=>applyInboxUnreadSnapshot(account,'chat',[item],Date.now()-1000))
 const event={id:'message',sender_id:'peer',created_at:'2026-10-29T18:00:01Z'}
 for(const change of [{sender_id:account},{deleted_for_all:true},{status:'read'},{read_at:'2026-10-29T18:00:01Z'},{created_at:'2026-10-29T17:00:00Z'}])act(()=>notifyInboxMessage(account,'chat','chat',{...event,...change},true))
 expect(result.current.archivedChats).toBe(0)
 act(()=>notifyInboxMessage(account,'chat','chat',{...event,id:'valid'},true))
 expect(result.current.chats).toBe(0);expect(result.current.archivedChats).toBe(1)
 act(()=>markInboxConversationRead(account,'chat','chat'))
 expect(result.current.archivedChats).toBe(0)
 expect(currentInboxUnreadCount('different-account','chat',item)).toBe(0)
})
import {dedupeInboxConversations} from '../../features/messages/conversationInboxSupport'

test('reloading keeps confirmed incoming IDs read after our own outgoing activity',()=>{
 const account='reload-confirmed-ids',at=new Date(Date.now()-1000).toISOString()
 const item={id:'chat',unreadCount:1,unreadMessageIds:['already-read'],updatedAt:at}
 applyInboxUnreadSnapshot(account,'chat',[item],100)
 const token=markInboxConversationRead(account,'chat','chat')
 confirmInboxConversationRead(account,'chat','chat',token,at)
 jest.isolateModules(()=>{
  const fresh=jest.requireActual<typeof import('../../core/inboxActivity')>('../../core/inboxActivity')
  const afterSending={...item,updatedAt:new Date(Date.now()+1000).toISOString()}
  expect(fresh.inboxUnreadCount(account,'chat',afterSending)).toBe(0)
  expect(fresh.inboxUnreadCount(account,'chat',{...afterSending,unreadCount:2,unreadMessageIds:['already-read','new-incoming']})).toBe(1)
  expect(fresh.inboxUnreadCount('different-account','chat',afterSending)).toBe(1)
 })
})

test('legacy cursors load and malformed persisted IDs are ignored',()=>{
 const account='cursor-upgrade',time=Date.now()-1000
 localStorage.setItem(`novvin:read-cursors:${account}`,JSON.stringify([['chat:old',time],['chat:new',time,[null,{},'read-id']]]))
 expect(inboxUnreadCount(account,'chat',{id:'old',unreadCount:1,updatedAt:new Date(time).toISOString()})).toBe(0)
 expect(inboxUnreadCount(account,'chat',{id:'new',unreadCount:2,unreadMessageIds:['read-id','new-id'],updatedAt:new Date(time+1000).toISOString()})).toBe(1)
})

test('persisted confirmed IDs have a global bound and pending reads do not become confirmed',()=>{
 const account='bounded-confirmed-ids',at=new Date().toISOString()
 for(let index=0;index<45;index++){
  const id=`chat-${index}`,ids=Array.from({length:110},(_,i)=>`message-${index}-${i}`)
  applyInboxUnreadSnapshot(account,'chat',[{id,updatedAt:at,unreadCount:ids.length,unreadMessageIds:ids}],index)
  const token=markInboxConversationRead(account,'chat',id)
  confirmInboxConversationRead(account,'chat',id,token,at)
 }
 const saved=JSON.parse(localStorage.getItem(`novvin:read-cursors:${account}`)!) as [string,number,string[]?][]
 expect(saved.reduce((count,entry)=>count+(entry[2]?.length||0),0)).toBe(4000)
 expect(saved.every(entry=>(entry[2]?.length||0)<=100)).toBe(true)
 const pending='pending-not-confirmed'
 applyInboxUnreadSnapshot(pending,'chat',[{id:'chat',updatedAt:at,unreadCount:1,unreadMessageIds:['not-confirmed']}])
 markInboxConversationRead(pending,'chat','chat')
 expect(localStorage.getItem(`novvin:read-cursors:${pending}`)).toBeNull()
})

test('an outgoing sticker cannot revive incoming IDs already confirmed read',()=>{
 const account='own-sticker-regression',item={id:'chat',unreadCount:1,unreadMessageIds:['incoming'],updatedAt:'2026-10-19T07:00:00Z'}
 applyInboxUnreadSnapshot(account,'chat',[item],100)
 const token=markInboxConversationRead(account,'chat','chat')
 confirmInboxConversationRead(account,'chat','chat',token,'2026-10-19T07:00:01Z')
 notifyInboxMessage(account,'chat','chat',{id:'own-sticker',sender_id:account,content_type:'sticker',created_at:'2026-10-19T07:00:02Z'})
 applyInboxUnreadSnapshot(account,'chat',[{...item,updatedAt:'2026-10-19T07:00:02Z'}],Date.now()+1)
 expect(currentInboxUnreadCount(account,'chat',item)).toBe(0)
 applyInboxUnreadSnapshot(account,'chat',[{...item,unreadCount:2,unreadMessageIds:['incoming','new-incoming'],updatedAt:'2026-10-19T07:00:03Z'}],Date.now()+2)
 expect(currentInboxUnreadCount(account,'chat',item)).toBe(1)
})

test('tab total counts individual unread messages and follows read updates',()=>{
 const account='tab-total',{result}=renderHook(()=>useInboxUnreadMessageTotal(account))
 act(()=>{applyInboxUnreadSnapshot(account,'chat',[{id:'a',unreadCount:3,updatedAt:''},{id:'archived',unreadCount:8,updatedAt:'',isArchived:true}],100);applyInboxUnreadSnapshot(account,'group',[{id:'g',unreadCount:2,updatedAt:''}],100)})
 expect(result.current).toBe(5)
 act(()=>{const token=markInboxConversationRead(account,'chat','a');confirmInboxConversationRead(account,'chat','a',token)})
 expect(result.current).toBe(2)
})

test('a confirmed read cursor survives a page reload while a newer incoming message stays unread',()=>{
 const account='persistent-read',item={id:'chat',unreadCount:1,updatedAt:'2026-10-18T12:00:00Z'}
 applyInboxUnreadSnapshot(account,'chat',[item],100)
 const token=markInboxConversationRead(account,'chat','chat')
 confirmInboxConversationRead(account,'chat','chat',token,'2026-10-18T12:01:00Z')
 expect(JSON.parse(localStorage.getItem(`novvin:read-cursors:${account}`)!)).toEqual([['chat:chat',Date.parse('2026-10-18T12:01:00Z')]])
 jest.isolateModules(()=>{
   const fresh=require('../../core/inboxActivity') as typeof import('../../core/inboxActivity')
   expect(fresh.inboxUnreadCount(account,'chat',item)).toBe(0)
   expect(fresh.inboxUnreadCount(account,'chat',{...item,updatedAt:'2026-10-18T12:02:00Z'})).toBe(1)
   expect(fresh.inboxUnreadCount('different-account','chat',item)).toBe(1)
 })
})

test('an unconfirmed read never hides a newer message indefinitely',()=>{
 const account='failed-read',item={id:'chat',unreadCount:1,updatedAt:'2026-10-18T12:00:00Z'}
 applyInboxUnreadSnapshot(account,'chat',[item],100);markInboxConversationRead(account,'chat','chat')
 applyInboxUnreadSnapshot(account,'chat',[{...item,updatedAt:'2026-10-18T12:02:00Z'}],200)
 expect(currentInboxUnreadCount(account,'chat',item)).toBe(1)
})

test('reading on another device removes exactly those IDs and duplicate controls never subtract twice',()=>{
 const {result}=renderHook(()=>useInboxUnreadCounts('remote-partial'))
 const item={id:'room',unreadCount:2,unreadMessageIds:['first','second'],updatedAt:'2026-10-18T12:01:00Z'}
 act(()=>applyInboxUnreadSnapshot('remote-partial','chat',[item],100))
 act(()=>applyRemoteConversationRead('remote-partial','chat','room',['first']))
 expect(currentInboxUnreadCount('remote-partial','chat',item)).toBe(1);expect(result.current.chats).toBe(1)
 act(()=>applyRemoteConversationRead('remote-partial','chat','room',['first']))
 expect(currentInboxUnreadCount('remote-partial','chat',item)).toBe(1)
 act(()=>applyRemoteConversationRead('remote-partial','chat','room',['second']))
 expect(result.current.chats).toBe(0)
 act(()=>applyInboxUnreadSnapshot('remote-partial','chat',[{...item,unreadCount:1,unreadMessageIds:['new']}],200))
 expect(result.current.chats).toBe(1)
})
test('remote read cutoff retains newer messages and is isolated by account and conversation kind',()=>{
 const before={id:'room',unreadCount:1,updatedAt:'2026-10-18T11:59:00Z'}
 const {result}=renderHook(()=>useInboxUnreadCounts('remote-cutoff'))
 act(()=>{applyInboxUnreadSnapshot('remote-cutoff','chat',[before],100);applyInboxUnreadSnapshot('remote-cutoff','group',[before],100)})
 act(()=>applyRemoteConversationRead('other-account','chat','room',[],'2026-10-18T12:00:00Z'))
 expect(result.current.chats).toBe(1)
 act(()=>applyRemoteConversationRead('remote-cutoff','chat','room',[],'2026-10-18T12:00:00Z'))
 expect(result.current.chats).toBe(0);expect(result.current.groups).toBe(1)
 act(()=>applyInboxUnreadSnapshot('remote-cutoff','chat',[{...before,updatedAt:'2026-10-18T12:00:01Z'}],200))
 expect(result.current.chats).toBe(1)
 act(()=>applyRemoteConversationRead('remote-cutoff','chat','room',[],'2026-10-18T11:00:00Z'))
 expect(result.current.chats).toBe(1)
})

test('badges count unique conversations in each section, rather than individual messages',()=>{
 const {result}=renderHook(()=>useInboxUnreadCounts('counts'))
 act(()=>{applyInboxUnreadSnapshot('counts','chat',[{id:'camila',unreadCount:2,updatedAt:'2026-10-17T08:00:00Z'},{id:'camila',unreadCount:2,updatedAt:'2026-10-17T08:00:00Z'}]);applyInboxUnreadSnapshot('counts','group',[5,8,3].map((n,i)=>({id:String(i),unreadCount:n,updatedAt:''})));applyInboxUnreadSnapshot('counts','community',[{id:'c',unreadCount:7,updatedAt:''}])})
 expect(result.current).toEqual({chats:1,groups:3,communities:1,archivedChats:0})
})
test('opening clears immediately and a stale snapshot cannot restore the badge; a later message can',()=>{
 jest.spyOn(Date,'now').mockReturnValue(Date.parse('2026-10-17T09:00:00Z'))
 const {result}=renderHook(()=>useInboxUnreadCounts('read'))
 const old={id:'c',unreadCount:2,updatedAt:'2026-10-17T08:59:00Z'}
 let token:number|undefined
 act(()=>{applyInboxUnreadSnapshot('read','chat',[old]);token=markInboxConversationRead('read','chat','c')})
 expect(result.current.chats).toBe(0)
 act(()=>applyInboxUnreadSnapshot('read','chat',[old]));expect(result.current.chats).toBe(0);expect(inboxUnreadCount('read','chat',old)).toBe(0)
 act(()=>applyInboxUnreadSnapshot('read','chat',[{...old,updatedAt:'2026-10-17T09:01:00Z'}]));expect(result.current.chats).toBe(1)
 act(()=>confirmInboxConversationRead('read','chat','c',token))
 act(()=>applyInboxUnreadSnapshot('read','chat',[{...old,updatedAt:'2026-10-17T09:01:00Z'}],Date.now()-100));expect(result.current.chats).toBe(0)
 act(()=>applyInboxUnreadSnapshot('read','chat',[{...old,updatedAt:'2026-10-17T09:01:00Z'}],Date.now()+100));expect(result.current.chats).toBe(1)
 jest.restoreAllMocks()
})
test('account state and previews remain isolated; an older delivery cannot replace the latest message',()=>{
 notifyInboxMessage('a','chat','same',{id:'new',content:'Nuevo',created_at:'2026-10-17T09:01:00Z'})
 notifyInboxMessage('a','chat','same',{id:'old',content:'Antiguo',created_at:'2026-10-17T09:00:00Z'})
 expect(latestInboxMessage('a','chat','same')?.id).toBe('new');expect(latestInboxMessage('b','chat','same')).toBeUndefined()
 const {result}=renderHook(()=>useInboxUnreadCounts('b'));expect(result.current.chats).toBe(0)
 expect(inboxMessageTarget('room:group:g')).toEqual({scope:'group',id:'g'});expect(inboxMessageTarget('room:community:c')).toEqual({scope:'community',id:'c'});expect(inboxMessageTarget('dm')).toEqual({scope:'chat',id:'dm'})
})
test('an incorrect device clock does not hide later server messages',()=>{
 jest.spyOn(Date,'now').mockReturnValue(Date.parse('2035-01-01T00:00:00Z'))
 const {result}=renderHook(()=>useInboxUnreadCounts('clock'))
 act(()=>applyInboxUnreadSnapshot('clock','chat',[{id:'c',unreadCount:4,updatedAt:'2026-10-17T09:00:00Z'}]))
 let token:number|undefined;act(()=>{token=markInboxConversationRead('clock','chat','c')});act(()=>confirmInboxConversationRead('clock','chat','c',token))
 act(()=>applyInboxUnreadSnapshot('clock','chat',[{id:'c',unreadCount:1,updatedAt:'2026-10-17T09:00:01Z'}],Date.now()+1))
 expect(result.current.chats).toBe(1);jest.restoreAllMocks()
})

test('an older shell response cannot revive a badge after the list accepted a newer clean snapshot',()=>{
 const {result}=renderHook(()=>useInboxUnreadCounts('out-of-order'))
 const item={id:'chat',unreadCount:1,updatedAt:'2026-10-18T19:00:00Z'}
 act(()=>applyInboxUnreadSnapshot('out-of-order','chat',[{...item,unreadCount:0}],200))
 let accepted:boolean|undefined
 act(()=>{accepted=applyInboxUnreadSnapshot('out-of-order','chat',[item],100)})
 expect(accepted).toBe(false)
 expect(result.current.chats).toBe(0)
 expect(currentInboxUnreadCount('out-of-order','chat',item)).toBe(0)
})

test('cached row counts project the same snapshot as navigation and update when its total is unchanged',()=>{
 const {result}=renderHook(()=>useInboxUnreadCounts('row-projection'))
 const cached={id:'chat',unreadCount:0,updatedAt:'2026-10-18T18:00:00Z'}
 act(()=>applyInboxUnreadSnapshot('row-projection','chat',[{...cached,unreadCount:1}],100))
 expect(result.current.chats).toBe(1)
 expect(currentInboxUnreadCount('row-projection','chat',cached)).toBe(1)
 const before=result.current
 act(()=>applyInboxUnreadSnapshot('row-projection','chat',[{...cached,unreadCount:3}],200))
 expect(result.current.chats).toBe(1)
 expect(result.current).not.toBe(before)
 expect(currentInboxUnreadCount('row-projection','chat',cached)).toBe(3)
 act(()=>applyInboxUnreadSnapshot('row-projection','chat',[],300))
 expect(result.current.chats).toBe(0)
 expect(currentInboxUnreadCount('row-projection','chat',cached)).toBe(0)
})

test('a newly discovered unread chat missing from an older list requires a list refresh',()=>{
 applyInboxUnreadSnapshot('missing-row','chat',[{id:'new-chat',unreadCount:1,updatedAt:'2026-10-18T19:00:00Z'}],200)
 expect(hasUnrepresentedInboxUnread('missing-row','chat',['old-chat'])).toBe(true)
 expect(hasUnrepresentedInboxUnread('missing-row','chat',['old-chat','NEW-CHAT'])).toBe(false)
 expect(hasUnrepresentedInboxUnread('missing-row','group',[])).toBe(false)
 applyInboxUnreadSnapshot('missing-row','chat',[],300)
 expect(hasUnrepresentedInboxUnread('missing-row','chat',['old-chat'])).toBe(false)
})

test('offline cached counts agree with rows but cannot replace a fresh network snapshot',()=>{
 const {result}=renderHook(()=>useInboxUnreadCounts('offline-cache'))
 const cached={id:'chat',unreadCount:2,updatedAt:'2026-10-18T17:00:00Z'}
 act(()=>applyInboxUnreadSnapshot('offline-cache','chat',[cached],0))
 expect(result.current.chats).toBe(1)
 expect(currentInboxUnreadCount('offline-cache','chat',cached)).toBe(2)
 act(()=>applyInboxUnreadSnapshot('offline-cache','chat',[{...cached,unreadCount:0}],100))
 act(()=>applyInboxUnreadSnapshot('offline-cache','chat',[cached],0))
 expect(result.current.chats).toBe(0)
 expect(currentInboxUnreadCount('offline-cache','chat',cached)).toBe(0)
})

test('archived unread conversations have their own counter and retain their real unread messages',()=>{
 const {result}=renderHook(()=>useInboxUnreadCounts('archived'))
 const item={id:'archived-chat',unreadCount:4,updatedAt:'2026-10-18T18:00:00Z',isArchived:true}
 act(()=>applyInboxUnreadSnapshot('archived','chat',[item],100))
 expect(result.current.chats).toBe(0)
 expect(result.current.archivedChats).toBe(1)
 expect(currentInboxUnreadCount('archived','chat',item)).toBe(4)
 act(()=>applyInboxUnreadSnapshot('archived','chat',[{...item,isArchived:false}],200))
 expect(result.current.chats).toBe(1)
 expect(result.current.archivedChats).toBe(0)
})

test('the shell uses the same contact deduplication as the visible conversation list',()=>{
 const rows=[{id:'old-chat',updatedAt:'2026-10-18T17:00:00Z'},{id:'new-chat',updatedAt:'2026-10-18T18:00:00Z'}].map(row=>({...row,kind:'chat' as const,profileId:'peer',unreadCount:1,isPinned:false,isMuted:false,isArchived:false,isFavorite:false,folders:[]}))
 const {result}=renderHook(()=>useInboxUnreadCounts('same-peer'))
 const visible=dedupeInboxConversations(rows)
 act(()=>applyInboxUnreadSnapshot('same-peer','chat',visible,100))
 expect(visible).toHaveLength(1)
 expect(result.current.chats).toBe(1)
 expect(currentInboxUnreadCount('same-peer','chat',visible[0])).toBe(1)
})

test('pending outgoing preview time does not change the timestamp used to classify actual incoming unread',()=>{
 const {result}=renderHook(()=>useInboxUnreadCounts('optimistic-preview'))
 const item={id:'chat',unreadCount:1,updatedAt:'2026-10-18T18:00:00Z'}
 let token:number|undefined
 act(()=>{applyInboxUnreadSnapshot('optimistic-preview','chat',[item]);token=markInboxConversationRead('optimistic-preview','chat','chat')})
 act(()=>confirmInboxConversationRead('optimistic-preview','chat','chat',token))
 const requested=Date.now()+1
 act(()=>{
  applyInboxUnreadSnapshot('optimistic-preview','chat',[item],requested)
  notifyInboxMessage('optimistic-preview','chat','chat',{id:'own',status:'pending',created_at:'2026-10-18T18:02:00Z'})
 })
 expect(currentInboxUnreadCount('optimistic-preview','chat',{...item,updatedAt:'2026-10-18T18:02:00Z'})).toBe(0)
 act(()=>applyInboxUnreadSnapshot('optimistic-preview','chat',[{...item,updatedAt:'2026-10-18T18:01:00Z'}],requested+1))
 expect(result.current.chats).toBe(1)
 expect(currentInboxUnreadCount('optimistic-preview','chat',item)).toBe(1)
})
