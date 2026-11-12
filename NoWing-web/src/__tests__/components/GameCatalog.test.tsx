import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { GamesPage } from '../../features/games/GamesPage'
import { MemoryRouter } from 'react-router-dom'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'

jest.mock('../../core/auth/fallbackAuth', () => ({ fallbackApiJson: jest.fn() }))
jest.mock('../../components/StreamingMedia', () => ({ StreamingImage: () => null }))
jest.mock('../../core/auth/AuthProvider', () => ({ useAuth: () => ({ user: { id: 'test' }, isFallbackSession: true }) }))
jest.mock('../../core/fallbackData', () => ({ uploadFallbackFile: jest.fn() }))
const request = jest.fn()
const game = (id: string, name: string) => ({ id, name, platforms: ['PC'], ranks: [], roles: [] })
beforeEach(() => {
  request.mockReset()
  ;(fallbackApiJson as jest.Mock).mockImplementation((url: string) => {
    if (url.includes('/catalog')) return Promise.resolve({ items: [game('first', 'Primer juego')] })
    if (url.includes('/games/search')) return request(url)
    if (url.endsWith('/capabilities')) return Promise.resolve({ capabilities: { enabled: true, photosEnabled: false } })
    if (url.endsWith('/profile')) return Promise.resolve({ profile: { displayName: 'Test', gamerBio: '', regionCode: '', languages: [], games: [], availability: [], photos: [], playStyle: 'casual', version: 1 } })
    return Promise.resolve({ items: [] })
  })
})

test('searches the server beyond the first page and distinguishes no matches from an empty catalog', async () => {
  request.mockImplementation(async (url: string) => url.includes('q=Zelda') ? { items: [game('zelda', 'Zelda')], nextCursor: null } : url.includes('q=missing') ? { items: [], nextCursor: null } : { items: [game('first', 'Primer juego')], nextCursor: 'page-two' })
  render(<MemoryRouter><GamesPage /></MemoryRouter>)
  await screen.findByRole('button', { name: /Primer juego/ })
  expect(screen.getByRole('status')).toHaveTextContent('1 juego mostrado')
  fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'Zelda' } })
  await screen.findByRole('button', { name: /Zelda/ })
  expect(screen.queryByText('Primer juego')).not.toBeInTheDocument()
  fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'missing' } })
  await screen.findByText(/No encontramos juegos/)
  expect(screen.getByRole('status')).toHaveTextContent('0 juegos mostrados')
  expect(screen.queryByText('No hay juegos disponibles por ahora.')).not.toBeInTheDocument()
})

test('loads more results using the server cursor and ignores a late old search', async () => {
  let finishOld!: (value: unknown) => void
  request.mockImplementation((url: string) => {
    if (url.includes('q=old')) return new Promise(resolve => { finishOld = resolve })
    if (url.includes('q=new')) return Promise.resolve({ items: [game('new', 'Nuevo juego')], nextCursor: null })
    return Promise.resolve(url.includes('cursor=page-two') ? { items: [game('second', 'Segundo juego')], nextCursor: null } : { items: [game('first', 'Primer juego')], nextCursor: 'page-two' })
  })
  render(<MemoryRouter><GamesPage /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button', { name: 'Ver más juegos' }))
  await screen.findByText('Segundo juego')
  expect(screen.getByRole('status')).toHaveTextContent('2 juegos mostrados')
  fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'old' } })
  await waitFor(() => expect(finishOld).toBeDefined())
  fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'new' } })
  await screen.findByText('Nuevo juego')
  await act(async () => finishOld({ items: [game('old', 'Resultado antiguo')], nextCursor: null }))
  expect(screen.queryByText('Resultado antiguo')).not.toBeInTheDocument()
})

test('a failed search provides retry without claiming there are no games', async () => {
  request.mockRejectedValueOnce(new Error('NETWORK_ERROR')).mockResolvedValue({ items: [game('one', 'Recuperado')], nextCursor: null })
  render(<MemoryRouter><GamesPage /></MemoryRouter>)
  await screen.findByRole('alert')
  expect(screen.queryByText(/No hay juegos/)).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Reintentar' }))
  await screen.findByText('Recuperado')
})

test('cannot switch to another game while a discovery request is pending', async () => {
  request.mockResolvedValue({ items: [game('first', 'Primer juego'), game('second', 'Segundo juego')], nextCursor: null })
  const original = (fallbackApiJson as jest.Mock).getMockImplementation()!
  let finish!: (value: unknown) => void
  ;(fallbackApiJson as jest.Mock).mockImplementation((url: string) => {
    if (url.includes('/players?') && url.includes('gameId=first')) return new Promise(resolve => { finish = resolve })
    return original(url)
  })
  render(<MemoryRouter><GamesPage /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button', { name: /Primer juego/ }))
  await waitFor(() => expect(finish).toBeDefined())
  fireEvent.click(screen.getByRole('button', { name: 'Juegos', exact: true }))
  expect(await screen.findByRole('button', { name: /Segundo juego/ })).toBeDisabled()
  await act(async () => finish({ items: [] }))
  expect(screen.getByRole('button', { name: /Segundo juego/ })).toBeEnabled()
})
