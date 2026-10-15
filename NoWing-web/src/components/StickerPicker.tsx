import { useEffect, useState } from 'react'
import { useAuth } from '../core/auth/AuthProvider'
import { fallbackApiJson } from '../core/auth/fallbackAuth'
import { supabase } from '../core/supabase'
import { textOf, type GenericRow } from '../core/utils'
import { ChatAttachmentMedia } from './ChatAttachmentMedia'

export type CatalogSticker = { stickerId: string; title: string; mediaUrl: string; isFavorite: boolean }

export function StickerPicker({ onSelect, onImport, onClose }: {
  onSelect: (item: CatalogSticker) => void
  onImport: (file: File | null) => void
  onClose: () => void
}) {
  const { user, isFallbackSession } = useAuth()
  const [query, setQuery] = useState('')
  const [items, setItems] = useState<CatalogSticker[]>([])
  const [loading, setLoading] = useState(true)
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    let active = true
    const timer = window.setTimeout(async () => {
      setLoading(true)
      setFailed(false)
      try {
        if (!user || (!supabase && !isFallbackSession)) throw new Error('Session unavailable')
        const input = { p_query: query.trim(), p_limit: 60 }
        const { data, error } = isFallbackSession
          ? { data: await fallbackApiJson<GenericRow[]>('/rest/v1/rpc/secure_search_stickers', { method: 'POST', body: JSON.stringify(input), expectedAccountId: user.id }), error: null }
          : await supabase!.rpc('secure_search_stickers', input)
        if (error) throw error
        if (active) setItems(((data ?? []) as GenericRow[]).map(row => ({
          stickerId: textOf(row, ['sticker_id', 'id'], ''),
          title: textOf(row, ['title', 'label', 'name'], 'Sticker'),
          mediaUrl: textOf(row, ['media_url', 'url', 'thumbnail_url'], ''),
          isFavorite: row.is_favorite === true,
        })).filter(item => item.stickerId && item.mediaUrl))
      } catch {
        if (active) { setItems([]); setFailed(true) }
      } finally { if (active) setLoading(false) }
    }, 200)
    return () => { active = false; window.clearTimeout(timer) }
  }, [query, user?.id, isFallbackSession])

  return <section className="stickers-tray" aria-label="Stickers">
    <div className="row-between">
      <input className="text-input" type="search" placeholder="Buscar stickers…" aria-label="Buscar stickers" value={query} onChange={event => setQuery(event.target.value)} />
      <label className="ghost-btn file-pick-btn" role="button" tabIndex={0} onKeyDown={event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); event.currentTarget.querySelector<HTMLInputElement>('input')?.click() } }}>Importar<input type="file" hidden accept="image/png,image/webp,image/gif,image/jpeg,.png,.webp,.gif,.jpg,.jpeg" onChange={event => { onImport(event.currentTarget.files?.[0] ?? null); event.currentTarget.value = '' }} /></label>
      <button className="icon-btn-sm" type="button" onClick={onClose} aria-label="Cerrar stickers">✕</button>
    </div>
    {loading ? <p className="sticker-empty" role="status">Cargando stickers…</p> : null}
    {!loading && !items.length ? <p className="sticker-empty">{failed ? 'No se pudieron cargar los stickers. Puedes importar uno de tu equipo.' : 'No hay stickers que coincidan. Puedes importar una imagen de tu equipo.'}</p> : null}
    <div className="sticker-grid-compact">{items.map(item => <button className="sticker-item-btn" key={item.stickerId} type="button" onClick={() => onSelect(item)} aria-label={`Enviar sticker ${item.title}`} title={item.title}><ChatAttachmentMedia url={item.mediaUrl} kind="image" title={item.title} imageLink={false} /></button>)}</div>
  </section>
}
