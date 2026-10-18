import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { LiveSessionPage } from '../../../features/live/LiveSessionPage'
import { fallbackApiJson } from '../../../core/auth/fallbackAuth'
import { subscribeUbuntuRealtime } from '../../../core/ubuntuRealtime'
import { sendFallbackLiveComment } from '../../../core/fallbackData'

let mockSfuAvailable = true
let mockAccount = 'host-a'
const mockDisposeMedia = jest.fn()
const mockStream = { id: 'live-a', host_user_id: 'host-a', host_name: 'Ana', is_active: true, allow_guest_requests: false, chat_paused: false }
let mockInvitations: Record<string, unknown>[] = []
const mockEmit = jest.fn()
jest.mock('../../../features/live/live.css', () => ({}))
jest.mock('../../../features/live/useLiveSfu', () => ({ useLiveSfu: () => ({ connected: true, error: '', hostStream: null, guests: [], retry: jest.fn() }) }))
jest.mock('../../../core/auth/AuthProvider', () => ({ useAuth: () => ({ user: { id: mockAccount, user_metadata: { display_name: 'Ana' } }, isFallbackSession: true }) }))
jest.mock('../../../core/auth/fallbackAuth', () => ({ ensureFallbackAccessToken: jest.fn(async () => 'synthetic'), fallbackApiJson: jest.fn() }))
jest.mock('../../../core/fallbackData', () => ({
  loadFallbackLiveStream: jest.fn(async () => ({stream: {...mockStream}})),
  loadFallbackLiveComments: jest.fn(async () => ({items: []})),
  sendFallbackLiveComment: jest.fn(),
  updateFallbackLiveState: jest.fn(async () => ({stream: {...mockStream}})),
}))
jest.mock('../../../core/publicProfileSummaries', () => ({ publicProfileSummaries: jest.fn(async () => new Map([['guest-a', { display_name: 'Luis' }]])) }))
jest.mock('../../../core/ubuntuRealtime', () => ({ subscribeUbuntuRealtime: jest.fn(() => jest.fn()) }))
jest.mock('../../../core/runtimeBackend', () => ({ getRuntimeBackendUrl: () => 'https://synthetic.invalid' }))
jest.mock('../../../components/SafeAvatar', () => ({ SafeAvatar: ({ name }: { name: string }) => <span>{name}</span> }))
jest.mock('../../../components/LiveStreamMedia', () => ({ LiveStreamVideo: () => <video/> }))
jest.mock('socket.io-client', () => ({ io: () => ({ connected: true, on: jest.fn(), connect: jest.fn(), disconnect: jest.fn(), emit: mockEmit }) }))
jest.mock('../../../features/voice/WebRTCManager', () => ({ __esModule: true, default: jest.fn(() => {
  let stream: MediaStream | null = null
  return { setLocalStream: (value: MediaStream) => {stream=value}, getLocalStream: () => stream, onTrack: jest.fn(), onConnectionState: jest.fn(), onIceCandidate: jest.fn(), onOfferNeeded: jest.fn(), disconnectAll: mockDisposeMedia, stopLocalStream: jest.fn() }
}) }))

beforeEach(() => {
  mockSfuAvailable = true; mockInvitations = []; mockEmit.mockClear()
  mockAccount = 'host-a'; mockDisposeMedia.mockClear()
  Object.defineProperty(crypto, 'randomUUID', { configurable: true, value: () => '11111111-1111-4111-8111-111111111111' })
  jest.mocked(fallbackApiJson).mockImplementation(async path => {
    if (String(path).endsWith('/capabilities')) return { sfuAvailable: mockSfuAvailable } as never
    if (String(path).endsWith('/invitations')) return { invitations: mockInvitations } as never
    return { moderation: { chat_paused: true } } as never
  })
  Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: { getUserMedia: jest.fn(async () => ({ getTracks: () => [], getAudioTracks: () => [], getVideoTracks: () => [] })) } })
})
function openHost() { return render(<MemoryRouter initialEntries={['/live/live-a?host=1']}><Routes><Route path="/live/:liveId" element={<LiveSessionPage/>}/></Routes></MemoryRouter>) }

test('notifies the host about pending requests without requiring settings to be open', async () => {
  mockInvitations = [{ id: 'request-a', invitee_user_id: 'guest-a', status: 'pending', initiated_by: 'viewer' }]
  openHost()
  await screen.findByText('Una persona solicita participar')
  expect(screen.queryByRole('dialog')).toBeNull()
  expect(subscribeUbuntuRealtime).toHaveBeenCalledWith(expect.objectContaining({ channel: 'live-collaboration:live-a' }))
  fireEvent.click(screen.getByRole('button', { name: 'Revisar' }))
  await screen.findAllByText('Luis')
  expect(screen.getByRole('button', { name: 'Aceptar' })).toBeEnabled()
})

test('shows host controls and chat even when guest video is not available', async () => {
  mockSfuAvailable = false
  openHost()
  await screen.findByText('Chat del directo')
  fireEvent.click(screen.getByRole('button', { name: 'Ajustes del directo' }))
  expect(screen.getByLabelText('Permitir solicitudes para participar')).toBeDisabled()
  expect(screen.getByLabelText('Permitir comentarios')).toBeEnabled()
})

test('turning off host video keeps the broadcast unpaused', async () => {
  openHost()
  fireEvent.click(await screen.findByRole('button', { name: 'Activar cámara y comenzar' }))
  await waitFor(() => expect(navigator.mediaDevices.getUserMedia).toHaveBeenCalled())
  await waitFor(() => expect(screen.queryByRole('button', { name: 'Activar cámara y comenzar' })).toBeNull())
  fireEvent.click(screen.getByRole('button', { name: 'Alternar video' }))
  expect(mockEmit).toHaveBeenCalledWith('update-live-state', expect.objectContaining({ videoEnabled: false, isPaused: false }))
  expect(screen.getByText('En vivo · Solo audio')).toBeInTheDocument()
})

test('shows a sent comment before HTTP completes and merges its realtime echo once', async () => {
  let finish!: (value: never) => void
  jest.mocked(sendFallbackLiveComment).mockImplementation(() => new Promise(resolve => { finish = resolve }))
  openHost()
  const input = await screen.findByRole('textbox', { name: 'Comentario para el directo' })
  fireEvent.change(input, { target: { value: 'Comentario inmediato' } })
  fireEvent.click(screen.getByRole('button', { name: 'Enviar comentario' }))
  expect(screen.getByText('Comentario inmediato')).toBeInTheDocument()
  const [, , id] = jest.mocked(sendFallbackLiveComment).mock.calls.at(-1)!
  expect(id).toMatch(/^[a-f0-9-]{36}$/i)
  const comment = { id, live_id: 'live-a', user_id: 'host-a', user_name: 'Ana', content: 'Comentario inmediato', created_at: new Date().toISOString() }
  const subscription = jest.mocked(subscribeUbuntuRealtime).mock.calls.findLast(([options]) => options.channel === 'live-comments:live-a')![0]
  act(() => { subscription.onEvent({ payload: { event: 'live_comment_created', comment } } as never) })
  expect(screen.getAllByText('Comentario inmediato')).toHaveLength(1)
  await act(async () => { finish({ comment } as never) })
  expect(screen.getAllByText('Comentario inmediato')).toHaveLength(1)
})

test('renders a received comment directly from realtime without another HTTP round trip', async () => {
  openHost()
  await screen.findByText('Chat del directo')
  const subscription = jest.mocked(subscribeUbuntuRealtime).mock.calls.findLast(([options]) => options.channel === 'live-comments:live-a')![0]
  act(() => { subscription.onEvent({ payload: { event: 'live_comment_created', comment: {
    id: 'incoming-a', live_id: 'live-a', user_id: 'guest-a', user_name: 'Luis', content: 'Desde el celular', created_at: new Date().toISOString(),
  } } } as never) })
  expect(screen.getByText('Desde el celular')).toBeInTheDocument()
})

test('a previous accepted invitation never reopens guest capture on a fresh visit', async () => {
  mockAccount = 'guest-a'
  mockInvitations = [{ id: 'old-invitation', invitee_user_id: 'guest-a', status: 'accepted' }]
  render(<MemoryRouter initialEntries={['/live/live-a']}><Routes><Route path="/live/:liveId" element={<LiveSessionPage/>}/></Routes></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button', { name: 'Ajustes del directo' }))
  await waitFor(() => expect(fallbackApiJson).toHaveBeenCalledWith(expect.stringContaining('/invitations'), expect.anything()))
  expect(screen.queryByText('Tu solicitud fue aceptada')).toBeNull()
  expect(navigator.mediaDevices.getUserMedia).not.toHaveBeenCalled()
})

test('navigating to messages and returning keeps the transport and host capture alive', async () => {
  function Session() {
    const location = useLocation(), navigate = useNavigate()
    return <><button onClick={() => navigate('/app/messages')}>Messages</button><button onClick={() => navigate('/app/live/live-a')}>Return</button><LiveSessionPage liveIdOverride="live-a" initialSearch="?host=1" minimized={location.pathname === '/app/messages'}/></>
  }
  render(<MemoryRouter initialEntries={['/app/live/live-a']}><Session/></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button', { name: 'Activar cámara y comenzar' }))
  await waitFor(() => expect(screen.queryByRole('button', { name: 'Activar cámara y comenzar' })).toBeNull())
  const before = mockDisposeMedia.mock.calls.length
  fireEvent.click(screen.getByText('Messages'))
  await screen.findByLabelText('Directo activo')
  expect(mockDisposeMedia).toHaveBeenCalledTimes(before)
  fireEvent.click(screen.getByText('Return'))
  expect(screen.queryByLabelText('Directo activo')).toBeNull()
  expect(mockDisposeMedia).toHaveBeenCalledTimes(before)
  expect(navigator.mediaDevices.getUserMedia).toHaveBeenCalledTimes(1)
})
