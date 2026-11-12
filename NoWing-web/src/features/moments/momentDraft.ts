import { useCallback, useSyncExternalStore } from 'react'
import { getStorageReadIdentity, onStorageReadInvalidated, subscribeStorageReadIdentity, type StorageReadIdentity } from '../../core/media/storageReadSession'
import { friendlyError } from '../../core/utils'
import { localAudioDuration } from './localMomentAudio'
import type { MomentMusicSelection, MomentOverlay, MomentTrack } from './momentMusic'

export type MomentDraft = {
  file: File | null
  videoDuration: number
  caption: string
  durationHours: number
  visibility: string
  replies: boolean
  music?: MomentMusicSelection
  overlay: MomentOverlay
  status: 'editing' | 'publishing' | 'published'
  error: string
  audioPending: boolean
}
const empty: MomentDraft = {
  file: null, videoDuration: 0, caption: '', durationHours: 24, visibility: 'all', replies: true,
  overlay: { text: '', color: '#ffffff', font: 'sans', x: .5, y: .38 }, status: 'editing', error: '', audioPending: false,
}
type Publication = { identity: StorageReadIdentity }
let current: { identity: StorageReadIdentity; draft: MomentDraft } | null = null
let publication: Publication | null = null
let audioRead: AbortController | null = null
const listeners = new Set<() => void>()
const subscribe = (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener) } }

export function hasMomentDraft(draft: MomentDraft) {
  return Boolean(draft.audioPending || draft.file || draft.caption || draft.music || draft.durationHours !== 24 || draft.visibility !== 'all' || !draft.replies ||
    draft.overlay.text || draft.overlay.color !== '#ffffff' || draft.overlay.font !== 'sans' || draft.overlay.x !== .5 || draft.overlay.y !== .38)
}
function valid(identity: StorageReadIdentity) {
  return identity === getStorageReadIdentity() && Boolean(identity.accountId) && (identity.provider === 'fallback' || identity.provider === 'supabase')
}
function warnBeforeUnload(event: BeforeUnloadEvent) { event.preventDefault(); event.returnValue = '' }
function notify() {
  if (typeof window !== 'undefined') {
    window.removeEventListener('beforeunload', warnBeforeUnload)
    if (current && (hasMomentDraft(current.draft) || current.draft.status === 'publishing')) window.addEventListener('beforeunload', warnBeforeUnload)
  }
  listeners.forEach(listener => listener())
}
// One draft in this tab's memory, never localStorage/IndexedDB or a server upload.
// Clear synchronously on logout, account/provider change, or backend invalidation,
// including while the Moments route is unmounted. Old asynchronous completions lose their token.
onStorageReadInvalidated(() => { audioRead?.abort(); audioRead = null; current = null; publication = null; notify() })

export function discardMomentDraft(identity: StorageReadIdentity) {
  if (!valid(identity) || current?.draft.status === 'publishing') return
  audioRead?.abort(); audioRead = null; current = null; notify()
}
export function beginMomentPublication(identity: StorageReadIdentity): Publication | null {
  if (!valid(identity) || current?.identity !== identity || !current.draft.file || current.draft.audioPending || publication) return null
  publication = { identity }
  current = { identity, draft: { ...current.draft, status: 'publishing', error: '' } }; notify()
  return publication
}
export function finishMomentPublication(attempt: Publication, error?: string) {
  if (publication !== attempt || !valid(attempt.identity) || current?.identity !== attempt.identity) return
  publication = null
  current = { identity: attempt.identity, draft: error ? { ...current.draft, status: 'editing', error } : { ...empty, status: 'published' } }
  notify()
}
function updateMomentDraft(identity: StorageReadIdentity, change: Partial<MomentDraft> | ((draft: MomentDraft) => Partial<MomentDraft>)) {
  if (!valid(identity) || publication) return
  const previous = current?.identity === identity ? current.draft : empty
  current = { identity, draft: { ...previous, ...(typeof change === 'function' ? change(previous) : change), status: 'editing' } }; notify()
}
export function selectMomentTrack(identity: StorageReadIdentity, track: MomentTrack) {
  if (!valid(identity) || publication) return
  audioRead?.abort(); audioRead = null
  updateMomentDraft(identity, { audioPending: false, music: { track, startMs: 0, volume: 1, originalVolume: 1, fadeInMs: 0, fadeOutMs: 0, duckOriginal: false, presentation: 'card' } })
}
/** Metadata preparation belongs to the session draft, not the mounted editor. */
export async function selectMomentAudio(identity: StorageReadIdentity, audio: File) {
  if (!valid(identity) || publication) return
  if (!audio.type.startsWith('audio/') || audio.size > 80 * 1024 * 1024) { updateMomentDraft(identity, { error: 'Elige un archivo de audio de hasta 80 MB.' }); return }
  audioRead?.abort()
  const controller = new AbortController(); audioRead = controller
  updateMomentDraft(identity, { audioPending: true, error: '' })
  try {
    const duration = await localAudioDuration(audio, controller.signal)
    if (audioRead !== controller || controller.signal.aborted || !valid(identity)) return
    selectMomentTrack(identity, { id: `device:${crypto.randomUUID()}`, title: audio.name.replace(/\.[^.]+$/, ''), artist: 'Mi audio', audioUrl: '', source: 'novvin_moment_local', durationMs: duration, localFile: audio })
  } catch (caught) {
    if (audioRead === controller && !controller.signal.aborted) updateMomentDraft(identity, { error: friendlyError(caught, 'No pudimos abrir el audio.') })
  } finally {
    if (audioRead === controller) { audioRead = null; updateMomentDraft(identity, { audioPending: false }) }
  }
}
export function useMomentDraft(owner: string | undefined) {
  const identity = useSyncExternalStore(subscribeStorageReadIdentity, getStorageReadIdentity, getStorageReadIdentity)
  const available = valid(identity) && identity.accountId === owner
  const draft = useSyncExternalStore(subscribe, () => {
    const active = getStorageReadIdentity()
    return active.accountId === owner && current?.identity === active ? current.draft : empty
  }, () => empty)
  const update = useCallback((change: Partial<MomentDraft> | ((draft: MomentDraft) => Partial<MomentDraft>)) => {
    if (identity.accountId === owner) updateMomentDraft(identity, change)
  }, [identity, owner])
  return { draft, update, identity, available }
}
