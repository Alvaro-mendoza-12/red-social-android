import { io, type Socket } from 'socket.io-client'

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
  'auth-error': (payload: { error?: string }) => void
}, {
  'join-call': (payload: { callId: string; userId: string; name: string; avatarUrl: string }) => void
  offer: (payload: { target: string; offer: RTCSessionDescriptionInit; userId: string; name: string }) => void
  answer: (payload: { target: string; answer: RTCSessionDescriptionInit; userId: string; name: string }) => void
  'ice-candidate': (payload: { target: string; candidate: RTCIceCandidateInit; userId: string; name: string }) => void
  'mute-state': (payload: { callId: string; userId: string; isMuted: boolean }) => void
}>

type SignalParticipant = {
  socketId: string
  userId: string
  name?: string
  avatarUrl?: string
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
  private socket: CallSocket | null = null
  private ownSocketId = ''
  private localStream: MediaStream | null = null
  private peerConnections = new Map<string, RTCPeerConnection>()
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
  private onCallEndCallbacks: Array<(reason: string) => void> = []
  private ended = false

  constructor(options: WebCallManagerOptions) {
    this.userId = options.userId
    this.accessToken = options.accessToken
    this.displayName = options.displayName
    this.avatarUrl = options.avatarUrl ?? ''
    this.signalingBaseUrl = options.signalingBaseUrl
  }

  async initiateCall(
    callId: string,
    remoteUserId: string,
    participantInfo: { name: string; avatarUrl: string; videoEnabled: boolean },
  ): Promise<void> {
    if (this.callState.currentCallId) {
      throw new Error('Ya estás en una llamada')
    }

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

    await this.initializeLocalMedia(true, participantInfo.videoEnabled)
    await this.connectSocket(callId)
  }

  async joinExistingCall(
    callId: string,
    participantInfo: { id: string; name: string; avatarUrl?: string; videoEnabled: boolean },
  ): Promise<void> {
    if (this.callState.currentCallId) {
      throw new Error('Ya estás en una llamada')
    }

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

    await this.initializeLocalMedia(true, participantInfo.videoEnabled)
    await this.connectSocket(callId)
  }

  rejectCall(): void {
    this.endCall('rejected')
  }

  endCall(reason = 'local_hang_up'): void {
    if (this.ended && reason !== 'cleanup') return
    this.ended = true

    if (this.durationInterval) {
      clearInterval(this.durationInterval)
      this.durationInterval = null
    }

    this.peerConnections.forEach((connection) => connection.close())
    this.peerConnections.clear()
    this.pendingCandidates.clear()
    this.participants.clear()

    this.socket?.disconnect()
    this.socket = null
    this.ownSocketId = ''
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

    this.onCallEndCallbacks.forEach((callback) => callback(reason))
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
    if (!this.localStream) return

    const nextVideo = !this.callState.isVideoEnabled
    const videoTracks = this.localStream.getVideoTracks()
    if (nextVideo && videoTracks.length === 0) {
      const videoStream = await navigator.mediaDevices.getUserMedia({ video: true })
      const videoTrack = videoStream.getVideoTracks()[0]
      if (videoTrack) {
        this.localStream.addTrack(videoTrack)
        this.peerConnections.forEach((connection) => {
          connection.addTrack(videoTrack, this.localStream as MediaStream)
        })
      }
    } else {
      videoTracks.forEach((track) => {
        track.enabled = nextVideo
      })
    }

    this.callState = {
      ...this.callState,
      isVideoEnabled: nextVideo,
    }
    this.onLocalStreamCallbacks.forEach((callback) => callback(this.localStream))
    this.notifyCallStateChange()
  }

  async toggleScreenShare(): Promise<void> {
    if (!this.localStream) return
    if (!this.callState.isScreenSharing) {
      const screenStream = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: false })
      const screenTrack = screenStream.getVideoTracks()[0]
      if (!screenTrack) return

      await Promise.all(
        Array.from(this.peerConnections.values()).map(async (connection) => {
          const sender = connection.getSenders().find((item) => item.track?.kind === 'video')
          if (sender) {
            await sender.replaceTrack(screenTrack)
          } else {
            connection.addTrack(screenTrack, screenStream)
          }
        }),
      )

      screenTrack.onended = () => {
        this.callState = { ...this.callState, isScreenSharing: false }
        this.notifyCallStateChange()
      }
      this.callState = { ...this.callState, isScreenSharing: true }
      this.notifyCallStateChange()
      return
    }

    const cameraTrack = this.localStream.getVideoTracks()[0] ?? null
    await Promise.all(
      Array.from(this.peerConnections.values()).map(async (connection) => {
        const sender = connection.getSenders().find((item) => item.track?.kind === 'video')
        if (sender) await sender.replaceTrack(cameraTrack)
      }),
    )
    this.callState = { ...this.callState, isScreenSharing: false }
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

  onCallEnd(callback: (reason: string) => void): void {
    this.onCallEndCallbacks.push(callback)
  }

  getLocalStream(): MediaStream | null {
    return this.localStream
  }

  cleanup(): void {
    this.endCall('cleanup')
  }

  private async connectSocket(callId: string): Promise<void> {
    await this.loadIceServers()

    const socket = io(toCallNamespaceUrl(this.signalingBaseUrl), {
      transports: ['websocket', 'polling'],
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
      const timeout = window.setTimeout(() => {
        reject(new Error('No se pudo conectar con el servidor de llamadas.'))
      }, 12000)

      socket.once('connect', () => {
        window.clearTimeout(timeout)
        this.ownSocketId = socket.id ?? ''
        this.callState = { ...this.callState, connectionLabel: 'Señalización conectada' }
        this.notifyCallStateChange()
        socket.emit('join-call', {
          callId,
          userId: this.userId,
          name: this.displayName,
          avatarUrl: this.avatarUrl,
        })
        resolve()
      })

      socket.once('connect_error', (error) => {
        window.clearTimeout(timeout)
        reject(error)
      })

      socket.connect()
    })
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
      this.participants.forEach((participant, socketId) => {
        if (participant.id !== payload.userId) return
        this.participants.set(socketId, {
          ...participant,
          isAudioEnabled: !payload.isMuted,
        })
      })
      this.notifyCallStateChange()
    })

    socket.on('auth-error', (payload) => {
      this.callState = {
        ...this.callState,
        connectionLabel: payload.error || 'La señalización rechazó la sesión.',
      }
      this.notifyCallStateChange()
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
      isVideoEnabled: this.callState.isVideoEnabled,
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
      this.userId.localeCompare(remoteUserId) < 0 ||
      (this.userId === remoteUserId && this.ownSocketId.localeCompare(remoteSocketId) < 0)
    return !isPolite
  }

  private getOrCreatePeerConnection(socketId: string): RTCPeerConnection {
    const existing = this.peerConnections.get(socketId)
    if (existing) return existing

    const connection = new RTCPeerConnection({ iceServers: this.iceServers })
    this.localStream?.getTracks().forEach((track) => {
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
      if (isConnectedState(connection.connectionState)) {
        this.callState = { ...this.callState, connectionLabel: 'En llamada' }
        this.startCallTimer()
      } else if (connection.connectionState === 'failed' || connection.connectionState === 'disconnected') {
        this.callState = { ...this.callState, connectionLabel: 'Reconectando medios...' }
      }
      this.notifyCallStateChange()
    }

    this.peerConnections.set(socketId, connection)
    void this.flushPendingCandidates(socketId)
    return connection
  }

  private async createAndSendOffer(socketId: string): Promise<void> {
    const connection = this.getOrCreatePeerConnection(socketId)
    if (connection.signalingState !== 'stable') return

    const offer = await connection.createOffer()
    await connection.setLocalDescription(offer)
    this.socket?.emit('offer', {
      target: socketId,
      offer,
      userId: this.userId,
      name: this.displayName,
    })
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

    const connection = this.getOrCreatePeerConnection(socketId)
    await connection.setRemoteDescription(payload.offer)
    await this.flushPendingCandidates(socketId)
    const answer = await connection.createAnswer()
    await connection.setLocalDescription(answer)
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
    const connection = this.peerConnections.get(socketId)
    if (!connection || connection.signalingState === 'stable') return
    await connection.setRemoteDescription(payload.answer)
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
    await connection.addIceCandidate(payload.candidate)
  }

  private async flushPendingCandidates(socketId: string): Promise<void> {
    const connection = this.peerConnections.get(socketId)
    if (!connection || !connection.remoteDescription) return
    const queued = this.pendingCandidates.get(socketId) ?? []
    this.pendingCandidates.delete(socketId)
    for (const candidate of queued) {
      await connection.addIceCandidate(candidate)
    }
  }

  private async initializeLocalMedia(audio: boolean, video: boolean): Promise<void> {
    const constraints: MediaStreamConstraints = {
      audio,
      video: video
        ? {
            width: { ideal: 1280 },
            height: { ideal: 720 },
          }
        : false,
    }
    this.localStream = await navigator.mediaDevices.getUserMedia(constraints)
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
