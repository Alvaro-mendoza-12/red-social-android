import { io, type Socket } from 'socket.io-client'
import { buildSignalingAffinity } from '../../core/signalingAffinity'
import { WebRtcPeerSession } from './WebRtcPeerSession'
import { CallControlClient, type CallLeaveAction } from './callControl'
import { replacePeerTracks } from '../../core/replacePeerTracks'

export interface CallParticipant {
  id: string
  name: string
  avatarUrl: string
  isAudioEnabled: boolean
  isVideoEnabled: boolean
  isSpeaking: boolean
  socketId?: string
  stream?: MediaStream
}

export interface CallState {
  isIncomingCall: boolean
  isOutgoingCall: boolean
  currentCallId: string | null
  remoteParticipant: CallParticipant | null
  duration: number
  isMuted: boolean
  isVideoEnabled: boolean
  isScreenSharing: boolean
  connectionLabel?: string
}

export type CallEndEvent = {
  callId: string
  remoteUserId: string
  action: CallLeaveAction
  notifyRemote: boolean
  persisted: Promise<boolean>
}

type WebCallManagerOptions = {
  userId: string
  accessToken: string
  displayName: string
  avatarUrl?: string
  signalingBaseUrl?: string
}

type CallSocket = Socket<{
  'current-participants': (participants: SignalParticipant[]) => void
  'user-joined': (participant: SignalParticipant) => void
  'user-left': (socketId: string) => void
  offer: (payload: OfferPayload) => void
  answer: (payload: AnswerPayload) => void
  'ice-candidate': (payload: IcePayload) => void
  'mute-state': (payload: { userId: string; isMuted: boolean }) => void
  'video-state': (payload: { userId: string; isVideoEnabled: boolean }) => void
  'auth-error': (payload: { error?: string }) => void
  'call-joined': (payload: { callId: string }) => void
  'call-join-error': (payload: { callId?: string; error?: string }) => void
  'call-access-revoked': (payload: { callId?: string; error?: string }) => void
  'call-session-replaced': (payload: { callId?: string }) => void
}, {
  'join-call': (payload: { callId: string; userId: string; name: string; avatarUrl: string }) => void
  offer: (payload: { target: string; offer: RTCSessionDescriptionInit; userId: string; name: string }) => void
  answer: (payload: { target: string; answer: RTCSessionDescriptionInit; userId: string; name: string }) => void
  'ice-candidate': (payload: { target: string; candidate: RTCIceCandidateInit; userId: string; name: string }) => void
  'mute-state': (payload: { callId: string; userId: string; isMuted: boolean }) => void
  'video-state': (payload: { callId: string; userId: string; isVideoEnabled: boolean }) => void
}>

type SignalParticipant = {
  socketId: string
  userId: string
  name?: string
  avatarUrl?: string
  videoEnabled?: boolean
}

type OfferPayload = {
  sender: string
  userId?: string
  name?: string
  offer: RTCSessionDescriptionInit
}

type AnswerPayload = {
  sender: string
  userId?: string
  answer: RTCSessionDescriptionInit
}

type IcePayload = {
  sender: string
  userId?: string
  candidate: RTCIceCandidateInit
}

import { getRuntimeBackendUrl } from '../../core/runtimeBackend'

function normalizeBaseUrl(value?: string): string {
  const clean = String(value ?? '').trim().replace(/\/+$/, '')
  return clean || getRuntimeBackendUrl()
}

function toCallNamespaceUrl(value?: string): string {
  const base = normalizeBaseUrl(value)
  return base.endsWith('/call') ? base : `${base}/call`
}

function toTurnCredentialsUrl(value?: string): string {
  const base = normalizeBaseUrl(value).replace(/\/call$/, '')
  return `${base}/turn-credentials`
}

function isConnectedState(state: RTCPeerConnectionState): boolean {
  return state === 'connected'
}

export class WebCallManager {
  private readonly userId: string
  private readonly accessToken: string
  private readonly displayName: string
  private readonly avatarUrl: string
  private readonly signalingBaseUrl?: string
  private readonly callControl: CallControlClient
  private controlRequest: Promise<unknown> = Promise.resolve()
  private pendingLeave: Promise<void> = Promise.resolve()
  private ringTimeout: ReturnType<typeof setTimeout> | null = null
  private joinRetryTimer: ReturnType<typeof setTimeout> | null = null
  private joinRetryDeadline: ReturnType<typeof setTimeout> | null = null
  private pendingJoinCancel: (() => void) | null = null
  private disposed = false
  private wasAccepted = false
  private socket: CallSocket | null = null
  private ownSocketId = ''
  private localStream: MediaStream | null = null
  private screenTrack: MediaStreamTrack | null = null
  private mediaBusy = false
  private mediaRevision = 0
  private peerConnections = new Map<string, RTCPeerConnection>()
  private peerSessions = new Map<string, WebRtcPeerSession>()
  private participants = new Map<string, CallParticipant>()
  private pendingCandidates = new Map<string, RTCIceCandidateInit[]>()
  private iceServers: RTCIceServer[] = [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'stun:stun1.l.google.com:19302' },
  ]
  private callState: CallState = {
    isIncomingCall: false,
    isOutgoingCall: false,
    currentCallId: null,
    remoteParticipant: null,
    duration: 0,
    isMuted: false,
    isVideoEnabled: true,
    isScreenSharing: false,
    connectionLabel: 'Listo',
  }
  private callStartTime: number | null = null
  private durationInterval: ReturnType<typeof setInterval> | null = null
  private onCallStateCallbacks: Array<(state: CallState) => void> = []
  private onRemoteStreamCallbacks: Array<(stream: MediaStream, participant: CallParticipant) => void> = []
  private onLocalStreamCallbacks: Array<(stream: MediaStream | null) => void> = []
  private onCallEndCallbacks: Array<(reason: string, event: CallEndEvent) => void> = []
  private ended = false

  constructor(options: WebCallManagerOptions) {
    this.userId = options.userId
    this.accessToken = options.accessToken
    this.callControl = new CallControlClient(options.accessToken)
    this.displayName = options.displayName
    this.avatarUrl = options.avatarUrl ?? ''
    this.signalingBaseUrl = options.signalingBaseUrl
  }

  async initiateCall(
    callId: string,
    remoteUserId: string,
    participantInfo: { name: string; avatarUrl: string; videoEnabled: boolean; contextId: string },
  ): Promise<void> {
    if (this.disposed) throw new Error('La pantalla de llamada ya se cerró.')
    if (this.callState.currentCallId) {
      throw new Error('Ya estás en una llamada')
    }

    this.ended = false
    this.wasAccepted = false
    const participant: CallParticipant = {
      id: remoteUserId,
      name: participantInfo.name || `Usuario ${remoteUserId}`,
      avatarUrl: participantInfo.avatarUrl,
      isAudioEnabled: true,
      isVideoEnabled: participantInfo.videoEnabled,
      isSpeaking: false,
    }

    this.callState = {
      ...this.callState,
      isOutgoingCall: true,
      isIncomingCall: false,
      currentCallId: callId,
      remoteParticipant: participant,
      isVideoEnabled: participantInfo.videoEnabled,
      connectionLabel: 'Llamando...',
    }
    this.notifyCallStateChange()

    try {
      await this.pendingLeave
      this.ensureCallStillCurrent(callId)
      const preparing = this.callControl.prepare(callId, participantInfo.contextId, [remoteUserId])
      this.controlRequest = preparing
      const permission = await preparing
      this.ensureCallStillCurrent(callId)
      const remaining = new Date(permission.expiresAt).getTime() - Date.now()
      this.ringTimeout = setTimeout(() => this.endCall('timeout'), Math.max(0, remaining))
      await this.initializeLocalMedia(true, participantInfo.videoEnabled, callId)
      this.ensureCallStillCurrent(callId)
      await this.connectSocket(callId)
    } catch (error) {
      if (this.callState.currentCallId === callId) this.endCall('start_failed')
      throw error
    }
  }

  async joinExistingCall(
    callId: string,
    participantInfo: { id: string; name: string; avatarUrl?: string; videoEnabled: boolean },
  ): Promise<void> {
    if (this.disposed) throw new Error('La pantalla de llamada ya se cerró.')
    if (this.callState.currentCallId) {
      throw new Error('Ya estás en una llamada')
    }

    this.ended = false
    this.wasAccepted = false
    this.callState = {
      ...this.callState,
      isOutgoingCall: false,
      isIncomingCall: false,
      currentCallId: callId,
      remoteParticipant: {
        id: participantInfo.id,
        name: participantInfo.name || 'Usuario',
        avatarUrl: participantInfo.avatarUrl ?? '',
        isAudioEnabled: true,
        isVideoEnabled: participantInfo.videoEnabled,
        isSpeaking: false,
      },
      isVideoEnabled: participantInfo.videoEnabled,
      connectionLabel: 'Conectando...',
    }
    this.notifyCallStateChange()

    try {
      await this.pendingLeave
      this.ensureCallStillCurrent(callId)
      const accepting = this.callControl.accept(callId)
      this.controlRequest = accepting
      await accepting
      this.ensureCallStillCurrent(callId)
      this.wasAccepted = true
      await this.initializeLocalMedia(true, participantInfo.videoEnabled, callId)
      this.ensureCallStillCurrent(callId)
      await this.connectSocket(callId)
    } catch (error) {
      if (this.callState.currentCallId === callId) this.endCall('start_failed')
      throw error
    }
  }

  private ensureCallStillCurrent(callId: string): void {
    if (this.ended || this.callState.currentCallId !== callId) throw new Error('La llamada terminó antes de conectar.')
  }

  markAccepted(): void {
    if (this.ended || !this.callState.currentCallId) return
    this.wasAccepted = true
    if (this.ringTimeout) clearTimeout(this.ringTimeout)
    this.ringTimeout = null
    this.callState = { ...this.callState, isOutgoingCall: false }
    this.notifyCallStateChange()
  }

  rejectCall(): void {
    this.endCall('rejected')
  }

  endCall(reason = 'local_hang_up'): void {
    if (this.ended && reason !== 'cleanup') return
    this.ended = true
    const callId = this.callState.currentCallId ?? ''
    const remoteUserId = this.callState.remoteParticipant?.id ?? ''
    const action: CallLeaveAction = reason === 'timeout' ? 'missed'
      : reason === 'rejected' ? 'decline'
      : this.callState.isOutgoingCall && !this.wasAccepted ? 'cancel' : 'end'
    const replaced = reason === 'session_replaced'
    const notifyRemote = !replaced && !['decline', 'cancel', 'end', 'busy', 'missed', 'collision', 'access_denied', 'auth_error'].includes(reason)
    const persisted = !callId || replaced ? Promise.resolve(false)
      : this.controlRequest.catch(() => undefined)
        .then(() => this.callControl.leave(callId, action))
        .then((result) => result.left === true)
        .catch(() => false)
    if (callId && !replaced) this.pendingLeave = persisted.then(() => undefined)
    if (this.ringTimeout) clearTimeout(this.ringTimeout)
    this.ringTimeout = null
    this.clearJoinRetry()
    this.pendingJoinCancel?.()

    if (this.durationInterval) {
      clearInterval(this.durationInterval)
      this.durationInterval = null
    }

    this.peerConnections.forEach((connection) => connection.close())
    this.peerConnections.clear()
    this.peerSessions.forEach((session) => session.dispose())
    this.peerSessions.clear()
    this.pendingCandidates.clear()
    this.participants.clear()

    this.socket?.disconnect()
    this.socket = null
    this.ownSocketId = ''
    this.mediaRevision++
    if (this.screenTrack) this.screenTrack.onended = null
    this.screenTrack?.stop()
    this.screenTrack = null
    this.stopLocalStream()

    this.callState = {
      isIncomingCall: false,
      isOutgoingCall: false,
      currentCallId: null,
      remoteParticipant: null,
      duration: 0,
      isMuted: false,
      isVideoEnabled: true,
      isScreenSharing: false,
      connectionLabel: 'Finalizada',
    }
    this.callStartTime = null

    this.onCallEndCallbacks.forEach((callback) => callback(reason, { callId, remoteUserId, action, notifyRemote, persisted }))
    this.notifyCallStateChange()
  }

  toggleMute(): void {
    if (!this.localStream || !this.callState.currentCallId) return
    const callId = this.callState.currentCallId

    const nextMuted = !this.callState.isMuted
    this.localStream.getAudioTracks().forEach((track) => {
      track.enabled = !nextMuted
    })
    this.callState = {
      ...this.callState,
      isMuted: nextMuted,
    }
    this.socket?.emit('mute-state', {
      callId,
      userId: this.userId,
      isMuted: nextMuted,
    })
    this.notifyCallStateChange()
  }

  async toggleVideo(): Promise<void> {
    const stream = this.localStream, revision = this.mediaRevision
    if (!stream || !this.callState.currentCallId || this.mediaBusy) return
    this.mediaBusy = true
    try {
      const nextVideo = !this.callState.isVideoEnabled
      let track = stream.getVideoTracks().find(value => value.readyState === 'live') ?? null
      if (nextVideo && !track) {
        const capture = await navigator.mediaDevices.getUserMedia({ video: true, audio: false })
        track = capture.getVideoTracks()[0] ?? null
        if (!track || revision !== this.mediaRevision) { capture.getTracks().forEach(value => value.stop()); return }
        try { if (!this.screenTrack) await replacePeerTracks(this.peerConnections.values(), 'video', track, stream) }
        catch (error) { track.stop(); throw error }
        if (revision !== this.mediaRevision) { track.stop(); return }
        stream.getVideoTracks().forEach(value => { stream.removeTrack(value); value.stop() })
        stream.addTrack(track)
      }
      if (track) track.enabled = nextVideo
      this.callState = { ...this.callState, isVideoEnabled: nextVideo }
      this.emitVideoState()
      this.publishLocalPreview()
      this.notifyCallStateChange()
    } finally { this.mediaBusy = false }
  }

  async toggleScreenShare(): Promise<void> {
    const stream = this.localStream, revision = this.mediaRevision
    if (!stream || !this.callState.currentCallId || this.mediaBusy) return
    this.mediaBusy = true
    try {
      if (this.callState.isScreenSharing) { await this.stopScreenShare(); return }
      const screenStream = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: false })
      const screenTrack = screenStream.getVideoTracks()[0]
      if (!screenTrack || revision !== this.mediaRevision) { screenStream.getTracks().forEach(value => value.stop()); return }
      try { await replacePeerTracks(this.peerConnections.values(), 'video', screenTrack, stream) }
      catch (error) { screenTrack.stop(); throw error }
      if (revision !== this.mediaRevision) { screenTrack.stop(); return }
      this.screenTrack = screenTrack
      screenTrack.onended = () => {
        if (this.screenTrack === screenTrack) void this.stopScreenShare().catch(() => {})
      }
      this.callState = { ...this.callState, isScreenSharing: true }
      this.emitVideoState()
      this.publishLocalPreview()
      this.notifyCallStateChange()
    } finally { this.mediaBusy = false }
  }

  private async stopScreenShare(): Promise<void> {
    if (!this.localStream || !this.screenTrack) return
    const activeScreenTrack = this.screenTrack
    activeScreenTrack.onended = null
    const revision = this.mediaRevision
    const cameraTrack = this.localStream.getVideoTracks().find(value => value.readyState === 'live') ?? null
    await replacePeerTracks(this.peerConnections.values(), 'video', cameraTrack, this.localStream)
    if (revision !== this.mediaRevision || this.screenTrack !== activeScreenTrack) return
    this.screenTrack = null
    activeScreenTrack.stop()
    this.callState = { ...this.callState, isScreenSharing: false }
    this.emitVideoState()
    this.publishLocalPreview()
    this.notifyCallStateChange()
  }

  private publishLocalPreview(): void {
    const tracks = this.localStream?.getTracks().filter(track => !this.screenTrack || track.kind !== 'video') ?? []
    if (this.screenTrack) tracks.push(this.screenTrack)
    const preview = this.localStream ? new MediaStream(tracks) : null
    this.onLocalStreamCallbacks.forEach(callback => callback(preview))
  }

  private emitVideoState(): void {
    if (!this.callState.currentCallId) return
    this.socket?.emit('video-state', { callId: this.callState.currentCallId, userId: this.userId, isVideoEnabled: this.callState.isScreenSharing || this.callState.isVideoEnabled })
  }

  private updateRemoteMedia(userId: string, patch: Partial<CallParticipant>): void {
    this.participants.forEach((participant, socketId) => {
      if (participant.id !== userId) return
      const updated = { ...participant, ...patch }
      this.participants.set(socketId, updated)
      if (this.callState.remoteParticipant?.id === userId) this.callState = { ...this.callState, remoteParticipant: updated }
    })
    this.notifyCallStateChange()
  }

  onCallState(callback: (state: CallState) => void): void {
    this.onCallStateCallbacks.push(callback)
  }

  onRemoteStream(callback: (stream: MediaStream, participant: CallParticipant) => void): void {
    this.onRemoteStreamCallbacks.push(callback)
  }

  onLocalStream(callback: (stream: MediaStream | null) => void): void {
    this.onLocalStreamCallbacks.push(callback)
  }

  onCallEnd(callback: (reason: string, event: CallEndEvent) => void): void {
    this.onCallEndCallbacks.push(callback)
  }

  getLocalStream(): MediaStream | null {
    return this.localStream
  }

  cleanup(): void {
    this.disposed = true
    this.endCall('cleanup')
  }

  private async connectSocket(callId: string): Promise<void> {
    await this.loadIceServers()
    this.ensureCallStillCurrent(callId)

    const socket = io(toCallNamespaceUrl(this.signalingBaseUrl), {
      transports: ['websocket', 'polling'],
      query: { affinity: buildSignalingAffinity('call', callId) },
      auth: { token: this.accessToken },
      autoConnect: false,
      reconnection: true,
      reconnectionAttempts: 10,
      reconnectionDelay: 800,
      reconnectionDelayMax: 10000,
      timeout: 12000,
    }) as CallSocket

    this.socket = socket
    this.setupSocketListeners(socket, callId)

    await new Promise<void>((resolve, reject) => {
      let settled = false
      const timeout = setTimeout(() => finish(new Error('No se pudo confirmar el acceso a la llamada.')), 12000)
      const joined = (payload: { callId: string }) => { if (payload.callId === callId) finish() }
      const failed = (payload: { callId?: string; error?: string }) => {
        if (payload.error === 'CALL_ACCESS_UNAVAILABLE' || payload.error === 'CALL_JOIN_IN_PROGRESS') return
        if (!payload.callId || payload.callId === callId) finish(new Error(payload.error === 'CALL_ACCESS_UNAVAILABLE'
          ? 'El servidor de llamadas no está disponible. Intenta nuevamente.' : 'Esta llamada ya no está disponible.'))
      }
      const connectionFailed = () => finish(new Error('No se pudo conectar con el servidor de llamadas.'))
      const finish = (error?: Error) => {
        if (settled) return
        settled = true
        this.pendingJoinCancel = null
        clearTimeout(timeout)
        socket.off('call-joined', joined)
        socket.off('call-join-error', failed)
        socket.off('auth-error', failed)
        socket.off('connect_error', connectionFailed)
        if (error) reject(error)
        else resolve()
      }
      this.pendingJoinCancel = () => finish(new Error('La llamada terminó antes de conectar.'))
      // Reconnects must rejoin and revalidate the persisted permission.
      socket.on('connect', () => {
        if (this.ended || this.callState.currentCallId !== callId) return
        this.clearJoinRetry()
        this.joinRetryDeadline = setTimeout(() => this.endCall('authorization_unavailable'), 12000)
        this.ownSocketId = socket.id ?? ''
        this.callState = { ...this.callState, connectionLabel: 'Confirmando acceso...' }
        this.notifyCallStateChange()
        socket.emit('join-call', { callId, userId: this.userId, name: this.displayName, avatarUrl: this.avatarUrl })
      })
      socket.on('call-joined', joined)
      socket.on('call-join-error', failed)
      socket.on('auth-error', failed)
      socket.once('connect_error', connectionFailed)
      socket.connect()
    })
  }

  private clearJoinRetry(): void {
    if (this.joinRetryTimer) clearTimeout(this.joinRetryTimer)
    if (this.joinRetryDeadline) clearTimeout(this.joinRetryDeadline)
    this.joinRetryTimer = null
    this.joinRetryDeadline = null
  }

  private scheduleJoinRetry(socket: CallSocket, callId: string): void {
    if (this.joinRetryTimer || this.ended || this.callState.currentCallId !== callId) return
    this.joinRetryTimer = setTimeout(() => {
      this.joinRetryTimer = null
      if (!socket.connected || this.ended || this.callState.currentCallId !== callId) return
      socket.emit('join-call', { callId, userId: this.userId, name: this.displayName, avatarUrl: this.avatarUrl })
    }, 1000)
  }

  private setupSocketListeners(socket: CallSocket, callId: string) {
    socket.on('current-participants', (participants) => {
      participants.forEach((participant) => {
        this.registerParticipant(participant)
        if (this.shouldInitiateOffer(participant)) {
          void this.createAndSendOffer(participant.socketId)
        }
      })
    })

    socket.on('user-joined', (participant) => {
      this.registerParticipant(participant)
      if (this.shouldInitiateOffer(participant)) {
        void this.createAndSendOffer(participant.socketId)
      }
    })

    socket.on('user-left', (socketId) => {
      this.participants.delete(socketId)
      this.peerSessions.get(socketId)?.dispose()
      this.peerSessions.delete(socketId)
      this.peerConnections.get(socketId)?.close()
      this.peerConnections.delete(socketId)
      if (this.participants.size === 0 && this.callState.currentCallId === callId) {
        this.callState = { ...this.callState, connectionLabel: 'Esperando reconexión...' }
        this.notifyCallStateChange()
      }
    })

    socket.on('offer', (payload) => {
      void this.handleOffer(payload)
    })

    socket.on('answer', (payload) => {
      void this.handleAnswer(payload)
    })

    socket.on('ice-candidate', (payload) => {
      void this.handleIceCandidate(payload)
    })

    socket.on('mute-state', (payload) => {
      this.updateRemoteMedia(payload.userId, { isAudioEnabled: !payload.isMuted })
    })
    socket.on('video-state', payload => this.updateRemoteMedia(payload.userId, { isVideoEnabled: payload.isVideoEnabled }))

    socket.on('auth-error', () => this.endCall('auth_error'))
    socket.on('call-joined', (payload) => {
      if (payload.callId === callId) { this.clearJoinRetry(); this.emitVideoState() }
    })
    socket.on('call-join-error', (payload) => {
      if (payload.callId && payload.callId !== callId) return
      if (payload.error === 'CALL_JOIN_IN_PROGRESS') return
      if (payload.error === 'CALL_ACCESS_UNAVAILABLE') {
        this.callState = { ...this.callState, connectionLabel: 'La autorización no está disponible. Intenta nuevamente.' }
        this.notifyCallStateChange()
        this.scheduleJoinRetry(socket, callId)
        return
      }
      this.endCall('access_denied')
    })
    socket.on('call-access-revoked', (payload) => {
      if (!payload.callId || payload.callId === callId) this.endCall('access_denied')
    })
    socket.on('call-session-replaced', (payload) => {
      if (!payload.callId || payload.callId === callId) this.endCall('session_replaced')
    })

    socket.on('disconnect', () => {
      if (!this.callState.currentCallId) return
      this.callState = { ...this.callState, connectionLabel: 'Reconectando...' }
      this.notifyCallStateChange()
    })
  }

  private registerParticipant(signalParticipant: SignalParticipant) {
    const socketId = signalParticipant.socketId?.trim()
    const userId = signalParticipant.userId?.trim()
    if (!socketId || !userId || socketId === this.ownSocketId || userId === this.userId) return

    const participant: CallParticipant = {
      id: userId,
      name: signalParticipant.name?.trim() || this.callState.remoteParticipant?.name || 'Usuario',
      avatarUrl: signalParticipant.avatarUrl?.trim() || this.callState.remoteParticipant?.avatarUrl || '',
      isAudioEnabled: true,
      isVideoEnabled: signalParticipant.videoEnabled !== false,
      isSpeaking: false,
      socketId,
    }
    this.participants.set(socketId, participant)
    if (!this.callState.remoteParticipant || this.callState.remoteParticipant.id === userId) {
      this.callState = {
        ...this.callState,
        remoteParticipant: participant,
        connectionLabel: 'Conectando medios...',
      }
    }
    this.getOrCreatePeerConnection(socketId)
    this.notifyCallStateChange()
  }

  private shouldInitiateOffer(participant: SignalParticipant): boolean {
    const remoteUserId = participant.userId?.trim()
    const remoteSocketId = participant.socketId?.trim()
    if (!this.ownSocketId || !remoteUserId || !remoteSocketId) return false
    const isPolite =
      this.userId < remoteUserId ||
      (this.userId === remoteUserId && this.ownSocketId < remoteSocketId)
    return !isPolite
  }

  private isPolitePeer(socketId: string): boolean {
    const remoteUserId = this.participants.get(socketId)?.id?.trim() || socketId
    if (!this.ownSocketId) return true
    return this.userId < remoteUserId ||
      (this.userId === remoteUserId && this.ownSocketId < socketId)
  }

  private getOrCreatePeerConnection(socketId: string): RTCPeerConnection {
    const existing = this.peerConnections.get(socketId)
    if (existing) return existing

    const connection = new RTCPeerConnection({ iceServers: this.iceServers })
    this.localStream?.getTracks().filter(track => !this.screenTrack || track.kind !== 'video').concat(this.screenTrack ? [this.screenTrack] : []).forEach((track) => {
      connection.addTrack(track, this.localStream as MediaStream)
    })

    connection.onicecandidate = (event) => {
      if (!event.candidate) return
      this.socket?.emit('ice-candidate', {
        target: socketId,
        candidate: event.candidate.toJSON(),
        userId: this.userId,
        name: this.displayName,
      })
    }

    connection.ontrack = (event) => {
      const stream = event.streams[0]
      const participant = this.participants.get(socketId)
      if (!stream || !participant) return

      const nextParticipant = { ...participant, stream }
      this.participants.set(socketId, nextParticipant)
      this.callState = {
        ...this.callState,
        remoteParticipant: nextParticipant,
        connectionLabel: 'Medios conectados',
      }
      this.startCallTimer()
      this.onRemoteStreamCallbacks.forEach((callback) => callback(stream, nextParticipant))
      this.notifyCallStateChange()
    }

    connection.onconnectionstatechange = () => {
      this.peerSessions.get(socketId)?.handleConnectionState(connection.connectionState)
      if (isConnectedState(connection.connectionState)) {
        this.callState = { ...this.callState, connectionLabel: 'En llamada' }
        this.startCallTimer()
      } else if (connection.connectionState === 'failed' || connection.connectionState === 'disconnected') {
        this.callState = { ...this.callState, connectionLabel: 'Reconectando medios...' }
      }
      this.notifyCallStateChange()
    }

    this.peerConnections.set(socketId, connection)
    const session = new WebRtcPeerSession(connection, {
      polite: this.isPolitePeer(socketId),
      sendOffer: (offer) => {
        this.socket?.emit('offer', {
          target: socketId,
          offer,
          userId: this.userId,
          name: this.displayName,
        })
      },
      onRecoveryState: (state, attempt) => {
        if (state === 'recovering') {
          this.callState = { ...this.callState, connectionLabel: `Recuperando medios (${attempt}/3)...` }
        } else if (state === 'exhausted') {
          this.callState = { ...this.callState, connectionLabel: 'No se pudo recuperar. Vuelve a intentar.' }
        }
        this.notifyCallStateChange()
      },
      onError: () => {
        this.callState = { ...this.callState, connectionLabel: 'Reintentando conexión de medios...' }
        this.notifyCallStateChange()
      },
    })
    this.peerSessions.set(socketId, session)
    session.bindNegotiation()
    void this.flushPendingCandidates(socketId)
    return connection
  }

  private async createAndSendOffer(socketId: string): Promise<void> {
    this.getOrCreatePeerConnection(socketId)
    await this.peerSessions.get(socketId)?.negotiate()
  }

  private async handleOffer(payload: OfferPayload): Promise<void> {
    const socketId = payload.sender?.trim()
    if (!socketId) return

    this.registerParticipant({
      socketId,
      userId: payload.userId?.trim() || this.participants.get(socketId)?.id || socketId,
      name: payload.name,
      avatarUrl: this.participants.get(socketId)?.avatarUrl,
    })

    this.getOrCreatePeerConnection(socketId)
    const answer = await this.peerSessions.get(socketId)?.receiveOffer(payload.offer)
    if (!answer) return
    await this.flushPendingCandidates(socketId)
    this.socket?.emit('answer', {
      target: socketId,
      answer,
      userId: this.userId,
      name: this.displayName,
    })
    this.startCallTimer()
  }

  private async handleAnswer(payload: AnswerPayload): Promise<void> {
    const socketId = payload.sender?.trim()
    if (!socketId) return
    const session = this.peerSessions.get(socketId)
    if (!session) return
    await session.receiveAnswer(payload.answer)
    await this.flushPendingCandidates(socketId)
    this.startCallTimer()
  }

  private async handleIceCandidate(payload: IcePayload): Promise<void> {
    const socketId = payload.sender?.trim()
    if (!socketId || !payload.candidate) return
    const connection = this.peerConnections.get(socketId)
    if (!connection || !connection.remoteDescription) {
      const queued = this.pendingCandidates.get(socketId) ?? []
      queued.push(payload.candidate)
      this.pendingCandidates.set(socketId, queued)
      return
    }
    await this.peerSessions.get(socketId)?.addIceCandidate(payload.candidate)
  }

  private async flushPendingCandidates(socketId: string): Promise<void> {
    const connection = this.peerConnections.get(socketId)
    if (!connection || !connection.remoteDescription) return
    const queued = this.pendingCandidates.get(socketId) ?? []
    this.pendingCandidates.delete(socketId)
    for (const candidate of queued) {
      await this.peerSessions.get(socketId)?.addIceCandidate(candidate)
    }
  }

  private async initializeLocalMedia(audio: boolean, video: boolean, callId: string): Promise<void> {
    const constraints: MediaStreamConstraints = {
      audio,
      video: video
        ? {
            width: { ideal: 1280 },
            height: { ideal: 720 },
          }
        : false,
    }
    const stream = await navigator.mediaDevices.getUserMedia(constraints)
    if (this.ended || this.callState.currentCallId !== callId) {
      stream.getTracks().forEach((track) => track.stop())
      throw new Error('La llamada terminó antes de abrir los medios.')
    }
    this.localStream = stream
    this.onLocalStreamCallbacks.forEach((callback) => callback(this.localStream))
  }

  private stopLocalStream(): void {
    this.localStream?.getTracks().forEach((track) => track.stop())
    this.localStream = null
    this.onLocalStreamCallbacks.forEach((callback) => callback(null))
  }

  private async loadIceServers(): Promise<void> {
    try {
      const response = await fetch(toTurnCredentialsUrl(this.signalingBaseUrl), {
        headers: {
          Accept: 'application/json',
          Authorization: `Bearer ${this.accessToken}`,
        },
      })
      if (!response.ok) return
      const payload = (await response.json()) as { iceServers?: RTCIceServer[] }
      if (Array.isArray(payload.iceServers) && payload.iceServers.length > 0) {
        this.iceServers = payload.iceServers
      }
    } catch {
      this.iceServers = [
        { urls: 'stun:stun.l.google.com:19302' },
        { urls: 'stun:stun1.l.google.com:19302' },
      ]
    }
  }

  private startCallTimer(): void {
    this.markAccepted()
    if (this.durationInterval) return
    this.callStartTime = Date.now()
    this.durationInterval = setInterval(() => {
      if (!this.callStartTime) return
      this.callState = {
        ...this.callState,
        duration: Math.floor((Date.now() - this.callStartTime) / 1000),
      }
      this.notifyCallStateChange()
    }, 1000)
  }

  private notifyCallStateChange(): void {
    this.onCallStateCallbacks.forEach((callback) => {
      callback({ ...this.callState })
    })
  }
}

export default WebCallManager
