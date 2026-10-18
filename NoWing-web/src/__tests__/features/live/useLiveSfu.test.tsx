import { act, renderHook, waitFor } from '@testing-library/react'
import { useLiveSfu } from '../../../features/live/useLiveSfu'

const mockParticipants = new Map<string, unknown>()
const mockEvents = new Map<string, () => void>()
const mockRoom = { remoteParticipants: mockParticipants, on: jest.fn((event, callback) => { mockEvents.set(event, callback); return mockRoom }), connect: jest.fn(async () => {}), disconnect: jest.fn(async () => {}), removeAllListeners: jest.fn() }
jest.mock('livekit-client', () => ({
  Room: jest.fn(() => mockRoom),
  RoomEvent: Object.fromEntries(['TrackSubscribed', 'TrackUnsubscribed', 'TrackMuted', 'TrackUnmuted', 'ParticipantConnected', 'ParticipantDisconnected', 'ParticipantPermissionsChanged', 'Reconnecting', 'Reconnected', 'Disconnected'].map(key => [key, key])),
  Track: { Kind: { Audio: 'audio' }, Source: {} },
}))
jest.mock('../../../core/auth/AuthProvider', () => ({ useAuth: () => ({ user: { id: 'account-a' } }) }))
jest.mock('../../../core/auth/fallbackAuth', () => ({ fallbackApiJson: jest.fn(async () => ({ wsUrl: 'wss://synthetic.invalid', token: 'synthetic' })) }))

beforeEach(() => {
  mockParticipants.clear(); mockEvents.clear()
  Object.defineProperty(globalThis, 'MediaStream', { configurable: true, value: class { getTracks() { return [] } } })
})
function open() { return renderHook(() => useLiveSfu({ liveId: 'live-a', hostId: 'host-a', role: 'viewer', enabled: true, localMedia: null, source: 'camera', micEnabled: false, videoEnabled: false })) }
function participant(identity: string, canPublish: boolean) { return { identity, name: identity, permissions: { canPublish }, trackPublications: new Map() } }

test('spectators never become stage tiles and accepted audio-only guests remain visible', async () => {
  mockParticipants.set('host-a', participant('host-a', true))
  mockParticipants.set('spectator-a', participant('spectator-a', false))
  mockParticipants.set('guest-a', participant('guest-a', true))
  const { result } = open()
  await waitFor(() => expect(result.current.connected).toBe(true))
  expect(result.current.guests.map(guest => guest.id)).toEqual(['guest-a'])
  expect(result.current.guests[0].videoEnabled).toBe(false)
  expect(result.current.hostStream).not.toBeNull()
})
test('a spectator is shown only after the server grants publication permission', async () => {
  const viewer = participant('viewer-a', false)
  mockParticipants.set(viewer.identity, viewer)
  const { result } = open()
  await waitFor(() => expect(result.current.connected).toBe(true))
  expect(result.current.guests).toHaveLength(0)
  act(() => { viewer.permissions.canPublish = true; mockEvents.get('ParticipantPermissionsChanged')!() })
  expect(result.current.guests.map(guest => guest.id)).toEqual(['viewer-a'])
})
