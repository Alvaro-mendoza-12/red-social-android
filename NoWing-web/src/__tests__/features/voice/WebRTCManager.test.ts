import { WebRTCManager } from '../../../features/voice/WebRTCManager'

class FakePeerConnection {
  signalingState: RTCSignalingState = 'stable'
  connectionState: RTCPeerConnectionState = 'new'
  localDescription: RTCSessionDescription | null = null
  remoteDescription: RTCSessionDescription | null = null
  onicecandidate: ((event: RTCPeerConnectionIceEvent) => void) | null = null
  ontrack: ((event: RTCTrackEvent) => void) | null = null
  onconnectionstatechange: (() => void) | null = null
  onnegotiationneeded: (() => void) | null = null
  createOffer = jest.fn(async (options?: RTCOfferOptions) => ({
    type: 'offer' as RTCSdpType,
    sdp: options?.iceRestart ? 'restart' : 'offer',
  }))
  createAnswer = jest.fn(async () => ({ type: 'answer' as RTCSdpType, sdp: 'answer' }))
  setLocalDescription = jest.fn(async (description: RTCSessionDescriptionInit) => {
    if (description.type === 'rollback') {
      this.localDescription = null
      this.signalingState = 'stable'
      return
    }
    this.localDescription = description as RTCSessionDescription
    this.signalingState = description.type === 'offer' ? 'have-local-offer' : 'stable'
  })
  setRemoteDescription = jest.fn(async (description: RTCSessionDescriptionInit) => {
    this.remoteDescription = description as RTCSessionDescription
    this.signalingState = description.type === 'offer' ? 'have-remote-offer' : 'stable'
  })
  addIceCandidate = jest.fn(async () => undefined)
  addTrack = jest.fn()
  getSenders = jest.fn(() => [])
  getTransceivers = jest.fn(() => [])
  addTransceiver = jest.fn()
  restartIce = jest.fn()
  close = jest.fn(() => { this.connectionState = 'closed' })
}

describe('voice WebRTCManager recovery integration', () => {
  const originalPeerConnection = globalThis.RTCPeerConnection

  beforeEach(() => {
    Object.defineProperty(globalThis, 'RTCPeerConnection', {
      configurable: true,
      value: jest.fn(() => new FakePeerConnection()),
    })
  })

  afterAll(() => {
    Object.defineProperty(globalThis, 'RTCPeerConnection', {
      configurable: true,
      value: originalPeerConnection,
    })
  })

  it('routes initial and recovery offers through the signaling callback', async () => {
    const manager = new WebRTCManager('local-user')
    const onOffer = jest.fn()
    manager.onOfferNeeded(onOffer)
    const connection = manager.createPeerConnection('peer-socket', true) as unknown as FakePeerConnection

    await manager.createOffer('peer-socket')

    expect(onOffer).toHaveBeenCalledWith('peer-socket', { type: 'offer', sdp: 'offer' })
    connection.signalingState = 'stable'
    connection.connectionState = 'failed'
    connection.onconnectionstatechange?.()
    await new Promise((resolve) => setTimeout(resolve, 10))
    expect(connection.restartIce).toHaveBeenCalled()
    expect(onOffer).toHaveBeenCalledWith('peer-socket', { type: 'offer', sdp: 'restart' })
    manager.disconnectAll()
  })

  it('answers an incoming offer through the shared perfect-negotiation session', async () => {
    const manager = new WebRTCManager('local-user')
    manager.createPeerConnection('peer-socket', false)

    const answer = await manager.createAnswer('peer-socket', { type: 'offer', sdp: 'remote' })

    expect(answer).toEqual({ type: 'answer', sdp: 'answer' })
    manager.disconnectAll()
  })

  it('releases microphone permission granted after the room has already closed', async () => {
    const manager = new WebRTCManager('local-user'), stop = jest.fn()
    let finish!: (stream: MediaStream) => void
    const originalDevices = navigator.mediaDevices, logged = jest.spyOn(console,'error').mockImplementation(() => {})
    Object.defineProperty(navigator,'mediaDevices',{configurable:true,value:{getUserMedia:jest.fn(() => new Promise<MediaStream>(resolve => { finish=resolve }))}})
    try {
      const pending = manager.initializeLocalMedia(true,false);manager.stopLocalStream()
      finish({getTracks:()=>[{stop}]} as unknown as MediaStream)
      await expect(pending).rejects.toThrow('No se pudo acceder')
      expect(stop).toHaveBeenCalled();expect(manager.getLocalStream()).toBeNull()
    } finally { logged.mockRestore();Object.defineProperty(navigator,'mediaDevices',{configurable:true,value:originalDevices}) }
  })
})
