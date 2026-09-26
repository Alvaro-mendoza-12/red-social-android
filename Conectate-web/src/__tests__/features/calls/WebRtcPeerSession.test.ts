import { WebRtcPeerSession } from '../../../features/calls/WebRtcPeerSession'

type FakeConnection = RTCPeerConnection & {
  createOffer: jest.Mock
  createAnswer: jest.Mock
  setLocalDescription: jest.Mock
  setRemoteDescription: jest.Mock
  addIceCandidate: jest.Mock
  restartIce: jest.Mock
}

function connectionFixture(): FakeConnection {
  const connection = {
    signalingState: 'stable',
    connectionState: 'new',
    localDescription: null,
    remoteDescription: null,
    onnegotiationneeded: null,
    createOffer: jest.fn(async (options?: RTCOfferOptions) => ({ type: 'offer', sdp: options?.iceRestart ? 'restart' : 'offer' })),
    createAnswer: jest.fn(async () => ({ type: 'answer', sdp: 'answer' })),
    setLocalDescription: jest.fn(async (description: RTCSessionDescriptionInit) => {
      if (description.type === 'rollback') {
        connection.signalingState = 'stable'
        connection.localDescription = null
      } else {
        connection.localDescription = description
        connection.signalingState = description.type === 'offer' ? 'have-local-offer' : 'stable'
      }
    }),
    setRemoteDescription: jest.fn(async (description: RTCSessionDescriptionInit) => {
      connection.remoteDescription = description
      connection.signalingState = description.type === 'offer' ? 'have-remote-offer' : 'stable'
    }),
    addIceCandidate: jest.fn(async () => undefined),
    restartIce: jest.fn(),
  }
  return connection as unknown as FakeConnection
}

describe('WebRtcPeerSession', () => {
  afterEach(() => {
    jest.useRealTimers()
  })

  it('serializes negotiation and emits the local offer', async () => {
    const connection = connectionFixture()
    const sendOffer = jest.fn()
    const session = new WebRtcPeerSession(connection, { polite: false, sendOffer })

    await session.negotiate()

    expect(connection.createOffer).toHaveBeenCalledWith(undefined)
    expect(sendOffer).toHaveBeenCalledWith({ type: 'offer', sdp: 'offer' })
  })

  it('rolls back a colliding local offer for the polite peer', async () => {
    const connection = connectionFixture()
    connection.signalingState = 'have-local-offer'
    connection.localDescription = { type: 'offer', sdp: 'local' } as RTCSessionDescription
    const session = new WebRtcPeerSession(connection, { polite: true, sendOffer: jest.fn() })

    const answer = await session.receiveOffer({ type: 'offer', sdp: 'remote' })

    expect(connection.setLocalDescription).toHaveBeenNthCalledWith(1, { type: 'rollback' })
    expect(connection.setRemoteDescription).toHaveBeenCalledWith({ type: 'offer', sdp: 'remote' })
    expect(answer).toEqual({ type: 'answer', sdp: 'answer' })
  })

  it('ignores a colliding offer and its candidates for the impolite peer', async () => {
    const connection = connectionFixture()
    connection.signalingState = 'have-local-offer'
    const session = new WebRtcPeerSession(connection, { polite: false, sendOffer: jest.fn() })

    expect(await session.receiveOffer({ type: 'offer', sdp: 'remote' })).toBeNull()
    await session.addIceCandidate({ candidate: 'candidate' })

    expect(connection.setRemoteDescription).not.toHaveBeenCalled()
    expect(connection.addIceCandidate).not.toHaveBeenCalled()
  })

  it('restarts ICE with bounded exponential retries and resets when connected', async () => {
    jest.useFakeTimers()
    const connection = connectionFixture()
    connection.connectionState = 'failed'
    const states: string[] = []
    const session = new WebRtcPeerSession(connection, {
      polite: false,
      sendOffer: jest.fn(),
      maxRestartAttempts: 2,
      restartBaseDelayMs: 100,
      onRecoveryState: (state) => states.push(state),
    })

    session.handleConnectionState('failed')
    await jest.advanceTimersByTimeAsync(0)
    connection.signalingState = 'stable'
    await jest.advanceTimersByTimeAsync(100)

    expect(connection.restartIce).toHaveBeenCalledTimes(2)
    expect(states).toEqual(['recovering', 'recovering', 'exhausted'])

    connection.connectionState = 'connected'
    session.handleConnectionState('connected')
    expect(states.at(-1)).toBe('connected')
  })
})
