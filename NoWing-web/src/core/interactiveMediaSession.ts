import { useSyncExternalStore } from 'react'
import { setChunkReloadGuard } from './chunkRecovery'
export type InteractiveMediaSession = { accountId: string; key: string; kind: 'live' | 'voice'; title: string; path: string; host?: boolean; close: () => Promise<void> }
let current: InteractiveMediaSession | null = null
setChunkReloadGuard(()=>Boolean(current)||Boolean(document.querySelector('.active-call'))||Array.from(document.querySelectorAll<HTMLInputElement|HTMLTextAreaElement>('form input:not([type=hidden]),textarea')).some(input=>Boolean(input.value)))
const listeners = new Set<() => void>()
const subscribe = (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener) } }
export const getInteractiveMediaSession = () => current
export function claimInteractiveMediaSession(session: InteractiveMediaSession) {
  if (current && current.accountId === session.accountId && current.key !== session.key) return false
  current = session; listeners.forEach(listener => listener()); return true
}
export function releaseInteractiveMediaSession(key: string) {
  if (current?.key !== key) return
  current = null; listeners.forEach(listener => listener())
}
export function useInteractiveMediaSession() { return useSyncExternalStore(subscribe, getInteractiveMediaSession, () => null) }
