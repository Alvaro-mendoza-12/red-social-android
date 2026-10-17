import {renderHook,act} from '@testing-library/react'
import {applyInboxUnreadSnapshot,confirmInboxConversationRead,inboxUnreadCount,markInboxConversationRead,useInboxUnreadCounts,notifyInboxMessage,latestInboxMessage,inboxMessageTarget} from '../../core/inboxActivity'

test('badges count unique conversations in each section, rather than individual messages',()=>{
 const {result}=renderHook(()=>useInboxUnreadCounts('counts'))
 act(()=>{applyInboxUnreadSnapshot('counts','chat',[{id:'camila',unreadCount:2,updatedAt:'2026-10-17T08:00:00Z'},{id:'camila',unreadCount:2,updatedAt:'2026-10-17T08:00:00Z'}]);applyInboxUnreadSnapshot('counts','group',[5,8,3].map((n,i)=>({id:String(i),unreadCount:n,updatedAt:''})));applyInboxUnreadSnapshot('counts','community',[{id:'c',unreadCount:7,updatedAt:''}])})
 expect(result.current).toEqual({chats:1,groups:3,communities:1})
})
test('opening clears immediately and a stale snapshot cannot restore the badge; a later message can',()=>{
 jest.spyOn(Date,'now').mockReturnValue(Date.parse('2026-10-17T09:00:00Z'))
 const {result}=renderHook(()=>useInboxUnreadCounts('read'))
 const old={id:'c',unreadCount:2,updatedAt:'2026-10-17T08:59:00Z'}
 let token:number|undefined
 act(()=>{applyInboxUnreadSnapshot('read','chat',[old]);token=markInboxConversationRead('read','chat','c')})
 expect(result.current.chats).toBe(0)
 act(()=>applyInboxUnreadSnapshot('read','chat',[old]));expect(result.current.chats).toBe(0);expect(inboxUnreadCount('read','chat',old)).toBe(0)
 act(()=>applyInboxUnreadSnapshot('read','chat',[{...old,updatedAt:'2026-10-17T09:01:00Z'}]));expect(result.current.chats).toBe(0)
 confirmInboxConversationRead('read','chat','c',token)
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
 let token:number|undefined;act(()=>{token=markInboxConversationRead('clock','chat','c')});confirmInboxConversationRead('clock','chat','c',token)
 act(()=>applyInboxUnreadSnapshot('clock','chat',[{id:'c',unreadCount:1,updatedAt:'2026-10-17T09:00:01Z'}],Date.now()+1))
 expect(result.current.chats).toBe(1);jest.restoreAllMocks()
})
