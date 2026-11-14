import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { FollowsPage } from '../../features/social/FollowsPage'
import { loadFallbackFollows, setFallbackProfileFollow } from '../../core/fallbackData'
import { subscribeUbuntuRealtime } from '../../core/ubuntuRealtime'
let accountId = 'owner'
jest.mock('../../core/auth/AuthProvider', () => ({ useAuth: () => ({ user: { id: accountId }, isFallbackSession: true }) }))
jest.mock('../../core/supabase', () => ({ supabase: null }))
jest.mock('../../core/ubuntuBackend', () => ({ shouldUseUbuntuBackend: () => true }))
jest.mock('../../core/fallbackData', () => ({ loadFallbackFollows: jest.fn(), setFallbackProfileFollow: jest.fn() }))
jest.mock('../../core/ubuntuRealtime', () => ({ subscribeUbuntuRealtime: jest.fn(() => jest.fn()) }))
jest.mock('../../components/SafeAvatar', () => ({ SafeAvatar: ({ url, name }: { url: string; name: string }) => <img src={url} alt={name}/> }))
const load = jest.mocked(loadFallbackFollows), follow = jest.mocked(setFallbackProfileFollow)
const ana = { id: 'local-ana', supabase_user_id: 'public-ana', displayName: 'Ana Sol', username: 'anasol', avatar_url: 'https://example.invalid/ana.jpg', is_following: true }
function page(tab = 'followers') { return <MemoryRouter initialEntries={['/app/follows?tab=' + tab]}><FollowsPage/></MemoryRouter> }
beforeEach(() => { accountId = 'owner'; jest.clearAllMocks(); load.mockResolvedValue({ items: [ana] }); follow.mockResolvedValue({ ok: true, isFollowing: false }) })
test('renders the real avatar, display name, canonical profile id and searchable handle', async () => {
  render(page()); expect(await screen.findByRole('img', { name: 'Ana Sol' })).toHaveAttribute('src', ana.avatar_url)
  expect(screen.getByRole('link', { name: /Ana Sol/ })).toHaveAttribute('href', '/app/profile/public-ana')
  fireEvent.change(screen.getByRole('textbox', { name: 'Buscar en esta lista' }), { target: { value: 'nobody' } })
  expect(screen.queryByRole('link', { name: /Ana Sol/ })).not.toBeInTheDocument()
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'ANASOL' } })
  expect(screen.getByRole('link', { name: /Ana Sol/ })).toBeInTheDocument()
})
test('does not misrepresent a failed initial read as an empty followers list', async () => {
  load.mockRejectedValueOnce(new Error('offline')); render(page())
  fireEvent.click(await screen.findByRole('button', { name: 'Reintentar' }))
  expect(screen.queryByText('Aquí aparecerán tus seguidores.')).not.toBeInTheDocument()
  expect(await screen.findByText('Ana Sol')).toBeInTheDocument()
})
test('ignores an old tab read even when the transport resolves after abort', async () => {
  let resolve!: (value: { items: typeof ana[] }) => void
  load.mockImplementationOnce(() => new Promise(done => { resolve = done }))
  render(page()); fireEvent.click(screen.getByRole('button', { name: 'Siguiendo', exact: true }))
  await screen.findByText('Ana Sol')
  await act(async () => resolve({ items: [{ ...ana, displayName: 'Wrong old tab' }] }))
  expect(screen.queryByText('Wrong old tab')).not.toBeInTheDocument()
  expect(load.mock.calls[0][2]?.aborted).toBe(true)
})
test('a follow mutation is sent once, then reconciled with the server', async () => {
  let resolve!: (value: { ok: boolean; isFollowing: boolean }) => void
  follow.mockImplementationOnce(() => new Promise(done => { resolve = done }))
  render(page()); const action = await screen.findByRole('button', { name: 'Siguiendo a Ana Sol. Dejar de seguir' })
  fireEvent.click(action); fireEvent.click(action)
  expect(follow).toHaveBeenCalledTimes(1); expect(follow).toHaveBeenCalledWith('public-ana', false)
  expect(action).toBeDisabled(); load.mockResolvedValue({ items: [{ ...ana, is_following: false }] })
  await act(async () => resolve({ ok: true, isFollowing: false }))
  expect(await screen.findByRole('button', { name: 'Seguir a Ana Sol' })).toBeEnabled()
})
test('a burst of live follower changes triggers one refresh and preserves the displayed list', async () => {
  render(page()); await screen.findByText('Ana Sol'); jest.useFakeTimers()
  const subscription = jest.mocked(subscribeUbuntuRealtime).mock.calls[0][0]
  expect(subscription.channel).toBe('profile-counts:owner')
  await act(async () => {
    for (let i = 0; i < 10; i++) subscription.onEvent({ type: 'event', channel: subscription.channel, payload: {} })
    jest.advanceTimersByTime(200)
  })
  expect(load).toHaveBeenCalledTimes(2); expect(screen.getByText('Ana Sol')).toBeInTheDocument()
  jest.useRealTimers()
})
test('switching accounts discards the previous account response', async () => {
  let resolve!: (value: { items: typeof ana[] }) => void
  load.mockImplementationOnce(() => new Promise(done => { resolve = done }))
  const view = render(page()); accountId = 'other'; load.mockResolvedValue({ items: [] }); view.rerender(page())
  await waitFor(() => expect(load).toHaveBeenCalledTimes(2))
  await act(async () => resolve({ items: [ana] }))
  expect(screen.queryByText('Ana Sol')).not.toBeInTheDocument()
})
