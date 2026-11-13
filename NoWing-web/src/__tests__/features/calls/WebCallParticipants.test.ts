import WebCallManager, { type CallParticipant, type CallState } from '../../../features/calls/WebCallManager'
import { CallControlError } from '../../../features/calls/callControl'
const mockAccess = jest.fn()
const mockInvite = jest.fn()
jest.mock('socket.io-client', () => ({ io: jest.fn() }))
jest.mock('../../../core/runtimeBackend', () => ({ getRuntimeBackendUrl: () => 'https://synthetic.invalid' }))
jest.mock('../../../features/calls/callControl', () => ({
  ...jest.requireActual('../../../features/calls/callControl'),
  CallControlClient: jest.fn(() => ({ access: mockAccess, invite: mockInvite })),
}))
type Internal = { callState: CallState; wasAccepted: boolean; participants: Map<string, CallParticipant>; setupSocketListeners: (socket: unknown, id: string) => void }
function fixture() {
  const manager = new WebCallManager({ userId: 'owner', accessToken: 'synthetic', displayName: 'Owner' })
  const internal = manager as unknown as Internal
  internal.callState = { ...internal.callState, currentCallId: 'call', isOwner: true }
  internal.wasAccepted = true
  return { manager, internal }
}
beforeEach(() => jest.clearAllMocks())
test('an extra invite uses the existing call without restarting media or signaling', async () => {
  const { manager } = fixture()
  mockInvite.mockResolvedValue({ inviteeId: 'third', state: 'invited', participantLimit: 8 })
  const prepare = jest.spyOn(manager, 'initiateCall'), cleanup = jest.spyOn(manager, 'cleanup')
  await expect(manager.inviteParticipant('third')).resolves.toMatchObject({ inviteeId: 'third' })
  expect(mockInvite).toHaveBeenCalledWith('call', 'third')
  expect(prepare).not.toHaveBeenCalled(); expect(cleanup).not.toHaveBeenCalled()
})
test('only the connected owner can invite', async () => {
  const { manager, internal } = fixture()
  internal.callState.isOwner = false
  await expect(manager.inviteParticipant('third')).rejects.toThrow()
  internal.callState.isOwner = true; internal.wasAccepted = false
  await expect(manager.inviteParticipant('third')).rejects.toThrow()
  expect(mockInvite).not.toHaveBeenCalled()
})
test('declining an extra invitation or a participant leaving preserves a still-authorized call', async () => {
  const { manager } = fixture(), end = jest.spyOn(manager, 'endCall').mockImplementation(() => {})
  mockAccess.mockResolvedValue({ state: 'active' })
  await manager.receiveResponse('call', 'decline'); await manager.receiveResponse('call', 'end')
  expect(end).not.toHaveBeenCalled()
  mockAccess.mockRejectedValue(new CallControlError('CALL_ACCESS_UNAVAILABLE', 503))
  await manager.receiveResponse('call', 'cancel'); expect(end).not.toHaveBeenCalled()
  mockAccess.mockRejectedValue(new CallControlError('CALL_ACCESS_DENIED', 404))
  await manager.receiveResponse('call', 'end'); expect(end).toHaveBeenCalledWith('end')
})
test('a response for another call cannot end the current one', async () => {
  const { manager } = fixture()
  await manager.receiveResponse('old-call', 'end'); expect(mockAccess).not.toHaveBeenCalled()
})
test('removing one peer publishes the remaining participants and their streams', () => {
  const { manager, internal } = fixture(), events = new Map<string, (value: unknown) => void>()
  const first = { id: 'first', socketId: 'socket-1', name: 'First' } as CallParticipant
  const second = { id: 'second', socketId: 'socket-2', name: 'Second', stream: {} as MediaStream } as CallParticipant
  internal.participants.set('socket-1', first); internal.participants.set('socket-2', second)
  internal.callState.remoteParticipant = first
  const changed = jest.fn(); manager.onCallState(changed)
  internal.setupSocketListeners({ on: (name: string, handler: (value: unknown) => void) => events.set(name, handler) }, 'call')
  events.get('user-left')?.('socket-1')
  expect(changed).toHaveBeenLastCalledWith(expect.objectContaining({ currentCallId: 'call', participants: [second], remoteParticipant: second }))
})
test('renegotiation metadata preserves an existing stream and mute state', () => {
  const { manager, internal } = fixture()
  const participant = { id: 'peer', socketId: 'socket', name: 'Peer', stream: {} as MediaStream, isAudioEnabled: false, isVideoEnabled: false } as CallParticipant
  internal.participants.set('socket', participant)
  const details = manager as unknown as { peerConnections: Map<string, RTCPeerConnection>; registerParticipant: (value: { socketId: string; userId: string }) => void }
  details.peerConnections.set('socket', {} as RTCPeerConnection)
  const changed = jest.fn(); manager.onCallState(changed)
  details.registerParticipant({ socketId: 'socket', userId: 'peer' })
  expect(changed).toHaveBeenLastCalledWith(expect.objectContaining({ participants: [expect.objectContaining({ stream: participant.stream, isAudioEnabled: false, isVideoEnabled: false })] }))
})
