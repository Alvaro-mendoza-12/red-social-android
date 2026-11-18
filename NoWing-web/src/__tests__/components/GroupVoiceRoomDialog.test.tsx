import { StrictMode } from 'react'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { GroupVoiceRoomDialog } from '../../features/messages/GroupVoiceRoomDialog'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import { getStorageReadIdentity, updateStorageReadIdentity } from '../../core/media/storageReadSession'
import { loadGroupVoiceIntent } from '../../features/messages/groupVoiceDraft'
jest.mock('../../core/auth/fallbackAuth', () => ({ fallbackApiJson: jest.fn() }))
jest.mock('../../components/SafeAvatar', () => ({ SafeAvatar: () => <span aria-hidden="true" /> }))
const navigate = jest.fn()
jest.mock('react-router-dom', () => ({ useNavigate: () => navigate }))
const api = fallbackApiJson as jest.Mock
const roster = { groupId: 'g', groupName: 'Grupo', members: [
  { id: 'alice', name: 'Alicia', avatarUrl: '' }, { id: 'bob', name: 'Roberto', avatarUrl: '' },
] }
beforeEach(() => {
  localStorage.clear(); api.mockReset(); navigate.mockReset(); updateStorageReadIdentity('owner', 'fallback')
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
  Object.defineProperty(crypto, 'randomUUID', { configurable: true, value: () => '11111111-1111-4111-8111-111111111111' })
  api.mockImplementation((path: string) => Promise.resolve(path.endsWith('invitees') ? roster : { roomId: 'room', invitedCount: 2 }))
})
async function ready(onClose = jest.fn()) {
  const component = render(<GroupVoiceRoomDialog groupId="g" onClose={onClose} />)
  await screen.findByText('2 integrantes recibirán la invitación')
  await waitFor(() => expect(screen.getByRole('button', { name: /^(Crear sala de voz|Confirmar invitación pendiente)$/ })).toBeEnabled())
  return component
}
test('all uses the complete roster and sends an invitation, without microphone capture', async () => {
  await ready(); expect(screen.getByLabelText('Incluir a todos')).toBeChecked()
  fireEvent.click(screen.getByRole('button', { name: 'Crear sala de voz' }))
  await waitFor(() => expect(navigate).toHaveBeenCalledWith('/app/voice/room?join=1'))
  const options = api.mock.calls.find(([, options]) => options?.method === 'POST')[1]
  expect(JSON.parse(options.body)).toEqual({ requestId: expect.any(String), mode: 'all', recipientIds: ['alice', 'bob'] })
  expect(options.expectedIdentity).toBe(getStorageReadIdentity())
})
test('individual selection, select all and deselect all update the count and action', async () => {
  await ready(); fireEvent.click(screen.getByLabelText('Seleccionar integrantes'))
  expect(screen.getByRole('button', { name: 'Crear sala de voz' })).toBeDisabled()
  fireEvent.click(screen.getByLabelText('Alicia'))
  expect(screen.getByText('1 integrantes recibirán la invitación')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Seleccionar a todos' }))
  expect(screen.getByText('2 integrantes recibirán la invitación')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Deseleccionar a todos' }))
  expect(screen.getByRole('button', { name: 'Crear sala de voz' })).toBeDisabled()
  fireEvent.click(screen.getByLabelText('Roberto')); fireEvent.click(screen.getByRole('button', { name: 'Crear sala de voz' }))
  await waitFor(() => expect(navigate).toHaveBeenCalled())
  expect(JSON.parse(api.mock.calls.find(([, options]) => options?.method === 'POST')[1].body).recipientIds).toEqual(['bob'])
})
test('rapid clicks produce one command while its confirmation is pending', async () => {
  let confirm!: (value: unknown) => void
  api.mockImplementation((path: string) => path.endsWith('invitees') ? Promise.resolve(roster) : new Promise(resolve => { confirm = resolve }))
  await ready(); const button = screen.getByRole('button', { name: 'Crear sala de voz' })
  fireEvent.click(button); fireEvent.click(button)
  expect(api.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1)
  await act(async () => confirm({ roomId: 'room', invitedCount: 2 }))
})
test('a lost confirmation retains an immutable intent across remount and retries its same ID', async () => {
  api.mockImplementation((path: string) => path.endsWith('invitees') ? Promise.resolve(roster) : Promise.reject(new Error('offline')))
  const component = await ready(); fireEvent.click(screen.getByRole('button', { name: 'Crear sala de voz' }))
  await screen.findByRole('button', { name: 'Confirmar invitación pendiente' })
  const original = loadGroupVoiceIntent(getStorageReadIdentity(), 'g')
  component.unmount(); await ready()
  expect(screen.getByLabelText('Seleccionar integrantes')).toBeDisabled()
  api.mockResolvedValue({ roomId: 'room', invitedCount: 2 })
  fireEvent.click(screen.getByRole('button', { name: 'Confirmar invitación pendiente' }))
  await waitFor(() => expect(navigate).toHaveBeenCalled())
  const attempts = api.mock.calls.filter(([, options]) => options?.method === 'POST').map(([, options]) => JSON.parse(options.body))
  expect(attempts).toEqual([original, original])
})
test('cancelling never joins later when the server reply arrives', async () => {
  let confirm!: (value: unknown) => void
  api.mockImplementation((path: string) => path.endsWith('invitees') ? Promise.resolve(roster) : new Promise(resolve => { confirm = resolve }))
  const close = jest.fn(); const component = await ready(close)
  fireEvent.click(screen.getByRole('button', { name: 'Crear sala de voz' }))
  fireEvent.click(screen.getByRole('button', { name: 'Cancelar' })); expect(close).toHaveBeenCalled()
  component.unmount(); await act(async () => confirm({ roomId: 'room', invitedCount: 2 }))
  expect(navigate).not.toHaveBeenCalled()
})
test('account change dismisses the modal and rejects a late response', async () => {
  let confirm!: (value: unknown) => void
  api.mockImplementation((path: string) => path.endsWith('invitees') ? Promise.resolve(roster) : new Promise(resolve => { confirm = resolve }))
  const close = jest.fn(); await ready(close)
  fireEvent.click(screen.getByRole('button', { name: 'Crear sala de voz' }))
  act(() => updateStorageReadIdentity('different-account', 'fallback'))
  await act(async () => confirm({ roomId: 'room', invitedCount: 2 }))
  expect(close).toHaveBeenCalled(); expect(navigate).not.toHaveBeenCalled()
})
test('failed local durability prevents sending the invitation', async () => {
  await ready()
  const save = jest.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota') })
  try {
    fireEvent.click(screen.getByRole('button', { name: 'Crear sala de voz' }))
    await screen.findByRole('alert')
    expect(api.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(0)
  } finally { save.mockRestore() }
})
test('a failed roster cannot create an incomplete all invitation', async () => {
  api.mockRejectedValue(new Error('offline'))
  render(<GroupVoiceRoomDialog groupId="g" onClose={jest.fn()} />)
  await screen.findByRole('alert')
  expect(screen.getByRole('button', { name: 'Crear sala de voz' })).toBeDisabled()
})
test('unreadable pending intent is preserved and blocks another creation', async () => {
  localStorage.setItem('novvin:group-voice:owner/g', '{unreadable')
  render(<GroupVoiceRoomDialog groupId="g" onClose={jest.fn()} />)
  await screen.findByRole('alert')
  expect(screen.getByRole('button', { name: 'Crear sala de voz' })).toBeDisabled()
  expect(localStorage.getItem('novvin:group-voice:owner/g')).toBe('{unreadable')
  expect(api).not.toHaveBeenCalled()
})

test('StrictMode cleanup cannot turn a successful roster into a stale loading error', async () => {
  render(<StrictMode><GroupVoiceRoomDialog groupId="g" onClose={jest.fn()} /></StrictMode>)
  await waitFor(() => expect(screen.getByRole('button', { name: 'Crear sala de voz' })).toBeEnabled())
  expect(screen.queryByRole('alert')).toBeNull()
})
test('a stalled roster releases its loading state after the deadline', async () => {
  jest.useFakeTimers()
  api.mockImplementation(() => new Promise(() => {}))
  const component = render(<GroupVoiceRoomDialog groupId="g" onClose={jest.fn()} />)
  try {
    await act(async () => { jest.advanceTimersByTime(20001) })
    expect(screen.getByRole('alert')).toBeInTheDocument()
    expect(screen.queryByText('Cargando integrantes…')).toBeNull()
    expect(screen.getByRole('button', { name: 'Crear sala de voz' })).toBeDisabled()
  } finally { component.unmount(); jest.useRealTimers() }
})
test('a stalled confirmation unlocks retry and retains its durable request', async () => {
  const component = await ready()
  jest.useFakeTimers(); api.mockImplementation(() => new Promise(() => {}))
  try {
    fireEvent.click(screen.getByRole('button', { name: 'Crear sala de voz' }))
    await act(async () => { jest.advanceTimersByTime(20001) })
    expect(screen.getByRole('button', { name: 'Confirmar invitación pendiente' })).toBeEnabled()
    expect(screen.getByRole('alert')).toBeInTheDocument()
    expect(loadGroupVoiceIntent(getStorageReadIdentity(), 'g')).not.toBeNull()
  } finally { component.unmount(); jest.useRealTimers() }
})
