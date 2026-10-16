import './messageSticker.css'
import { useEffect, useRef, useState } from 'react'
import { Star, Reply, Forward } from 'lucide-react'
import { ChatAttachmentMedia } from './ChatAttachmentMedia'
import { PublicationDialog } from './PublicationDialog'
import { MediaDownloadButton } from './MediaDownloadButton'
import { useAuth } from '../core/auth/AuthProvider'
import { fallbackApiJson } from '../core/auth/fallbackAuth'
import { supabase } from '../core/supabase'
import { shouldUseUbuntuBackend } from '../core/ubuntuBackend'
import { rewriteRuntimeStorageUrl } from '../core/runtimeBackend'
import { textOf, type GenericRow } from '../core/utils'

export function MessageSticker({ url, title, onReply, onForward }: { url: string; title: string; onReply: () => void; onForward: () => void }) {
  const { user, isFallbackSession } = useAuth(), useUbuntu = shouldUseUbuntuBackend(isFallbackSession)
  const owner = useRef(user?.id); owner.current = user?.id
  const [open, setOpen] = useState(false), [item, setItem] = useState<{ id: string; favorite: boolean; animated: boolean } | null>(null)
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  const accountId = user?.id
  useEffect(() => {
    if (!open || !accountId) return
    let active = true
    void (async () => {
      await Promise.resolve()
      if (!active) return
      setItem(null); setBusy(false); setError('')
      try {
        const input = { p_query: '', p_limit: 250 }
        const rows = useUbuntu ? await fallbackApiJson<GenericRow[]>('/rest/v1/rpc/secure_search_stickers', { method: 'POST', body: JSON.stringify(input), expectedAccountId: accountId }) : (await supabase!.rpc('secure_search_stickers', input)).data || []
        const key = rewriteRuntimeStorageUrl(url).split('?')[0]
        const found = rows.find((row: GenericRow) => rewriteRuntimeStorageUrl(textOf(row, ['media_url', 'url'])).split('?')[0] === key)
        const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(key))
        const id = found ? textOf(found, ['sticker_id', 'id']) : 'message:' + Array.from(new Uint8Array(digest)).map(value => value.toString(16).padStart(2, '0')).join('')
        if (active) setItem({ id, favorite: found?.is_favorite === true, animated: found?.is_animated === true || (typeof found?.is_animated !== 'boolean' && /\.(gif|apng)(?:[?#]|$)/i.test(url)) })
      } catch { if (active) setError('No se pudo consultar este sticker. Cierra y vuelve a intentarlo.') }
    })()
    return () => { active = false }
  }, [open, url, accountId, useUbuntu])
  async function toggle() {
    if (!item || !user || busy) return
    setBusy(true); setError('')
    try {
      const input = { p_sticker_id: item.id, p_favorite: !item.favorite, p_media_url: url, p_title: title, p_is_animated: item.animated }
      if (useUbuntu) await fallbackApiJson('/rest/v1/rpc/secure_toggle_sticker_favorite', { method: 'POST', expectedAccountId: user.id, body: JSON.stringify(input) })
      else { const result = await supabase!.rpc('secure_toggle_sticker_favorite', input); if (result.error) throw result.error }
      if (owner.current === user.id) setItem({ ...item, favorite: !item.favorite })
    } catch { if (owner.current === user.id) setError('No se pudo guardar el cambio. Inténtalo de nuevo.') } finally { if (owner.current === user.id) setBusy(false) }
  }
  return <>
    <div role="button" tabIndex={0} className="message-sticker-trigger" aria-label={'Opciones del sticker ' + title}
      onClick={event => { if (!(event.target instanceof Element) || !event.target.closest('button')) setOpen(true) }}
      onKeyDown={event => { if (event.target === event.currentTarget && (event.key === 'Enter' || event.key === ' ')) { event.preventDefault(); setOpen(true) } }}>
      <ChatAttachmentMedia url={url} kind="image" title={title} imageClassName="sticker-preview" imageLink={false} preserveAnimation />
    </div>
    {open ? <PublicationDialog title="Sticker" busy={busy} onClose={() => setOpen(false)} className="message-sticker-dialog">
      <ChatAttachmentMedia url={url} kind="image" title={title} imageClassName="sticker-preview" imageLink={false} preserveAnimation />
      <div className="message-sticker-actions">
        <button className="ghost-btn" type="button" disabled={!item || busy} onClick={() => void toggle()}><Star size={18} fill={item?.favorite ? 'currentColor' : 'none'} />{item?.favorite ? 'Quitar de favoritos' : 'Añadir a favoritos'}</button>
        <button className="ghost-btn" type="button" onClick={() => { setOpen(false); onReply() }}><Reply size={18} />Responder</button>
        <button className="ghost-btn" type="button" onClick={() => { setOpen(false); onForward() }}><Forward size={18} />Reenviar</button>
        <MediaDownloadButton url={url} name={title} />
      </div>
      {error ? <p role="alert" className="error-text">{error}</p> : null}
    </PublicationDialog> : null}
  </>
}
