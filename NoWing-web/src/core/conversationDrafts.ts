import { useCallback, useSyncExternalStore, type SetStateAction } from 'react'
type Scope = 'chat' | 'group' | 'community'
const prefix = 'novvin:conversation-draft:v1:'
const listeners = new Set<() => void>(), memory = new Map<string,string>()
let revision = 0
const notify = () => { revision++; listeners.forEach(listener => listener()) }
const subscribe = (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener) } }
if (typeof window !== 'undefined') window.addEventListener('storage', event => { if (!event.key || event.key.startsWith(prefix)) { memory.clear(); notify() } })
function key(owner: string | undefined, scope: Scope, room: string) {
 return owner?.trim() && room.trim() ? `${prefix}${encodeURIComponent(owner.trim().toLowerCase())}:${scope}:${encodeURIComponent(room.trim().toLowerCase())}` : ''
}
export function readConversationDraft(owner: string | undefined, scope: Scope, room: string) {
 const name=key(owner,scope,room);if(!name)return ''
 if(memory.has(name))return memory.get(name) || ''
 try { return localStorage.getItem(name) || '' } catch { return '' }
}
export function writeConversationDraft(owner: string | undefined, scope: Scope, room: string, text: string) {
 const name=key(owner,scope,room);if(!name)return
 const value=text.slice(0,16000)
 try { if(value)localStorage.setItem(name,value);else localStorage.removeItem(name);memory.delete(name) } catch { memory.set(name,value) }
 notify()
}
export function useDraftRevision() { return useSyncExternalStore(subscribe,()=>revision,()=>0) }
export function useConversationDraft(owner: string | undefined, scope: Scope, room: string) {
 const text=useSyncExternalStore(subscribe,()=>readConversationDraft(owner,scope,room),()=>readConversationDraft(owner,scope,room))
 const setText=useCallback((value:SetStateAction<string>)=>writeConversationDraft(owner,scope,room,typeof value==='function'?value(readConversationDraft(owner,scope,room)):value),[owner,scope,room])
 return [text,setText] as const
}
