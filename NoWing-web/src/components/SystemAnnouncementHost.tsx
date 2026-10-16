import { useEffect, useRef, useState } from 'react'
import { useAuth } from '../core/auth/AuthProvider'
import { activeAnnouncement, announcementSchedule, loadSystemAnnouncements, officialUpdateUrl, type SystemAnnouncement } from '../core/systemAnnouncements'
import { isFallbackAuthConfigured } from '../core/auth/fallbackAuth'
import './systemAnnouncements.css'

export function SystemAnnouncementHost() {
  const { user } = useAuth(), owner = user?.id || 'guest'
  const bannerRef = useRef<HTMLElement>(null)
  const [current, setCurrent] = useState<{ owner: string; notices: SystemAnnouncement[] }>({ owner, notices: [] })
  const seenKey = `novvin:system-notices:seen:${owner}`
  useEffect(() => {
    if (!isFallbackAuthConfigured) return
    let disposed = false
    const abort = new AbortController()
    const dismissed = new Set<string>()
    try { for (const id of JSON.parse(localStorage.getItem(seenKey) || '[]')) dismissed.add(String(id)) } catch { /* Storage is optional. */ }
    function show(notices: SystemAnnouncement[]) {
      if (!disposed) setCurrent({ owner, notices: notices.filter(notice => activeAnnouncement(notice) && !dismissed.has(notice.id)) })
    }
    try { show(JSON.parse(localStorage.getItem('novvin:system-notices:cache') || '[]')) } catch { /* Invalid cache. */ }
    let pending = false, fetchedAt = 0
    async function refresh() {
      if (pending || document.visibilityState === 'hidden' || Date.now() - fetchedAt < 60_000) return
      pending = true
      try {
        const fresh = await loadSystemAnnouncements(false, abort.signal)
        fetchedAt = Date.now()
        if (disposed) return
        try { localStorage.setItem('novvin:system-notices:cache', JSON.stringify(fresh)) } catch { /* Storage is optional. */ }
        // Re-read seen IDs after network completion so a dismissed notice does
        // not reappear while its request was still in flight.
        try { for (const id of JSON.parse(localStorage.getItem(seenKey) || '[]')) dismissed.add(String(id)) } catch { /* Storage is optional. */ }
        show(fresh)
      } catch { /* Offline: retain only cached notices that have not expired. */ }
      finally { pending = false }
    }
    const onSeenChange = (event: StorageEvent) => {
      if (event.key !== seenKey) return
      try {
        const ids: unknown = JSON.parse(event.newValue || '[]')
        if (Array.isArray(ids)) for (const id of ids) dismissed.add(String(id))
        setCurrent(value => ({ ...value, notices: value.notices.filter(item => !dismissed.has(item.id)) }))
      } catch { /* Ignore malformed storage events. */ }
    }
    window.addEventListener('storage', onSeenChange)
    void refresh()
    window.addEventListener('focus', refresh)
    document.addEventListener('visibilitychange', refresh)
    return () => { disposed = true; abort.abort(); window.removeEventListener('storage', onSeenChange); window.removeEventListener('focus', refresh); document.removeEventListener('visibilitychange', refresh) }
  }, [owner, seenKey])
  const notice = current.owner === owner ? current.notices.find(item => activeAnnouncement(item)) : undefined
  useEffect(() => {
    const root = document.documentElement, banner = bannerRef.current
    const update = () => root.style.setProperty('--novvin-system-notice-height', `${banner?.getBoundingClientRect().height || 0}px`)
    update()
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(update)
    if (banner) observer?.observe(banner)
    window.addEventListener('resize', update)
    return () => { observer?.disconnect(); window.removeEventListener('resize', update); root.style.removeProperty('--novvin-system-notice-height') }
  }, [notice?.id])
  if (!notice) return null
  function dismiss() {
    if (!notice) return
    setCurrent(value => ({ ...value, notices: value.notices.filter(item => item.id !== notice.id) }))
    try {
      const old: unknown = JSON.parse(localStorage.getItem(seenKey) || '[]')
      const ids = new Set(Array.isArray(old) ? old.map(String) : [])
      ids.add(notice.id); localStorage.setItem(seenKey, JSON.stringify([...ids].slice(-200)))
    } catch { /* Dismiss still works for this screen without persistent storage. */ }
  }
  return <aside ref={bannerRef} className="system-notice-banner" role="region" aria-label="Aviso de Novvin">
    <div><strong>{notice.title}</strong>{announcementSchedule(notice) && <p>{announcementSchedule(notice)}</p>}<p>{notice.body}</p></div>
    <div className="system-notice-actions">{notice.kind === 'update' && <a className="primary-btn" href={officialUpdateUrl} target="_blank" rel="noopener noreferrer">Actualizar app Android</a>}
      <button type="button" className="ghost-btn" onClick={dismiss}>{notice.kind === 'update' ? 'Más tarde' : 'Entendido'}</button></div>
  </aside>
}
