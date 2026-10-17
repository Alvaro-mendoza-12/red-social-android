export type PeerRecoveryState = 'connected' | 'recovering' | 'exhausted'

export type WebRtcPeerSessionOptions = {
  polite: boolean
  initialOfferer?: boolean
  sendOffer: (offer: RTCSessionDescriptionInit) => void | Promise<void>
  onRecoveryState?: (state: PeerRecoveryState, attempt: number) => void
  onError?: (error: unknown) => void
  maxRestartAttempts?: number
  disconnectedGraceMs?: number
  restartBaseDelayMs?: number
}

/**
 * Owns negotiation and bounded ICE recovery for one RTCPeerConnection.
 * Signaling transport stays outside this class so calls and voice rooms can
 * share the state machine without coupling media to Socket.IO.
 */
export class WebRtcPeerSession {
  private readonly connection: RTCPeerConnection
  private readonly options: Required<Pick<
    WebRtcPeerSessionOptions,
    'maxRestartAttempts' | 'disconnectedGraceMs' | 'restartBaseDelayMs'
  >> & WebRtcPeerSessionOptions
  private operation: Promise<unknown> = Promise.resolve()
  private makingOffer = false
  private ignoreOffer = false
  private restartAttempt = 0
  private restartTimer: ReturnType<typeof setTimeout> | null = null
  private disposed = false

  constructor(connection: RTCPeerConnection, options: WebRtcPeerSessionOptions) {
    this.connection = connection
    this.options = {
      maxRestartAttempts: options.maxRestartAttempts ?? 3,
      disconnectedGraceMs: options.disconnectedGraceMs ?? 2_000,
      restartBaseDelayMs: options.restartBaseDelayMs ?? 1_000,
      ...options,
    }
  }

  bindNegotiation(): void {
    this.connection.onnegotiationneeded = () => {
      // Android lets the agreed initiator start. Avoid an unsolicited initial
      // offer from the other peer while its local tracks are being attached.
      if (this.options.initialOfferer === false && !this.connection.remoteDescription && !this.connection.localDescription) return
      void this.negotiate().catch((error) => this.options.onError?.(error))
    }
  }

  async negotiate({ iceRestart = false }: { iceRestart?: boolean } = {}): Promise<RTCSessionDescriptionInit | null> {
    return this.enqueue(async () => {
      if (this.disposed || this.connection.signalingState !== 'stable') return null
      this.makingOffer = true
      try {
        if (iceRestart) this.connection.restartIce?.()
        const offer = await this.connection.createOffer(iceRestart ? { iceRestart: true } : undefined)
        if (this.connection.signalingState !== 'stable') return null
        await this.connection.setLocalDescription(offer)
        const localOffer = this.connection.localDescription ?? offer
        await this.options.sendOffer(localOffer)
        return localOffer
      } finally {
        this.makingOffer = false
      }
    })
  }

  async receiveOffer(offer: RTCSessionDescriptionInit): Promise<RTCSessionDescriptionInit | null> {
    return this.enqueue(async () => {
      if (this.disposed) return null
      const collision = this.makingOffer || this.connection.signalingState !== 'stable'
      this.ignoreOffer = !this.options.polite && collision
      if (this.ignoreOffer) return null

      if (collision && this.connection.signalingState !== 'stable') {
        await this.connection.setLocalDescription({ type: 'rollback' })
      }
      await this.connection.setRemoteDescription(offer)
      const answer = await this.connection.createAnswer()
      await this.connection.setLocalDescription(answer)
      return this.connection.localDescription ?? answer
    })
  }

  async receiveAnswer(answer: RTCSessionDescriptionInit): Promise<void> {
    await this.enqueue(async () => {
      if (this.disposed || this.connection.signalingState === 'stable') return
      await this.connection.setRemoteDescription(answer)
      this.ignoreOffer = false
    })
  }

  async addIceCandidate(candidate: RTCIceCandidateInit): Promise<void> {
    if (this.ignoreOffer || this.disposed) return
    await this.connection.addIceCandidate(candidate)
  }

  handleConnectionState(state: RTCPeerConnectionState): void {
    if (this.disposed) return
    if (state === 'connected') {
      this.clearRestartTimer()
      this.restartAttempt = 0
      this.options.onRecoveryState?.('connected', 0)
      return
    }
    if (state === 'closed') {
      this.dispose()
      return
    }
    if (state === 'failed') {
      this.scheduleRestart(0)
    } else if (state === 'disconnected') {
      this.scheduleRestart(this.options.disconnectedGraceMs)
    }
  }

  dispose(): void {
    this.disposed = true
    this.clearRestartTimer()
    this.connection.onnegotiationneeded = null
  }

  private enqueue<T>(task: () => Promise<T>): Promise<T> {
    const result = this.operation.then(task, task)
    this.operation = result.then(() => undefined, () => undefined)
    return result
  }

  private scheduleRestart(delayMs: number): void {
    if (this.restartTimer || this.restartAttempt >= this.options.maxRestartAttempts) return
    this.restartTimer = setTimeout(() => {
      this.restartTimer = null
      void this.restartIce().catch((error) => this.options.onError?.(error))
    }, delayMs)
  }

  private async restartIce(): Promise<void> {
    if (this.disposed || this.isConnectionRecovered()) return
    if (this.restartAttempt >= this.options.maxRestartAttempts) {
      this.options.onRecoveryState?.('exhausted', this.restartAttempt)
      return
    }

    this.restartAttempt += 1
    this.options.onRecoveryState?.('recovering', this.restartAttempt)
    try {
      await this.negotiate({ iceRestart: true })
    } catch (error) {
      this.options.onError?.(error)
    }

    if (this.isConnectionRecovered() || this.disposed) return
    if (this.restartAttempt >= this.options.maxRestartAttempts) {
      this.options.onRecoveryState?.('exhausted', this.restartAttempt)
      return
    }
    const delay = this.options.restartBaseDelayMs * (2 ** (this.restartAttempt - 1))
    this.scheduleRestart(delay)
  }

  private clearRestartTimer(): void {
    if (!this.restartTimer) return
    clearTimeout(this.restartTimer)
    this.restartTimer = null
  }

  private isConnectionRecovered(): boolean {
    return this.connection.connectionState === 'connected'
  }
}

export default WebRtcPeerSession
