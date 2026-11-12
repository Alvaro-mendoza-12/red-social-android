import { useEffect, useRef, useState } from 'react'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import { friendlyError } from '../../core/utils'
import { StreamingImage } from '../../components/StreamingMedia'

export type CatalogGame = { id: string; name: string; imageUrl?: string; iconUrl?: string; coverUrl?: string; platforms: string[]; ranks: string[]; roles: string[] }
type CatalogPage = { items: CatalogGame[]; nextCursor?: string | null }

export function GameCatalog({ onSelect, disabled = false }: { onSelect: (game: CatalogGame) => void; disabled?: boolean }) {
  const [query, setQuery] = useState(''), [items, setItems] = useState<CatalogGame[]>([])
  const [cursor, setCursor] = useState<string | null>(null), [loading, setLoading] = useState(true)
  const [error, setError] = useState(''), [reload, setReload] = useState(0)
  const generation = useRef(0), moreRequest = useRef<AbortController | null>(null)

  async function search(searchQuery: string, signal: AbortSignal, nextCursor?: string) {
    const params = new URLSearchParams({ q: searchQuery.trim(), limit: '50' })
    if (nextCursor) params.set('cursor', nextCursor)
    return fallbackApiJson<CatalogPage>(`/v1/data/playmatch/games/search?${params}`, { signal })
  }

  useEffect(() => {
    const controller = new AbortController(), sequence = generation
    sequence.current++
    moreRequest.current?.abort()
    setItems([]); setCursor(null); setLoading(true); setError('')
    const timer = window.setTimeout(() => {
      void search(query, controller.signal).then(page => {
        if (!controller.signal.aborted) { setItems(page.items); setCursor(page.nextCursor || null) }
      }).catch(caught => {
        if (!controller.signal.aborted) setError(friendlyError(caught, 'No se pudo cargar el catálogo.'))
      }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    }, 250)
    return () => { clearTimeout(timer); controller.abort(); moreRequest.current?.abort(); sequence.current++ }
  }, [query, reload])

  async function more() {
    if (loading || !cursor) return
    const expected = generation.current, controller = new AbortController()
    moreRequest.current = controller; setLoading(true); setError('')
    try {
      const page = await search(query, controller.signal, cursor)
      if (expected === generation.current && !controller.signal.aborted) {
        setItems(current => [...new Map([...current, ...page.items].map(game => [game.id, game])).values()])
        setCursor(page.nextCursor || null)
      }
    } catch (caught) {
      if (expected === generation.current && !controller.signal.aborted) setError(friendlyError(caught, 'No se pudieron cargar más juegos.'))
    } finally { if (expected === generation.current && !controller.signal.aborted) setLoading(false) }
  }

  return <section className="games-catalog-section">
    <div className="row-between"><h3>Encuentra tu juego</h3><small role="status">{loading ? 'Buscando juegos…' : error ? 'Catálogo sin actualizar' : `${items.length} ${items.length === 1 ? 'juego mostrado' : 'juegos mostrados'}${cursor ? ' · Hay más resultados' : ''}`}</small></div>
    <input className="text-input" type="search" aria-label="Buscar juego en catálogo" placeholder="Buscar juego…" maxLength={100} value={query} onChange={event => setQuery(event.target.value)} />
    {error ? <p role="alert">{error} <button type="button" className="ghost-btn" disabled={loading} onClick={() => cursor ? void more() : setReload(value => value + 1)}>Reintentar</button></p> : null}
    <div className="games-catalog-grid" aria-busy={loading}>{items.map(game => <button className="games-catalog-card" type="button" key={game.id} disabled={disabled} onClick={() => onSelect(game)}>
      {game.imageUrl || game.coverUrl || game.iconUrl ? <StreamingImage src={game.imageUrl || game.coverUrl || game.iconUrl} alt="" /> : <span className="games-catalog-initials" aria-hidden="true">{game.name.slice(0, 2)}</span>}
      <strong>{game.name}</strong><small>{game.platforms?.join(' · ')}</small><span>Buscar jugadores →</span>
    </button>)}</div>
    {!loading && !error && !items.length ? <p>{query.trim() ? `No encontramos juegos para “${query.trim()}”. Prueba otro nombre.` : 'No hay juegos disponibles por ahora.'}</p> : null}
    {cursor ? <button className="ghost-btn" type="button" disabled={loading} onClick={() => void more()}>Ver más juegos</button> : null}
  </section>
}
