import { stickerTitle } from '../core/stickerTitle'
import { RefreshCw, Sparkles, Star } from 'lucide-react'
import { EmojiPicker } from './EmojiPicker'
import { useEffect, useRef, useState } from 'react'
import { useAuth } from '../core/auth/AuthProvider'
import { fallbackApiJson } from '../core/auth/fallbackAuth'
import { supabase } from '../core/supabase'
import { textOf, type GenericRow } from '../core/utils'
import { ChatAttachmentMedia } from './ChatAttachmentMedia'
import { shouldUseUbuntuBackend } from '../core/ubuntuBackend'

export type CatalogSticker = { stickerId: string; title: string; mediaUrl: string; isFavorite: boolean; isAnimated: boolean }

export function StickerPicker({ onSelect, onImport, onClose, onEmoji }: {
  onSelect: (item: CatalogSticker) => void
  onImport: (file: File | null) => void
  onClose: () => void
  onEmoji?: (emoji: string) => void
}) {
  const { user, isFallbackSession } = useAuth()
  const accountId = user?.id
  const useUbuntu = shouldUseUbuntuBackend(isFallbackSession)
  const owner = useRef(user?.id); owner.current = user?.id
  const [query, setQuery] = useState('')
  const [items, setItems] = useState<CatalogSticker[]>([])
  const [loading, setLoading] = useState(true)
  const [failed, setFailed] = useState(false)
  const [favoritesOnly, setFavoritesOnly] = useState(false)
  const [animatedOnly, setAnimatedOnly] = useState(false)
  const [refresh, setRefresh] = useState(0)
  const [saving, setSaving] = useState('')
  const [saveError, setSaveError] = useState('')
  const [emojiMode, setEmojiMode] = useState(false)
  useEffect(() => {
    let active = true
    setItems([]); setLoading(true); setSaving(''); setSaveError('')
    const timer = window.setTimeout(async () => {
      setLoading(true)
      setFailed(false)
      try {
        if (!accountId || (!supabase && !useUbuntu)) throw new Error('Session unavailable')
        const input = { p_query: query.trim(), p_limit: 250 }
        const { data, error } = useUbuntu
          ? { data: await fallbackApiJson<GenericRow[]>('/rest/v1/rpc/secure_search_stickers', { method: 'POST', body: JSON.stringify(input), expectedAccountId: accountId }), error: null }
          : await supabase!.rpc('secure_search_stickers', input)
        if (error) throw error
        if (active) setItems(((data ?? []) as GenericRow[]).map(row => ({
          stickerId: textOf(row, ['sticker_id', 'id'], ''),
          title: stickerTitle(textOf(row, ['title', 'label', 'name'], 'Sticker')),
          mediaUrl: textOf(row, ['media_url', 'url', 'thumbnail_url'], ''),
          isFavorite: row.is_favorite === true,
          isAnimated: row.is_animated === true || (typeof row.is_animated !== 'boolean' && /\.(gif|apng)(?:[?#]|$)/i.test(textOf(row, ['media_url', 'url'], ''))),
        })).filter(item => item.stickerId && item.mediaUrl))
      } catch {
        if (active) { setItems([]); setFailed(true) }
      } finally { if (active) setLoading(false) }
    }, 200)
    return () => { active = false; window.clearTimeout(timer) }
  }, [query, accountId, useUbuntu, refresh])

  async function favorite(item: CatalogSticker) {
    if (saving || !user) return
    const expectedOwner = user.id
    setSaving(item.stickerId); setSaveError('')
    try {
      const input = { p_sticker_id: item.stickerId, p_favorite: !item.isFavorite, p_media_url: item.mediaUrl, p_title: item.title, p_is_animated: item.isAnimated }
      if (useUbuntu) await fallbackApiJson('/rest/v1/rpc/secure_toggle_sticker_favorite', { method: 'POST', expectedAccountId: user.id, body: JSON.stringify(input) })
      else { const result = await supabase!.rpc('secure_toggle_sticker_favorite', input); if (result.error) throw result.error }
      if (owner.current !== expectedOwner) return
      setItems(current => current.map(value => value.stickerId === item.stickerId ? { ...value, isFavorite: !item.isFavorite } : value))
    } catch { if (owner.current === expectedOwner) setSaveError('No se pudo guardar el sticker. Inténtalo de nuevo.') } finally { if (owner.current === expectedOwner) setSaving('') }
  }

  if (emojiMode && onEmoji) return <section className="stickers-tray" aria-label="Emojis"><EmojiPicker onPick={onEmoji} onStickers={() => setEmojiMode(false)} onClose={onClose} /></section>
  return <section className="stickers-tray" aria-label="Stickers">
    <div className="row-between">
      <input className="text-input" type="search" placeholder="Buscar stickers…" aria-label="Buscar stickers" value={query} onChange={event => setQuery(event.target.value)} />
      <label className="ghost-btn file-pick-btn" role="button" tabIndex={0} onKeyDown={event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); event.currentTarget.querySelector<HTMLInputElement>('input')?.click() } }}>Importar<input type="file" hidden accept="image/png,image/webp,image/gif,image/jpeg,.png,.webp,.gif,.jpg,.jpeg" onChange={event => { onImport(event.currentTarget.files?.[0] ?? null); event.currentTarget.value = '' }} /></label>
      {onEmoji ? <button className="ghost-btn" type="button" onClick={() => setEmojiMode(true)}>Emojis</button> : null}
      <button className="icon-btn-sm" type="button" onClick={onClose} aria-label="Cerrar stickers">✕</button>
    </div>
    {loading ? <p className="sticker-empty" role="status">Cargando stickers…</p> : null}
    {!loading && !items.length ? <p className="sticker-empty">{failed ? 'No se pudieron cargar los stickers. Puedes importar uno de tu equipo.' : 'No hay stickers que coincidan. Puedes importar una imagen de tu equipo.'}</p> : null}
    <nav className="sticker-library-tabs" aria-label="Colección de stickers"><button className="chip-btn" type="button" aria-pressed={!favoritesOnly} onClick={() => setFavoritesOnly(false)}>Todos</button><button className="chip-btn" type="button" aria-pressed={favoritesOnly} onClick={() => setFavoritesOnly(true)}><Star size={14}/>Favoritos</button><button className="chip-btn" type="button" aria-pressed={animatedOnly} onClick={() => setAnimatedOnly(value => !value)}><Sparkles size={14}/>Animados</button><button className="chip-btn" type="button" disabled={loading || Boolean(saving)} aria-label="Actualizar stickers" onClick={() => setRefresh(value => value + 1)}><RefreshCw size={14}/>Actualizar</button></nav>
    {saveError ? <p role="alert">{saveError}</p> : null}
    {!loading && items.length > 0 && !items.some(item => (!favoritesOnly || item.isFavorite) && (!animatedOnly || item.isAnimated)) ? <p className="sticker-empty">{favoritesOnly ? 'Guarda stickers con la estrella para encontrarlos aquí y en el móvil.' : 'No hay stickers animados en esta búsqueda.'}</p> : null}
    <div className="sticker-grid-compact">{items.filter(item => (!favoritesOnly || item.isFavorite) && (!animatedOnly || item.isAnimated)).map(item => <div className="sticker-library-tile" key={item.stickerId}><button className="sticker-item-btn" type="button" onClick={() => onSelect(item)} aria-label={`Enviar sticker ${item.title}`} title={item.title}><ChatAttachmentMedia url={item.mediaUrl} kind="image" title={item.title} imageLink={false} preserveAnimation allowRetry={false} /></button><button className="sticker-save-button" type="button" disabled={Boolean(saving)} aria-pressed={item.isFavorite} aria-label={`${item.isFavorite ? 'Quitar de favoritos' : 'Guardar'} sticker ${item.title}`} onClick={() => void favorite(item)}><Star size={15} fill={item.isFavorite ? 'currentColor' : 'none'}/></button></div>)}</div>
  </section>
}
