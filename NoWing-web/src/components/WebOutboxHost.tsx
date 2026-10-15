import { warmBrowserStreaming } from '../core/media/browserStreaming'
import { useEffect } from 'react'
import { useAuth } from '../core/auth/AuthProvider'
import { flushWebOutbox, revokeOutboxPreviews } from '../core/webOutbox'

export function WebOutboxHost() {
  const { user, isFallbackSession } = useAuth()
  useEffect(() => {
    if (!user || !isFallbackSession) return
    warmBrowserStreaming()
    const flush = () => { void flushWebOutbox().catch(() => {}) }
    flush()
    window.addEventListener('online', flush)
    window.addEventListener('novvin:realtime-ready', flush)
    const timer = window.setInterval(flush, 5000)
    return () => { window.clearInterval(timer); window.removeEventListener('online', flush); window.removeEventListener('novvin:realtime-ready', flush); revokeOutboxPreviews() }
  }, [user?.id, isFallbackSession])
  return null
}
