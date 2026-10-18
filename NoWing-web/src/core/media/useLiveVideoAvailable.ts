import { useCallback, useSyncExternalStore } from 'react'

/** Tracks can arrive after the stream, or be removed without replacing it. */
export function useLiveVideoAvailable(stream: MediaStream | null) {
  const subscribe = useCallback((notify: () => void) => {
    if (!stream) return () => {}
    const observed = new Set<MediaStreamTrack>()
    const refresh = () => {
      for (const track of observed) {
        if (!stream.getVideoTracks().includes(track)) {
          for (const event of ['mute', 'unmute', 'ended']) track.removeEventListener(event, notify)
          observed.delete(track)
        }
      }
      for (const track of stream.getVideoTracks()) {
        if (observed.has(track)) continue
        observed.add(track)
        for (const event of ['mute', 'unmute', 'ended']) track.addEventListener(event, notify)
      }
      notify()
    }
    stream.addEventListener('addtrack', refresh)
    stream.addEventListener('removetrack', refresh)
    stream.addEventListener('inactive', notify)
    refresh()
    return () => {
      stream.removeEventListener('addtrack', refresh)
      stream.removeEventListener('removetrack', refresh)
      stream.removeEventListener('inactive', notify)
      for (const track of observed) for (const event of ['mute', 'unmute', 'ended']) track.removeEventListener(event, notify)
    }
  }, [stream])
  // RTP mute can be a brief network interruption. Keep the renderer and its
  // last frame mounted; the participant's signaled video state handles camera off.
  const snapshot = useCallback(() => Boolean(stream?.getVideoTracks().some(track => track.readyState === 'live' && track.enabled)), [stream])
  return useSyncExternalStore(subscribe, snapshot, () => false)
}
