import { useCallback, useEffect, useRef, useState } from 'react'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import { friendlyError } from '../../core/utils'

export type PlayerFilters = {
  query: string
  gameId: string
  platform: string
  region: string
  availableNow: boolean
}

export const emptyPlayerFilters: PlayerFilters = { query: '', gameId: '', platform: '', region: '', availableNow: false }

export function playerSearchPath(filters: PlayerFilters, cursor?: string) {
  const params = new URLSearchParams({ limit: '20' })
  if (filters.query.trim()) params.set('q', filters.query.trim())
  if (filters.gameId) params.set('gameId', filters.gameId)
  if (filters.platform) params.set('platform', filters.platform)
  if (filters.region.trim()) params.set('region', filters.region.trim())
  if (filters.availableNow) params.set('availableNow', 'true')
  if (cursor) params.set('cursor', cursor)
  return `/v1/data/playmatch/players?${params}`
}

export function useGamePlayers<Player extends { userId: string }>(accountId: string) {
  const [players, setPlayers] = useState<Player[]>([])
  const [cursor, setCursor] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [applied, setApplied] = useState<PlayerFilters | null>(null)
  const operation = useRef<AbortController | null>(null)

  useEffect(() => () => operation.current?.abort(), [])

  const load = useCallback(async (filters: PlayerFilters, next?: string) => {
    if (next && operation.current) return
    operation.current?.abort()
    const controller = new AbortController()
    operation.current = controller
    setLoading(true)
    setError('')
    if (!next) {
      setApplied(filters)
      setCursor(null)
      setPlayers([])
    }
    try {
      const page = await fallbackApiJson<{ items: Player[]; nextCursor?: string | null }>(
        playerSearchPath(filters, next), { expectedAccountId: accountId, signal: controller.signal },
      )
      if (controller.signal.aborted) return
      setPlayers(current => [...new Map([...(next ? current : []), ...page.items].map(player => [player.userId, player])).values()])
      setCursor(page.nextCursor || null)
    } catch (caught) {
      if (!controller.signal.aborted) setError(friendlyError(caught, 'No se pudieron cargar los jugadores.'))
    } finally {
      if (operation.current === controller) {
        operation.current = null
        if (!controller.signal.aborted) setLoading(false)
      }
    }
  }, [accountId])

  return { players, cursor, loading, error, applied, load }
}

export type SavedGame = { id: string; name: string; platforms: string[] }
const libraryKey = (accountId: string) => `novvin:playmatch:saved-games:${accountId}`

export function readSavedGames(accountId: string): SavedGame[] {
  try {
    const rows: unknown = JSON.parse(localStorage.getItem(libraryKey(accountId)) || '[]')
    if (!Array.isArray(rows)) return []
    return rows.filter((game): game is SavedGame => (
      game && typeof game.id === 'string' && typeof game.name === 'string' && Array.isArray(game.platforms)
      && game.platforms.every((platform: unknown) => typeof platform === 'string')
    ))
  } catch {
    return []
  }
}

export function writeSavedGames(accountId: string, games: SavedGame[]) {
  try {
    localStorage.setItem(libraryKey(accountId), JSON.stringify(games))
    return true
  } catch {
    return false
  }
}

export function matchReadiness(profile: { adultConfirmed?: boolean; discoverable: boolean; games: unknown[] } | null, loading: boolean) {
  if (loading && !profile) return { ready: false, loading: true, message: 'Cargando tu perfil gamer…' }
  if (!profile) return { ready: false, loading: false, message: 'Abre tu perfil gamer para preparar la búsqueda.' }
  if (!profile.adultConfirmed) return { ready: false, loading: false, message: 'Confirma tu edad en el perfil gamer.' }
  if (!profile.games.length) return { ready: false, loading: false, message: 'Añade un juego y su plataforma.' }
  if (!profile.discoverable) return { ready: false, loading: false, message: 'Activa la visibilidad de tu perfil gamer.' }
  return { ready: true, loading: false, message: 'Tu perfil está preparado para buscar partida.' }
}
