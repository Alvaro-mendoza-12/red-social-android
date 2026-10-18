import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { LiveSessionPage } from '../../../features/live/LiveSessionPage'
import { fallbackApiJson } from '../../../core/auth/fallbackAuth'
import { subscribeUbuntuRealtime } from '../../../core/ubuntuRealtime'

let mockSfuAvailable = true
const mockStream = { id: 'live-a', host_user_id: 'host-a', host_name: 'Ana', is_active: true, allow_guest_requests: false, chat_paused: false }
let mockInvitations: Record<string, unknown>[] = []
const mockEmit = jest.fn()
jest.mock('../../../features/live/live.css', () => ({}))
jest.mock('../../../features/live/useLiveSfu', () => ({ useLiveSfu: () => ({ connected: true, error: '', hostStream: null, guests: [], retry: jest.fn() }) }))
jest.mock('../../../core/auth/AuthProvider', () => ({ useAuth: () => ({ user: { id: 'host-a', user_metadata: { display_name: 'Ana' } }, isFallbackSession: true }) }))
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
jest.mock('socket.io-client', () => ({ io: () => ({ on: jest.fn(), connect: jest.fn(), disconnect: jest.fn(), emit: mockEmit }) }))
jest.mock('../../../features/voice/WebRTCManager', () => ({ __esModule: true, default: jest.fn(() => {
  let stream: MediaStream | null = null
  return { setLocalStream: (value: MediaStream) => {stream=value}, getLocalStream: () => stream, onTrack: jest.fn(), onConnectionState: jest.fn(), onIceCandidate: jest.fn(), onOfferNeeded: jest.fn(), disconnectAll: jest.fn(), stopLocalStream: jest.fn() }
}) }))

beforeEach(() => {
  mockSfuAvailable = true; mockInvitations = []; mockEmit.mockClear()
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
