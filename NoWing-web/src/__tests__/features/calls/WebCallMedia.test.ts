import WebCallManager, { type CallState } from '../../../features/calls/WebCallManager'
jest.mock('socket.io-client', () => ({ io: jest.fn() }))
jest.mock('../../../core/runtimeBackend', () => ({ getRuntimeBackendUrl: () => 'https://synthetic.invalid' }))
jest.mock('../../../features/calls/callControl', () => ({ CallControlClient: jest.fn(() => ({ leave: jest.fn(async () => ({ left: true })) })) }))

class Track extends EventTarget {
  enabled = true; readyState = 'live'; onended: (() => void) | null = null
  stop = jest.fn(() => { this.readyState = 'ended' })
  constructor(public kind: string) { super() }
}
class Stream extends EventTarget {
  constructor(public tracks: Track[] = []) { super() }
  getTracks() { return [...this.tracks] }
  getVideoTracks() { return this.tracks.filter(track => track.kind === 'video') }
  getAudioTracks() { return this.tracks.filter(track => track.kind === 'audio') }
  addTrack(track: Track) { this.tracks.push(track) }
  removeTrack(track: Track) { this.tracks = this.tracks.filter(value => value !== track) }
}
type Internals = { localStream: Stream; screenTrack: Track | null; callState: CallState; socket: { emit: jest.Mock; disconnect: jest.Mock }; peerConnections: Map<string, RTCPeerConnection> }
const originalStream = globalThis.MediaStream
beforeAll(() => { Object.defineProperty(globalThis, 'MediaStream', { configurable: true, value: Stream }) })
afterAll(() => { Object.defineProperty(globalThis, 'MediaStream', { configurable: true, value: originalStream }) })
function fixture(video = true) {
  const manager = new WebCallManager({ userId: 'owned', accessToken: 'synthetic', displayName: 'QA' }), internal = manager as unknown as Internals
  const camera = new Track('video'), microphone = new Track('audio')
  internal.localStream = new Stream(video ? [microphone,camera] : [microphone])
  internal.callState = { ...internal.callState, currentCallId: 'test-call', isVideoEnabled: video }
  internal.socket = { emit: jest.fn(), disconnect: jest.fn() }
  const sender = { track: video ? camera : null, replaceTrack: jest.fn(async (track: Track | null) => { sender.track = track }) }
  const peer = { getTransceivers: () => [{ sender, receiver: { track: { kind: 'video' } } }], addTrack: jest.fn(), close: jest.fn() }
  internal.peerConnections.set('peer', peer as unknown as RTCPeerConnection)
  const previews: Stream[] = []
  manager.onLocalStream(value => { if (value) previews.push(value as unknown as Stream) })
  return { manager,internal,camera,sender,peer,previews }
}
test('previews the actual shared screen and restores the camera without a second video sender', async () => {
  const { manager,internal,camera,sender,peer,previews } = fixture(), screen = new Track('video')
  Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: { getDisplayMedia: jest.fn(async () => new Stream([screen])) } })
  await manager.toggleScreenShare()
  expect(sender.track).toBe(screen);expect(previews.at(-1)?.getVideoTracks()).toEqual([screen]);expect(camera.stop).not.toHaveBeenCalled()
  expect(internal.socket.emit).toHaveBeenCalledWith('video-state',expect.objectContaining({ isVideoEnabled:true }))
  await manager.toggleScreenShare()
  expect(sender.track).toBe(camera);expect(previews.at(-1)?.getVideoTracks()).toEqual([camera]);expect(screen.stop).toHaveBeenCalled();expect(peer.addTrack).not.toHaveBeenCalled()
})
test('turning on the camera during screen sharing keeps the screen on the negotiated sender', async () => {
  const { manager,sender,previews } = fixture(false), screen = new Track('video'), camera = new Track('video')
  Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: { getDisplayMedia: jest.fn(async () => new Stream([screen])), getUserMedia: jest.fn(async () => new Stream([camera])) } })
  await manager.toggleScreenShare();await manager.toggleVideo()
  expect(sender.track).toBe(screen);expect(previews.at(-1)?.getVideoTracks()).toEqual([screen])
  await manager.toggleScreenShare();expect(sender.track).toBe(camera)
})
test('a camera permission granted after hanging up is released and cannot reopen the call', async () => {
  const { manager,internal,previews } = fixture(false), late = new Track('video')
  let finish!: (stream: Stream) => void
  Object.defineProperty(navigator, 'mediaDevices', { configurable:true, value: { getUserMedia: jest.fn(() => new Promise<Stream>(resolve => { finish=resolve })) } })
  const switching = manager.toggleVideo();manager.endCall('user_hang_up');finish(new Stream([late]));await switching
  expect(late.stop).toHaveBeenCalled();expect(internal.callState.currentCallId).toBeNull();expect(previews).toHaveLength(0)
})
test('a failed screen replacement stops the new capture and retains the camera', async () => {
  const { manager,internal,camera,sender } = fixture(), screen = new Track('video')
  sender.replaceTrack.mockImplementationOnce(async () => { throw new Error('Device failed') })
  Object.defineProperty(navigator, 'mediaDevices', { configurable:true, value: { getDisplayMedia: jest.fn(async () => new Stream([screen])) } })
  await expect(manager.toggleScreenShare()).rejects.toThrow('Device failed')
  expect(screen.stop).toHaveBeenCalled();expect(camera.stop).not.toHaveBeenCalled();expect(internal.callState.isScreenSharing).toBe(false)
})

test('a new peer joining while screen sharing receives the screen rather than the camera', async () => {
  const {manager,camera}=fixture(),screen=new Track('video'),addTrack=jest.fn(),originalPeer=globalThis.RTCPeerConnection
  Object.defineProperty(navigator,'mediaDevices',{configurable:true,value:{getDisplayMedia:jest.fn(async()=>new Stream([screen]))}})
  Object.defineProperty(globalThis,'RTCPeerConnection',{configurable:true,value:jest.fn(()=>({addTrack}))})
  try {
    await manager.toggleScreenShare()
    ;(manager as unknown as {getOrCreatePeerConnection:(id:string)=>RTCPeerConnection}).getOrCreatePeerConnection('new-peer')
    expect(addTrack.mock.calls.some(([track])=>track===screen)).toBe(true)
    expect(addTrack.mock.calls.some(([track])=>track===camera)).toBe(false)
  } finally {Object.defineProperty(globalThis,'RTCPeerConnection',{configurable:true,value:originalPeer})}
})
