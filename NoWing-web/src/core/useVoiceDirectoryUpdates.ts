import { useEffect, useRef } from 'react'
import { subscribeUbuntuRealtime } from './ubuntuRealtime'

/** Events invalidate the authorized directory; never trust global participant payloads. */
export function useVoiceDirectoryUpdates(refresh: () => void, enabled: boolean) {
  const callback = useRef(refresh)
  useEffect(() => { callback.current = refresh }, [refresh])
  useEffect(() => {
    if (!enabled) return
    let timer: ReturnType<typeof setTimeout> | undefined
    const schedule = () => {
      clearTimeout(timer)
      timer = setTimeout(() => callback.current(), 120)
    }
    const stop = subscribeUbuntuRealtime({ channel: 'voice-rooms:live', onEvent: schedule })
    window.addEventListener('novvin:realtime-ready', schedule)
    window.addEventListener('novvin:voice-rooms-changed', schedule)
    const onVisible = () => { if (document.visibilityState === 'visible') schedule() }
    document.addEventListener('visibilitychange', onVisible)
    const interval = setInterval(onVisible, 15000)
    return () => {
      clearTimeout(timer); clearInterval(interval); stop()
      window.removeEventListener('novvin:realtime-ready', schedule)
      window.removeEventListener('novvin:voice-rooms-changed', schedule)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [enabled])
}
