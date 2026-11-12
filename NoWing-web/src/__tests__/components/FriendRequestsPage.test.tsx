import { randomUUID } from 'node:crypto'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { FriendRequestsPage } from '../../features/social/FriendRequestsPage'
import { loadFallbackFriends, cancelFallbackFriendRequest, updateFallbackFriendRequest } from '../../core/fallbackData'
import { updateStorageReadIdentity } from '../../core/media/storageReadSession'

let mockUser = { id: 'owner-a' }
jest.mock('../../core/auth/AuthProvider', () => ({ useAuth: () => ({ user: mockUser, isFallbackSession: true }) }))
jest.mock('../../core/auth/fallbackAuth', () => ({ isFallbackAuthConfigured: true }))
jest.mock('../../core/supabase', () => ({ supabase: null, isSupabaseConfigured: false }))
jest.mock('../../core/fallbackData', () => ({ loadFallbackFriends: jest.fn(), cancelFallbackFriendRequest: jest.fn(), updateFallbackFriendRequest: jest.fn() }))
jest.mock('../../core/publicProfileSummaries', () => ({ publicProfileSummaries: async () => new Map() }))
jest.mock('../../components/SafeAvatar', () => ({ SafeAvatar: () => null }))

function deferred() {
  let resolve!: (value?: unknown) => void
  let reject!: (reason: Error) => void
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const page = () => <MemoryRouter><FriendRequestsPage /></MemoryRouter>
beforeEach(() => {
  updateStorageReadIdentity('owner-a', 'fallback')
  jest.clearAllMocks()
  Object.defineProperty(globalThis.crypto, 'randomUUID', { configurable: true, value: randomUUID })
  mockUser = { id: 'owner-a' }
  jest.mocked(loadFallbackFriends).mockImplementation(async direction => ({ capabilities: { cancelOutgoingRequests: true }, items: [{
    id: `${mockUser.id}-${direction}`, sender_id: direction === 'incoming' ? 'sender' : mockUser.id,
    receiver_id: direction === 'incoming' ? mockUser.id : 'recipient', status: 'pending', created_at: '2026-11-10T12:00:00Z',
  }] }))
})

test('returning to the same UID starts a new action and old completion cannot unlock it', async () => {
  const first = deferred(), second = deferred()
  jest.mocked(cancelFallbackFriendRequest)
    .mockReturnValueOnce(first.promise as ReturnType<typeof cancelFallbackFriendRequest>)
    .mockReturnValueOnce(second.promise as ReturnType<typeof cancelFallbackFriendRequest>)
  const view = render(page())
  await screen.findByRole('button', { name: 'Aceptar' })
  fireEvent.click(screen.getByRole('button', { name: 'Enviadas' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Cancelar solicitud' }))
  updateStorageReadIdentity('owner-b', 'fallback')
  updateStorageReadIdentity('owner-a', 'fallback')
  mockUser = { id: 'owner-a' }
  view.rerender(page())
  const current = await screen.findByRole('button', { name: 'Cancelar solicitud' })
  expect(current).toBeEnabled()
  fireEvent.click(current)
  expect(cancelFallbackFriendRequest).toHaveBeenCalledTimes(2)
  await act(async () => first.reject(new Error('old-login-error')))
  expect(screen.queryByText('old-login-error')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Cancelar solicitud' })).toBeDisabled()
  await act(async () => second.resolve())
  expect(screen.getByRole('button', { name: 'Cancelar solicitud' })).toBeEnabled()
})

test('a late acceptance cannot replace the newly selected sent requests', async () => {
  const update = deferred()
  jest.mocked(updateFallbackFriendRequest).mockReturnValue(update.promise as ReturnType<typeof updateFallbackFriendRequest>)
  render(page())
  fireEvent.click(await screen.findByRole('button', { name: 'Aceptar' }))
  fireEvent.click(screen.getByRole('button', { name: 'Enviadas' }))
  expect(await screen.findByRole('link', { name: /Usuario de Novvin/ })).toHaveAttribute('href', '/app/profile/recipient')
  await act(async () => update.resolve())
  expect(screen.getByRole('button', { name: 'Enviadas' })).toHaveAttribute('aria-pressed', 'true')
  expect(screen.getByRole('link', { name: /Usuario de Novvin/ })).toHaveAttribute('href', '/app/profile/recipient')
})

test('a failed action from a previous account cannot show an error or block the next account', async () => {
  const update = deferred()
  jest.mocked(updateFallbackFriendRequest).mockReturnValue(update.promise as ReturnType<typeof updateFallbackFriendRequest>)
  const view = render(page())
  fireEvent.click(await screen.findByRole('button', { name: 'Aceptar' }))
  mockUser = { id: 'owner-b' }
  view.rerender(page())
  expect(await screen.findByRole('button', { name: 'Aceptar' })).toBeEnabled()
  await act(async () => update.reject(new Error('old-account-error')))
  expect(screen.queryByText('old-account-error')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Aceptar' })).toBeEnabled()
})

test('a previous completion cannot unlock a new action after changing accounts', async () => {
  const first = deferred(), second = deferred()
  jest.mocked(updateFallbackFriendRequest)
    .mockReturnValueOnce(first.promise as ReturnType<typeof updateFallbackFriendRequest>)
    .mockReturnValueOnce(second.promise as ReturnType<typeof updateFallbackFriendRequest>)
  const view = render(page())
  fireEvent.click(await screen.findByRole('button', { name: 'Aceptar' }))
  mockUser = { id: 'owner-b' }
  view.rerender(page())
  fireEvent.click(await screen.findByRole('button', { name: 'Aceptar' }))
  await act(async () => first.resolve())
  expect(screen.getByRole('button', { name: 'Aceptar' })).toBeDisabled()
  await act(async () => second.resolve())
  expect(screen.getByRole('button', { name: 'Aceptar' })).toBeEnabled()
})

test('a current action reports failure and permits retry without duplicate submissions', async () => {
  const update = deferred()
  jest.mocked(updateFallbackFriendRequest).mockReturnValue(update.promise as ReturnType<typeof updateFallbackFriendRequest>)
  render(page())
  const accept = await screen.findByRole('button', { name: 'Aceptar' })
  fireEvent.click(accept)
  fireEvent.click(accept)
  expect(updateFallbackFriendRequest).toHaveBeenCalledTimes(1)
  expect(updateFallbackFriendRequest).toHaveBeenCalledWith('owner-a-incoming', 'accepted')
  await act(async () => update.reject(new Error('current-request-error')))
  expect(screen.getByText('current-request-error')).toBeInTheDocument()
  expect(accept).toBeEnabled()
})

test('switching tabs and returning cannot submit a contradictory pending action', async () => {
  const update = deferred()
  jest.mocked(updateFallbackFriendRequest).mockReturnValue(update.promise as ReturnType<typeof updateFallbackFriendRequest>)
  render(page())
  fireEvent.click(await screen.findByRole('button', { name: 'Aceptar' }))
  fireEvent.click(screen.getByRole('button', { name: 'Enviadas' }))
  await screen.findByRole('link', { name: /Usuario de Novvin/ })
  fireEvent.click(screen.getByRole('button', { name: 'Recibidas' }))
  const reject = await screen.findByRole('button', { name: 'Rechazar' })
  expect(reject).toBeDisabled()
  fireEvent.click(reject)
  expect(updateFallbackFriendRequest).toHaveBeenCalledTimes(1)
  await act(async () => update.resolve())
  expect(screen.getByRole('button', { name: 'Aceptar' })).toBeEnabled()
})

test('unmounting discards a late completion without starting another load', async () => {
  const update = deferred()
  jest.mocked(updateFallbackFriendRequest).mockReturnValue(update.promise as ReturnType<typeof updateFallbackFriendRequest>)
  const view = render(page())
  fireEvent.click(await screen.findByRole('button', { name: 'Aceptar' }))
  const loads = jest.mocked(loadFallbackFriends).mock.calls.length
  view.unmount()
  await act(async () => update.resolve())
  expect(loadFallbackFriends).toHaveBeenCalledTimes(loads)
})

test('returning to an account retains its in-flight write lock', async () => {
  const update = deferred()
  jest.mocked(updateFallbackFriendRequest).mockReturnValue(update.promise as ReturnType<typeof updateFallbackFriendRequest>)
  const view = render(page())
  fireEvent.click(await screen.findByRole('button', { name: 'Aceptar' }))
  mockUser = { id: 'owner-b' }; view.rerender(page())
  expect(await screen.findByRole('button', { name: 'Aceptar' })).toBeEnabled()
  mockUser = { id: 'owner-a' }; view.rerender(page())
  expect(await screen.findByRole('button', { name: 'Aceptar' })).toBeDisabled()
  await act(async () => update.resolve())
  expect(screen.getByRole('button', { name: 'Aceptar' })).toBeEnabled()
})

test('completion refreshes the current tab before offering actions on an old pending row', async () => {
  const update = deferred()
  jest.mocked(updateFallbackFriendRequest).mockReturnValue(update.promise as ReturnType<typeof updateFallbackFriendRequest>)
  render(page())
  fireEvent.click(await screen.findByRole('button', { name: 'Aceptar' }))
  fireEvent.click(screen.getByRole('button', { name: 'Enviadas' }))
  await screen.findByRole('link', { name: /Usuario de Novvin/ })
  fireEvent.click(screen.getByRole('button', { name: 'Recibidas' }))
  await screen.findByRole('button', { name: 'Aceptar' })
  jest.mocked(loadFallbackFriends).mockResolvedValue({ items: [{ id: 'owner-a-incoming', sender_id: 'sender', receiver_id: 'owner-a', status: 'accepted' }] })
  await act(async () => update.resolve())
  expect(screen.getByText('Amigos')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Aceptar' })).not.toBeInTheDocument()
})

test('sent pending requests offer cancellation rather than requiring a profile detour', async () => {
  render(page())
  await screen.findByRole('button', { name: 'Aceptar' })
  fireEvent.click(screen.getByRole('button', { name: 'Enviadas' }))
  expect(await screen.findByRole('button', { name: 'Cancelar solicitud' })).toBeEnabled()
})

test.each([undefined, { cancelOutgoingRequests: false }])('a server without cancellation support does not expose the action (%j)', async capabilities => {
  jest.mocked(loadFallbackFriends).mockImplementation(async direction => ({ capabilities, items: [{
    id: 'old-server-request', sender_id: direction === 'incoming' ? 'sender' : mockUser.id,
    receiver_id: direction === 'incoming' ? mockUser.id : 'recipient', status: 'pending',
  }] }))
  render(page())
  await screen.findByRole('button', { name: 'Aceptar' })
  fireEvent.click(screen.getByRole('button', { name: 'Enviadas' }))
  await screen.findByRole('link', { name: /Usuario de Novvin/ })
  expect(screen.queryByRole('button', { name: 'Cancelar solicitud' })).not.toBeInTheDocument()
  expect(cancelFallbackFriendRequest).not.toHaveBeenCalled()
})

test('cancellation is locked across tabs and a failure keeps the request retryable', async () => {
  const cancellation = deferred()
  jest.mocked(cancelFallbackFriendRequest).mockReturnValue(cancellation.promise as ReturnType<typeof cancelFallbackFriendRequest>)
  render(page())
  await screen.findByRole('button', { name: 'Aceptar' })
  fireEvent.click(screen.getByRole('button', { name: 'Enviadas' }))
  const cancel = await screen.findByRole('button', { name: 'Cancelar solicitud' })
  fireEvent.click(cancel); fireEvent.click(cancel)
  expect(cancelFallbackFriendRequest).toHaveBeenCalledTimes(1)
  expect(cancelFallbackFriendRequest).toHaveBeenCalledWith('owner-a-outgoing')
  fireEvent.click(screen.getByRole('button', { name: 'Recibidas' }))
  expect(await screen.findByRole('button', { name: 'Aceptar' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: 'Enviadas' }))
  expect(await screen.findByRole('button', { name: 'Cancelar solicitud' })).toBeDisabled()
  await act(async () => cancellation.reject(new Error('stale-tab-cancel-error')))
  expect(screen.queryByText('stale-tab-cancel-error')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Cancelar solicitud' })).toBeEnabled()
})

test('successful cancellation reloads sent requests', async () => {
  jest.mocked(cancelFallbackFriendRequest).mockImplementation(async () => {
    jest.mocked(loadFallbackFriends).mockResolvedValue({items:[]})
    return {request:{id:'owner-a-outgoing'}}
  })
  render(page())
  await screen.findByRole('button', { name: 'Aceptar' })
  fireEvent.click(screen.getByRole('button', { name: 'Enviadas' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Cancelar solicitud' }))
  expect(await screen.findByText('Sin solicitudes enviadas')).toBeInTheDocument()
  expect(screen.queryByRole('button', {name:'Cancelar solicitud'})).not.toBeInTheDocument()
})

test.each(['accepted','rejected'])('sent %s requests do not offer cancellation', async status => {
  jest.mocked(loadFallbackFriends).mockResolvedValue({items:[{id:'resolved',sender_id:'owner-a',receiver_id:'recipient',status}]})
  render(page())
  await screen.findByRole('link', {name:/Usuario de Novvin/})
  fireEvent.click(screen.getByRole('button', {name:'Enviadas'}))
  await screen.findByRole('link', {name:/Usuario de Novvin/})
  expect(screen.queryByRole('button', {name:'Cancelar solicitud'})).not.toBeInTheDocument()
})
