export interface CallSignal {
  type: 'offer' | 'answer' | 'ice-candidate' | 'hang-up' | 'busy' | 'reject'
  from: string
  to: string
  data?: any
  timestamp: number
}

export interface CallParticipant {
  id: string
  name: string
  avatarUrl: string
  isAudioEnabled: boolean
  isVideoEnabled: boolean
  isSpeaking: boolean
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
}

export class WebCallManager {
  private localStream: MediaStream | null = null
  private peerConnection: RTCPeerConnection | null = null
  private dataChannel: RTCDataChannel | null = null
  private callState: CallState = {
    isIncomingCall: false,
    isOutgoingCall: false,
    currentCallId: null,
    remoteParticipant: null,
    duration: 0,
    isMuted: false,
    isVideoEnabled: true,
    isScreenSharing: false
  }
  private callStartTime: number | null = null
  private durationInterval: NodeJS.Timeout | null = null
  private onCallStateCallbacks: ((state: CallState) => void)[] = []
  private onIncomingCallCallbacks: ((from: string, participant: CallParticipant) => void)[] = []
  private onCallEndCallbacks: ((reason: string) => void)[] = []

  constructor(private userId: string, private signalingChannel: BroadcastChannel) {
    this.setupSignalingListeners()
  }

  private setupSignalingListeners(): void {
    this.signalingChannel.addEventListener('message', (event) => {
      try {
        const signal: CallSignal = JSON.parse(event.data)
        this.handleSignalingMessage(signal)
      } catch (error) {
        console.error('Error parsing signaling message:', error)
      }
    })
  }

  private async handleSignalingMessage(signal: CallSignal): Promise<void> {
    if (signal.to !== this.userId) return

    switch (signal.type) {
      case 'offer':
        await this.handleIncomingCall(signal)
        break
      case 'answer':
        await this.handleCallAnswer(signal)
        break
      case 'ice-candidate':
        await this.handleIceCandidate(signal)
        break
      case 'hang-up':
        this.handleHangUp(signal)
        break
      case 'busy':
      case 'reject':
        this.handleCallRejected(signal)
        break
    }
  }

  private async handleIncomingCall(signal: CallSignal): Promise<void> {
    if (this.callState.currentCallId) {
      // Already in a call, send busy signal
      this.sendSignal({
        type: 'busy',
        from: this.userId,
        to: signal.from,
        timestamp: Date.now()
      })
      return
    }

    const participant: CallParticipant = {
      id: signal.from,
      name: signal.data?.name || 'Usuario',
      avatarUrl: signal.data?.avatarUrl || '',
      isAudioEnabled: true,
      isVideoEnabled: signal.data?.videoEnabled || false,
      isSpeaking: false
    }

    this.callState.isIncomingCall = true
    this.callState.remoteParticipant = participant
    this.callState.currentCallId = `${signal.from}-${this.userId}-${Date.now()}`

    this.onIncomingCallCallbacks.forEach(callback => {
      callback(signal.from, participant)
    })

    this.notifyCallStateChange()
  }

  private async handleCallAnswer(signal: CallSignal): Promise<void> {
    if (!this.peerConnection || !this.callState.isOutgoingCall) return

    await this.peerConnection.setRemoteDescription(signal.data)
    this.notifyCallStateChange()
  }

  private async handleIceCandidate(signal: CallSignal): Promise<void> {
    if (!this.peerConnection) return

    try {
      await this.peerConnection.addIceCandidate(signal.data)
    } catch (error) {
      console.error('Error adding ICE candidate:', error)
    }
  }

  private handleHangUp(signal: CallSignal): void {
    if (signal.from === this.callState.remoteParticipant?.id) {
      this.endCall('remote_hang_up')
    }
  }

  private handleCallRejected(signal: CallSignal): void {
    if (signal.from === this.callState.remoteParticipant?.id) {
      this.endCall('rejected')
    }
  }

  async initiateCall(remoteUserId: string, participantInfo: { name: string; avatarUrl: string; videoEnabled: boolean }): Promise<void> {
    if (this.callState.currentCallId) {
      throw new Error('Ya estás en una llamada')
    }

    try {
      // Initialize local media
      await this.initializeLocalMedia(true, participantInfo.videoEnabled)

      // Create peer connection
      this.peerConnection = this.createPeerConnection()
      this.localStream?.getTracks().forEach(track => {
        this.peerConnection!.addTrack(track, this.localStream!)
      })

      // Create and send offer
      const offer = await this.peerConnection.createOffer()
      await this.peerConnection.setLocalDescription(offer)

      const participant: CallParticipant = {
        id: remoteUserId,
        name: participantInfo.name,
        avatarUrl: participantInfo.avatarUrl,
        isAudioEnabled: true,
        isVideoEnabled: participantInfo.videoEnabled,
        isSpeaking: false
      }

      this.callState.isOutgoingCall = true
      this.callState.remoteParticipant = participant
      this.callState.currentCallId = `${this.userId}-${remoteUserId}-${Date.now()}`
      this.callState.isVideoEnabled = participantInfo.videoEnabled

      this.sendSignal({
        type: 'offer',
        from: this.userId,
        to: remoteUserId,
        data: {
          ...offer,
          name: this.userId,
          avatarUrl: '', // Would come from user profile
          videoEnabled: participantInfo.videoEnabled
        },
        timestamp: Date.now()
      })

      this.notifyCallStateChange()
    } catch (error) {
      console.error('Error initiating call:', error)
      throw new Error('No se pudo iniciar la llamada')
    }
  }

  async answerCall(videoEnabled: boolean = true): Promise<void> {
    if (!this.callState.isIncomingCall || !this.peerConnection) {
      throw new Error('No hay llamada entrante para contestar')
    }

    try {
      // Initialize local media
      await this.initializeLocalMedia(true, videoEnabled)

      // Add local tracks to peer connection
      this.localStream?.getTracks().forEach(track => {
        this.peerConnection!.addTrack(track, this.localStream!)
      })

      // Create and send answer
      const answer = await this.peerConnection.createAnswer()
      await this.peerConnection.setLocalDescription(answer)

      this.callState.isIncomingCall = false
      this.callState.isVideoEnabled = videoEnabled

      this.sendSignal({
        type: 'answer',
        from: this.userId,
        to: this.callState.remoteParticipant!.id,
        data: answer,
        timestamp: Date.now()
      })

      this.startCallTimer()
      this.notifyCallStateChange()
    } catch (error) {
      console.error('Error answering call:', error)
      throw new Error('No se pudo contestar la llamada')
    }
  }

  rejectCall(): void {
    if (!this.callState.isIncomingCall) return

    this.sendSignal({
      type: 'reject',
      from: this.userId,
      to: this.callState.remoteParticipant!.id,
      timestamp: Date.now()
    })

    this.endCall('rejected')
  }

  endCall(reason: string = 'local_hang_up'): void {
    if (this.durationInterval) {
      clearInterval(this.durationInterval)
      this.durationInterval = null
    }

    // Send hang-up signal to remote participant
    if (this.callState.remoteParticipant) {
      this.sendSignal({
        type: 'hang-up',
        from: this.userId,
        to: this.callState.remoteParticipant.id,
        timestamp: Date.now()
      })
    }

    // Clean up peer connection
    if (this.peerConnection) {
      this.peerConnection.close()
      this.peerConnection = null
    }

    // Clean up data channel
    if (this.dataChannel) {
      this.dataChannel.close()
      this.dataChannel = null
    }

    // Clean up local stream
    this.stopLocalStream()

    // Reset call state
    this.callState = {
      isIncomingCall: false,
      isOutgoingCall: false,
      currentCallId: null,
      remoteParticipant: null,
      duration: 0,
      isMuted: false,
      isVideoEnabled: true,
      isScreenSharing: false
    }

    this.callStartTime = null

    this.onCallEndCallbacks.forEach(callback => {
      callback(reason)
    })

    this.notifyCallStateChange()
  }

  toggleMute(): void {
    if (!this.localStream) return

    const newMutedState = !this.callState.isMuted
    this.callState.isMuted = newMutedState

    this.localStream.getAudioTracks().forEach(track => {
      track.enabled = !newMutedState
    })

    this.notifyCallStateChange()
  }

  async toggleVideo(): Promise<void> {
    if (!this.localStream) return

    const newVideoState = !this.callState.isVideoEnabled
    this.callState.isVideoEnabled = newVideoState

    this.localStream.getVideoTracks().forEach(track => {
      track.enabled = newVideoState
    })

    this.notifyCallStateChange()
  }

  async toggleScreenShare(): Promise<void> {
    if (!this.peerConnection) return

    if (this.callState.isScreenSharing) {
      // Stop screen sharing
      this.callState.isScreenSharing = false
      // Implementation would depend on getting screen stream
    } else {
      try {
        // Start screen sharing
        const screenStream = await navigator.mediaDevices.getDisplayMedia({
          video: true,
          audio: true
        })

        const videoTrack = screenStream.getVideoTracks()[0]
        if (videoTrack) {
          const sender = this.peerConnection.getSenders().find(s => 
            s.track && s.track.kind === 'video'
          )
          
          if (sender) {
            await sender.replaceTrack(videoTrack)
          } else {
            this.peerConnection.addTrack(videoTrack, screenStream)
          }
        }

        this.callState.isScreenSharing = true

        // Stop screen sharing when track ends
        videoTrack.onended = () => {
          this.callState.isScreenSharing = false
          this.notifyCallStateChange()
        }
      } catch (error) {
        console.error('Error starting screen share:', error)
        throw new Error('No se pudo compartir pantalla')
      }
    }

    this.notifyCallStateChange()
  }

  private createPeerConnection(): RTCPeerConnection {
    const configuration = {
      iceServers: [
        { urls: 'stun:stun.l.google.com:19302' },
        { urls: 'stun:stun1.l.google.com:19302' }
      ]
    }

    const peerConnection = new RTCPeerConnection(configuration)

    peerConnection.onicecandidate = (event) => {
      if (event.candidate && this.callState.remoteParticipant) {
        this.sendSignal({
          type: 'ice-candidate',
          from: this.userId,
          to: this.callState.remoteParticipant.id,
          data: event.candidate,
          timestamp: Date.now()
        })
      }
    }

    peerConnection.ontrack = (event) => {
      if (event.streams && event.streams[0]) {
        // Handle remote stream
        console.log('Remote stream received')
      }
    }

    peerConnection.onconnectionstatechange = () => {
      console.log('Connection state:', peerConnection.connectionState)
      if (peerConnection.connectionState === 'disconnected' || 
          peerConnection.connectionState === 'failed' ||
          peerConnection.connectionState === 'closed') {
        this.endCall('connection_lost')
      }
    }

    return peerConnection
  }

  private async initializeLocalMedia(audio: boolean, video: boolean): Promise<void> {
    try {
      const constraints = {
        audio,
        video: video ? {
          width: { ideal: 1280 },
          height: { ideal: 720 }
        } : false
      }

      this.localStream = await navigator.mediaDevices.getUserMedia(constraints)
    } catch (error) {
      console.error('Error accessing media devices:', error)
      throw new Error('No se pudo acceder a los dispositivos de audio/vídeo')
    }
  }

  private stopLocalStream(): void {
    if (this.localStream) {
      this.localStream.getTracks().forEach(track => {
        track.stop()
      })
      this.localStream = null
    }
  }

  private startCallTimer(): void {
    this.callStartTime = Date.now()
    this.durationInterval = setInterval(() => {
      if (this.callStartTime) {
        this.callState.duration = Math.floor((Date.now() - this.callStartTime) / 1000)
        this.notifyCallStateChange()
      }
    }, 1000)
  }

  private sendSignal(signal: CallSignal): void {
    this.signalingChannel.postMessage(JSON.stringify(signal))
  }

  private notifyCallStateChange(): void {
    this.onCallStateCallbacks.forEach(callback => {
      callback({ ...this.callState })
    })
  }

  // Public API
  onCallState(callback: (state: CallState) => void): void {
    this.onCallStateCallbacks.push(callback)
  }

  onIncomingCall(callback: (from: string, participant: CallParticipant) => void): void {
    this.onIncomingCallCallbacks.push(callback)
  }

  onCallEnd(callback: (reason: string) => void): void {
    this.onCallEndCallbacks.push(callback)
  }

  getCallState(): CallState {
    return { ...this.callState }
  }

  getLocalStream(): MediaStream | null {
    return this.localStream
  }

  cleanup(): void {
    this.endCall('cleanup')
    this.signalingChannel.close()
  }
}

export default WebCallManager
