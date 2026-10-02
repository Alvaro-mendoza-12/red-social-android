import { useEffect, useState } from 'react'
import { useAuth } from '../core/auth/AuthProvider'
import { activeAnnouncement, announcementSchedule, loadSystemAnnouncements, officialUpdateUrl, type SystemAnnouncement } from '../core/systemAnnouncements'
import { isFallbackAuthConfigured } from '../core/auth/fallbackAuth'
import './systemAnnouncements.css'

export function SystemAnnouncementHost() {
  const { user } = useAuth(), owner = user?.id || 'guest'
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
    let pending = false
    async function refresh() {
      if (pending || document.visibilityState === 'hidden') return
      pending = true
      try {
        const fresh = await loadSystemAnnouncements(false, abort.signal)
        if (disposed) return
        try { localStorage.setItem('novvin:system-notices:cache', JSON.stringify(fresh)) } catch { /* Storage is optional. */ }
        // Re-read seen IDs after network completion so a dismissed notice does
        // not reappear while its request was still in flight.
        try { for (const id of JSON.parse(localStorage.getItem(seenKey) || '[]')) dismissed.add(String(id)) } catch { /* Storage is optional. */ }
        show(fresh)
      } catch { /* Offline: retain only cached notices that have not expired. */ }
      finally { pending = false }
    }
    void refresh()
    window.addEventListener('focus', refresh)
    document.addEventListener('visibilitychange', refresh)
    return () => { disposed = true; abort.abort(); window.removeEventListener('focus', refresh); document.removeEventListener('visibilitychange', refresh) }
  }, [owner, seenKey])
  const notice = current.owner === owner ? current.notices.find(item => activeAnnouncement(item)) : undefined
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
  return <aside className="system-notice-banner" role="region" aria-label="Aviso de Novvin">
    <div><strong>{notice.title}</strong>{announcementSchedule(notice) && <p>{announcementSchedule(notice)}</p>}<p>{notice.body}</p></div>
    <div className="system-notice-actions">{notice.kind === 'update' && <a className="primary-btn" href={officialUpdateUrl} target="_blank" rel="noopener noreferrer">Actualizar app Android</a>}
      <button type="button" className="ghost-btn" onClick={dismiss}>{notice.kind === 'update' ? 'Más tarde' : 'Entendido'}</button></div>
  </aside>
}
