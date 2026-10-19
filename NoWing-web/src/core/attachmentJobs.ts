import { useCallback, useEffect, useSyncExternalStore, type SetStateAction } from 'react'
import { attachmentPreparationError, type PreparationProgress } from './attachmentPreparation'

type Scope = 'chat' | 'group' | 'community'
type Attachment = { previewUrl: string }
type Job = { owner: string; key: string; route: string; status: 'preparing' | 'ready' | 'failed'; message: string; files: File[] }
const jobs = new Map<string, Job>(), drafts = new Map<string, Attachment[]>()
const listeners = new Set<() => void>()
let revision = 0
const empty: never[] = []
const notify = () => { revision++; listeners.forEach(listener => listener()) }
const subscribe = (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener) } }
const draftKey = (owner: string, scope: Scope, room: string) => JSON.stringify([owner, scope, room])
export const useAttachmentJobRevision = () => useSyncExternalStore(subscribe, () => revision, () => 0)
export const attachmentJobsFor = (owner: string) => [...jobs.values()].filter(job => job.owner === owner)
export function dismissAttachmentJob(key: string) { jobs.delete(key); notify() }

export function retainAttachmentOwner(owner: string) {
  for (const [key, job] of jobs) if (job.owner !== owner) jobs.delete(key)
  for (const [key, values] of drafts) if (JSON.parse(key)[0] !== owner) {
    values.forEach(value => { if (value.previewUrl) URL.revokeObjectURL(value.previewUrl) })
    drafts.delete(key)
  }
  notify()
}

/** Files stay in memory under the originating account and chat across SPA navigation. */
export function useAttachmentDraft<T extends Attachment>(owner: string, scope: Scope, room: string) {
  const key = draftKey(owner, scope, room)
  const value = useSyncExternalStore(subscribe, () => (drafts.get(key) || empty) as T[], () => empty as T[])
  const setValue = useCallback((input: SetStateAction<T[]>) => {
    const previous = (drafts.get(key) || empty) as T[]
    const next = typeof input === 'function' ? input(previous) : input
    if (next.length) drafts.set(key, next); else drafts.delete(key)
    notify()
  }, [key])
  return [value, setValue] as const
}

export function useAttachmentPreparation(owner: string, scope: Scope, room: string, onReady: (files: File[]) => void, onError: (message: string) => void) {
  const version = useAttachmentJobRevision()
  const key = draftKey(owner, scope, room), job = jobs.get(key)
  const start = useCallback((prepare: (progress: PreparationProgress) => Promise<File[]>) => {
    if (!owner || !room || jobs.get(key)?.status === 'preparing') return
    const route = `/app/messages/${scope === 'chat' ? '' : `${scope}/`}${encodeURIComponent(room)}`
    const current: Job = { owner, key, route, status: 'preparing', message: 'Preparando archivos…', files: [] }
    jobs.set(key, current); notify()
    // Invoke immediately: DataTransfer grants expire at the end of the drop event.
    const pending = prepare(message => {
      if (jobs.get(key) !== current) return
      current.message = message; notify()
    })
    void pending.then(files => {
      if (jobs.get(key) !== current) return
      current.files = files; current.status = 'ready'
      if (!/Listo para enviar/.test(current.message)) current.message = `${files.length} archivo(s) listos para enviar`
      notify()
    }).catch(error => {
      if (jobs.get(key) !== current) return
      current.status = 'failed'; current.message = attachmentPreparationError(error); notify()
    })
  }, [key, owner, room, scope])
  useEffect(() => {
    const current = jobs.get(key)
    if (!current || current.status === 'preparing') return
    jobs.delete(key)
    if (current.status === 'ready') onReady(current.files)
    else onError(current.message)
    notify()
  }, [key, version, onReady, onError])
  const cancel = useCallback(() => { jobs.delete(key); notify() }, [key])
  return { start, cancel, preparing: job?.status === 'preparing', status: job?.message || 'Preparando archivos…' }
}
