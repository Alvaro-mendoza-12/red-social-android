import { useEffect, useRef } from 'react'
import { ubuntuRealtimeConnected } from '../../core/ubuntuRealtime'

/** Reconcile messages missed during sleep, network changes, or socket recovery. */
export function useConversationRecovery(enabled: boolean, synchronize: () => Promise<unknown>) {
  const latest = useRef(synchronize)
  useEffect(() => { latest.current = synchronize }, [synchronize])
  useEffect(() => {
    if (!enabled) return
    let running = false, disposed = false
    let timer: ReturnType<typeof setTimeout> | undefined
    const run = async () => {
      if (disposed || running || document.hidden || !navigator.onLine) return
      running = true
      try { await latest.current() } catch { /* Keep the last visible conversation during recovery. */ }
      finally { running = false }
    }
    const recover = () => {
      clearTimeout(timer)
      timer = setTimeout(() => { void run() }, 100)
    }
    const fallback = setInterval(() => { if (!ubuntuRealtimeConnected()) void run() }, 15000)
    window.addEventListener('novvin:realtime-ready', recover)
    window.addEventListener('online', recover)
    window.addEventListener('focus', recover)
    document.addEventListener('visibilitychange', recover)
    return () => {
      disposed = true; clearTimeout(timer); clearInterval(fallback)
      window.removeEventListener('novvin:realtime-ready', recover)
      window.removeEventListener('online', recover)
      window.removeEventListener('focus', recover)
      document.removeEventListener('visibilitychange', recover)
    }
  }, [enabled])
}
