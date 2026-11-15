import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { randomUUID } from 'node:crypto'
import { MemoryRouter } from 'react-router-dom'
import { GamesPage } from '../../../features/games/GamesPage'
import { fallbackApiJson } from '../../../core/auth/fallbackAuth'

const game = { id: 'qa-game', name: 'Juego de prueba', platforms: ['PC'], ranks: [], roles: [] }
const profile = {
  userId: 'owner', displayName: 'Cuenta QA', playStyle: 'casual', languages: [], games: [], photos: [],
  availability: [], gamerBio: '', regionCode: '', adultConfirmed: true, discoverable: true,
}
const player = (userId: string, displayName: string) => ({
  userId, displayName, games: [], regionCode: 'CO', playStyle: 'casual', connected: false,
})

jest.mock('../../../core/auth/AuthProvider', () => ({
  useAuth: () => ({ user: { id: 'owner' }, isFallbackSession: true }),
}))
jest.mock('../../../core/auth/fallbackAuth', () => ({ fallbackApiJson: jest.fn() }))
jest.mock('../../../core/fallbackData', () => ({ uploadFallbackFile: jest.fn() }))
jest.mock('../../../components/StreamingMedia', () => ({ StreamingImage: () => null }))
jest.mock('../../../components/SafeAvatar', () => ({ SafeAvatar: () => null }))

const api = jest.mocked(fallbackApiJson)

function baseResponse(path: string) {
  if (path.endsWith('/capabilities')) return { capabilities: { enabled: true, photosEnabled: false } }
  if (path.endsWith('/profile')) return { profile }
  if (path.includes('/catalog?') || path.includes('/games/search?')) return { items: [game] }
  return { items: [] }
}

function showGames() {
  return render(<MemoryRouter><GamesPage /></MemoryRouter>)
}

function tab(name: string) {
  return within(screen.getByRole('navigation', { name: 'Secciones de juegos' })).getByRole('button', { name })
}

beforeEach(() => { localStorage.clear(); api.mockReset() })

test('shows the home without waiting for the profile and restores a saved game on return', async () => {
  api.mockImplementation(path => path.endsWith('/profile') ? new Promise(() => {}) : Promise.resolve(baseResponse(path)))
  const view = showGames()
  expect(await screen.findByRole('heading', { name: 'Encuentra tu equipo' })).toBeInTheDocument()
  expect(api.mock.calls.some(([path]) => path.includes('/players?'))).toBe(false)
  fireEvent.click(tab('Catálogo'))
  fireEvent.click(await screen.findByRole('button', { name: 'Guardar Juego de prueba' }))
  expect(screen.getByRole('button', { name: 'Quitar Juego de prueba de guardados' })).toHaveAttribute('aria-pressed', 'true')
  view.unmount()
  showGames()
  fireEvent.click(await screen.findByRole('button', { name: /Guardados/ }))
  expect(screen.getByRole('heading', { name: 'Juegos guardados' })).toBeInTheDocument()
  expect(screen.getByText(game.name, { exact: true })).toBeInTheDocument()
})

test('discards an earlier player lookup and paginates using the applied filters', async () => {
  let finishOld = () => {}
  const stale = new Promise(resolve => { finishOld = () => resolve({ items: [player('old', 'Resultado anterior')] }) })
  api.mockImplementation(path => {
    if (path.includes('/players?')) {
      const query = new URL(path, 'https://novvin.invalid').searchParams
      if (query.get('cursor')) return Promise.resolve({ items: [player('next', 'Jugador de la segunda página')] })
      if (query.get('q')) return Promise.resolve({ items: [player('fresh', 'Jugador actualizado')], nextCursor: 'cursor' })
      return stale
    }
    return Promise.resolve(baseResponse(path))
  })
  showGames()
  await screen.findByRole('heading', { name: 'Encuentra tu equipo' })
  fireEvent.click(tab('Jugadores'))
  fireEvent.change(screen.getByRole('searchbox', { name: 'Buscar jugador' }), { target: { value: 'Álvaro' } })
  fireEvent.change(screen.getByLabelText('Plataforma'), { target: { value: 'PC' } })
  fireEvent.click(screen.getByRole('button', { name: 'Buscar', exact: true }))
  expect(await screen.findByRole('heading', { name: 'Jugador actualizado' })).toBeInTheDocument()
  await act(async () => finishOld())
  expect(screen.queryByText('Resultado anterior')).not.toBeInTheDocument()
  fireEvent.change(screen.getByLabelText('Plataforma'), { target: { value: '' } })
  fireEvent.click(screen.getByRole('button', { name: 'Ver más jugadores' }))
  expect(await screen.findByRole('heading', { name: 'Jugador de la segunda página' })).toBeInTheDocument()
  expect(api).toHaveBeenCalledWith(expect.stringContaining('platform=PC&cursor=cursor'), expect.objectContaining({ expectedAccountId: 'owner' }))
})

test('keeps matchmaking active while another player has yet to confirm', async () => {
  Object.defineProperty(crypto, 'randomUUID', { configurable: true, value: randomUUID })
  const match = { id: 'match', status: 'PENDING', score: 90, player: player('team', 'Compañera de equipo') }
  api.mockImplementation((path, options) => {
    if (path.endsWith('/profile')) return Promise.resolve({ profile: {
      ...profile, games: [{ id: 'own-game', gameId: game.id, gameName: game.name, platform: 'PC' }],
    } })
    if (path.endsWith('/matchmaking/sessions') && options?.method === 'POST') {
      return Promise.resolve({ session: { id: 'session', status: 'MATCH_FOUND', match } })
    }
    if (path.endsWith('/matches/match/decision')) return Promise.resolve({ status: 'PENDING' })
    if (path.endsWith('/matches')) return Promise.resolve({ items: [match] })
    return Promise.resolve(baseResponse(path))
  })
  showGames()
  await screen.findByRole('heading', { name: 'Encuentra tu equipo' })
  fireEvent.click(tab('Partidas'))
  fireEvent.click(await screen.findByRole('button', { name: 'Buscar compañero' }))
  await screen.findByRole('button', { name: 'Cancelar búsqueda' })
  fireEvent.click(screen.getByRole('button', { name: 'Aceptar' }))
  expect(await screen.findByText('Esperando confirmación')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Cancelar búsqueda' })).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Buscar compañero' })).not.toBeInTheDocument()
})
