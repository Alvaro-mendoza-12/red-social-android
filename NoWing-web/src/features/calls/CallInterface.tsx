import './calls.css'
import { StreamingImage, StreamingVideo } from '../../components/StreamingMedia'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { useAuth } from '../../core/auth/AuthProvider'
import { ensureFallbackAccessToken } from '../../core/auth/fallbackAuth'
import { supabase } from '../../core/supabase'
import { sendCallInvite, sendCallResponse } from './callRealtime'
import { CallControlClient } from './callControl'
import { subscribeUbuntuRealtime } from '../../core/ubuntuRealtime'
import WebCallManager, { type CallState, type CallParticipant } from './WebCallManager'

type CallInterfaceProps = {
  initialContextId?: string
  initialCallId?: string
  initialRemoteUserId?: string
  initialRemoteName?: string
  initialVideoEnabled?: boolean
  joinExisting?: boolean
}

export function CallInterface({
  initialContextId = '',
  initialCallId = '',
  initialRemoteUserId = '',
  initialRemoteName = '',
  initialVideoEnabled = true,
  joinExisting = false,
}: CallInterfaceProps) {
  const { user, session, isFallbackSession } = useAuth()
  const navigate = useNavigate()
  const [callState, setCallState] = useState<CallState | null>(null)
  const [callManager, setCallManager] = useState<WebCallManager | null>(null)
  const [remoteUserId, setRemoteUserId] = useState<string>(initialRemoteUserId)
  const [videoEnabled, setVideoEnabled] = useState(initialVideoEnabled)
  const [participantInfo, setParticipantInfo] = useState<CallParticipant | null>(null)
  const [localStream, setLocalStream] = useState<MediaStream | null>(null)
  const [remoteStream, setRemoteStream] = useState<MediaStream | null>(null)
  const [isInitializing, setIsInitializing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [fallbackAccessToken, setFallbackAccessToken] = useState('')

  const localVideoRef = useRef<HTMLVideoElement>(null)
  const remoteVideoRef = useRef<HTMLVideoElement>(null)
  const autoStartedRef = useRef(false)

  const displayName =
    String(user?.user_metadata?.display_name ?? user?.user_metadata?.full_name ?? '').trim() ||
    user?.email?.split('@')[0] ||
    'Usuario'
  const avatarUrl = String(user?.user_metadata?.avatar_url ?? user?.user_metadata?.picture ?? '').trim()
  const signalingBaseUrl = String(import.meta.env.VITE_CALL_SIGNALING_URL ?? import.meta.env.VITE_SIGNALING_URL ?? '').trim()

  useEffect(() => {
    if (!isFallbackSession) {
      queueMicrotask(() => setFallbackAccessToken(''))
      return
    }
    let cancelled = false
    void ensureFallbackAccessToken()
      .then((token) => {
        if (!cancelled) setFallbackAccessToken(token)
      })
      .catch(() => {
        if (!cancelled) setFallbackAccessToken('')
      })
    return () => {
      cancelled = true
    }
  }, [isFallbackSession])

  const accessToken = isFallbackSession ? fallbackAccessToken : session?.access_token ?? ''

  useEffect(() => {
    if (!user || !accessToken) return

    try {
      const manager = new WebCallManager({
        userId: user.id,
        accessToken,
        displayName,
        avatarUrl,
        signalingBaseUrl,
      })
      queueMicrotask(() => setCallManager(manager))

      manager.onCallState((state) => {
        setCallState(state)
        setError(null)
      })

      manager.onLocalStream((stream) => {
        setLocalStream(stream)
      })

      manager.onRemoteStream((stream, participant) => {
        setRemoteStream(stream)
        setParticipantInfo(participant)
      })

      manager.onCallEnd((reason, event) => {
        void event.persisted.then(async (persisted) => {
          if (persisted && event.notifyRemote && event.remoteUserId) {
            await sendCallResponse(supabase, event.remoteUserId, { callId: event.callId, action: event.action, fromUserId: user.id }, accessToken)
          }
        }).catch(() => undefined)
        if (reason === 'cleanup') return
        setCallState(null)
        setRemoteUserId('')
        setParticipantInfo(null)
        setLocalStream(null)
        setRemoteStream(null)

        setTimeout(() => {
          navigate('/app/messages')
        }, 2000)
      })

      return () => {
        manager.cleanup()
      }
    } catch (error) {
      console.error('Error initializing call manager:', error)
      queueMicrotask(() => setError('No se pudo inicializar el sistema de llamadas'))
    }
  }, [accessToken, avatarUrl, displayName, navigate, signalingBaseUrl, user])

  useEffect(() => {
    if (localVideoRef.current) {
      localVideoRef.current.srcObject = localStream
    }
  }, [localStream])

  useEffect(() => {
    if (remoteVideoRef.current) {
      remoteVideoRef.current.srcObject = remoteStream
    }
  }, [remoteStream])

  const initiateCall = useCallback(async () => {
    if (!callManager || !user || !remoteUserId.trim()) {
      setError('Ingresa un ID de usuario válido')
      return
    }

    setIsInitializing(true)
    setError(null)

    try {
      const callId = initialCallId || crypto.randomUUID()
      const contextId = initialContextId || await new CallControlClient(accessToken).directContext(remoteUserId)
      const mockParticipantInfo: CallParticipant = {
        id: remoteUserId,
        name: initialRemoteName || `Usuario ${remoteUserId}`,
        avatarUrl: '',
        isAudioEnabled: true,
        isVideoEnabled: videoEnabled,
        isSpeaking: false,
      }

      await callManager.initiateCall(callId, remoteUserId, {
        name: mockParticipantInfo.name,
        avatarUrl: mockParticipantInfo.avatarUrl,
        videoEnabled,
        contextId,
      })

      await sendCallInvite(supabase, remoteUserId, {
        callId,
        title: mockParticipantInfo.name,
        callerUserId: user.id,
        callerName: displayName,
        callerAvatarUrl: avatarUrl,
        contextType: 'dm',
        contextId,
        isVideo: videoEnabled,
      }, accessToken)

      setParticipantInfo(mockParticipantInfo)
      setIsInitializing(false)
    } catch (error) {
      callManager.endCall('start_failed')
      setError(error instanceof Error ? error.message : 'No se pudo iniciar la llamada')
      setIsInitializing(false)
    }
  }, [accessToken, avatarUrl, callManager, displayName, initialCallId, initialContextId, initialRemoteName, remoteUserId, user, videoEnabled])

  const joinCall = useCallback(async () => {
    if (!callManager || !user || !initialCallId.trim()) return

    setIsInitializing(true)
    setError(null)

    try {
      await callManager.joinExistingCall(initialCallId, {
        id: initialRemoteUserId,
        name: initialRemoteName || 'Usuario',
        avatarUrl: '',
        videoEnabled,
      })
      await sendCallResponse(supabase, initialRemoteUserId, { callId: initialCallId, action: 'accept', fromUserId: user.id }, accessToken)
      setIsInitializing(false)
    } catch (error) {
      callManager.endCall('start_failed')
      setError(error instanceof Error ? error.message : 'No se pudo contestar la llamada')
      setIsInitializing(false)
    }
  }, [accessToken, callManager, initialCallId, initialRemoteName, initialRemoteUserId, user, videoEnabled])

  useEffect(() => {
    if (!callManager || autoStartedRef.current) return
    if (joinExisting && initialCallId) {
      autoStartedRef.current = true
      window.setTimeout(() => {
        void joinCall()
      }, 0)
      return
    }
    if (initialRemoteUserId && !joinExisting) {
      autoStartedRef.current = true
      window.setTimeout(() => {
        void initiateCall()
      }, 0)
    }
  }, [callManager, initialCallId, initialRemoteUserId, initiateCall, joinCall, joinExisting])

  useEffect(() => {
    if (!user || !callState?.currentCallId) return
    const currentCallId = callState.currentCallId
    const receive = (payload: { callId?: string; action?: string }) => {
      if (payload.callId !== currentCallId) return
      if (payload.action === 'accept') callManager?.markAccepted()
      else if (['decline', 'cancel', 'end', 'busy', 'missed', 'collision'].includes(payload.action ?? '')) callManager?.endCall(payload.action ?? 'end')
    }
    const stopUbuntu = subscribeUbuntuRealtime({ channel: `user:${user.id}`, onEvent: (event) => {
      if (event.payload?.event === 'call_response' || event.payload?.event === 'call_cancel') receive(event.payload)
    } })
    const channel = supabase?.channel(`call-user:${user.id}`).on('broadcast', { event: 'call_response' }, (event) => receive(event.payload ?? {}))
    channel?.subscribe()
    return () => {
      stopUbuntu()
      if (channel) void supabase?.removeChannel(channel)
    }
  }, [callManager, callState?.currentCallId, user])

  const answerCall = useCallback(async () => {
    await joinCall()
  }, [joinCall])

  const rejectCall = useCallback(() => {
    if (!callManager) return

    try {
      callManager.rejectCall()
      setCallState(null)
      setRemoteUserId('')
      setParticipantInfo(null)
    } catch (error) {
      console.error('Error rejecting call:', error)
    }
  }, [callManager])

  const endCall = useCallback(() => {
    if (!callManager) return

    try {
      callManager.endCall('user_hang_up')
    } catch (error) {
      console.error('Error ending call:', error)
    }
  }, [callManager, callState, participantInfo, user])

  const toggleMute = useCallback(() => {
    if (!callManager) return
    callManager.toggleMute()
  }, [callManager])

  const toggleVideo = useCallback(async () => {
    if (!callManager) return
    
    try {
      await callManager.toggleVideo()
    } catch (error) {
      console.error('Error toggling video:', error)
    }
  }, [callManager])

  const toggleScreenShare = useCallback(async () => {
    if (!callManager) return

    try {
      await callManager.toggleScreenShare()
    } catch (error) {
      console.error('Error toggling screen share:', error)
    }
  }, [callManager])

  if (!user) {
    return <StatusView title="Autenticación requerida" description="Debes iniciar sesión para hacer llamadas." />
  }

  if (error && !callState) {
    return (
      <div className="call-interface-container">
        <StatusView title="Error de llamadas" description={error} />
        <div className="call-actions">
          <button className="back-btn" onClick={() => navigate('/app/messages')}>
            Volver a mensajes
          </button>
        </div>
      </div>
    )
  }

  if (!callState) {
    // Show dialer interface
    return (
      <div className="call-interface-container">
        <header className="call-header">
          <h1>Nueva Llamada</h1>
          <p>Ingresa el ID del usuario para llamar</p>
        </header>

        <main className="call-main">
          <div className="dialer-section">
            <div className="user-input">
              <label htmlFor="userId">ID de Usuario:</label>
              <input
                id="userId"
                type="text"
                value={remoteUserId}
                onChange={(e) => setRemoteUserId(e.target.value)}
                placeholder="Ingresa el ID del usuario"
                className="user-id-input"
              />
            </div>

            <div className="dialer-actions">
              <label className="call-mode-toggle">
                <input
                  type="checkbox"
                  checked={videoEnabled}
                  onChange={(event) => setVideoEnabled(event.target.checked)}
                />
                <span>Video</span>
              </label>
              <button
                className="call-btn primary"
                onClick={initiateCall}
                disabled={!remoteUserId.trim() || isInitializing}
              >
                {isInitializing ? 'Llamando...' : videoEnabled ? 'Videollamar' : 'Llamar'}
              </button>
            </div>
          </div>

          {videoEnabled ? <div className="local-video-section">
            <h3>Vista Previa</h3>
            <StreamingVideo
              ref={localVideoRef}
              autoPlay
              muted
              playsInline
              className="local-video-preview"
            />
          </div> : null}
        </main>

        <div className="call-footer">
          <button className="back-btn" onClick={() => navigate('/app/messages')}>
            Volver
          </button>
        </div>
      </div>
    )
  }

  // Show active call interface
  return (
    <div className="call-interface-container">
      <header className="call-header">
        <div className="call-info">
          <h2>
            {callState.isIncomingCall ? 'Llamada Entrante' : 'Llamada en Curso'}
          </h2>
          <p>
            {participantInfo?.name || 'Usuario Desconocido'} • 
            {Math.floor(callState.duration / 60)}:{(callState.duration % 60).toString().padStart(2, '0')}
            {callState.connectionLabel ? ` • ${callState.connectionLabel}` : ''}
          </p>
        </div>
        <div className="call-status">
          {callState.isIncomingCall && (
            <span className="incoming-indicator">📞 Entrante</span>
          )}
          {callState.isScreenSharing && (
            <span className="screen-share-indicator">🖥️ Compartiendo pantalla</span>
          )}
        </div>
      </header>

      <main className="call-main">
        <div className="videos-container">
          {/* Remote video */}
          <div className="video-wrapper remote-video">
            <StreamingVideo
              ref={remoteVideoRef}
              autoPlay
              playsInline
              className="remote-video-element"
            />
            <div className="participant-overlay">
              <div className="participant-info">
                <StreamingImage 
                  src={participantInfo?.avatarUrl} 
                  alt={participantInfo?.name}
                  className="participant-avatar"
                />
                <span className="participant-name">{participantInfo?.name || 'Usuario'}</span>
              </div>
              <div className="participant-status">
                {callState.isMuted && <span className="muted-indicator">🔇</span>}
                {!callState.isVideoEnabled && <span className="video-off-indicator">📵</span>}
              </div>
            </div>
          </div>

          {/* Local video */}
          <div className="video-wrapper local-video">
            <StreamingVideo
              ref={localVideoRef}
              autoPlay
              muted
              playsInline
              className={`local-video-element ${callState.isMuted ? 'muted' : ''}`}
            />
            <div className="local-video-overlay">
              <span className="local-video-label">Tú</span>
              {callState.isMuted && <span className="muted-indicator">🔇</span>}
            </div>
          </div>
        </div>

        <div className="call-controls">
          {callState.isIncomingCall ? (
            <div className="incoming-controls">
              <button
                className="control-btn reject-btn"
                onClick={rejectCall}
                disabled={isInitializing}
              >
                🗑️ Rechazar
              </button>
              <button
                className="control-btn answer-btn"
                onClick={answerCall}
                disabled={isInitializing}
              >
                {isInitializing ? 'Conectando...' : '📞 Contestar'}
              </button>
            </div>
          ) : (
            <div className="active-controls">
              <button
                className={`control-btn ${callState.isMuted ? 'muted' : ''}`}
                onClick={toggleMute}
                title={callState.isMuted ? 'Activar micrófono' : 'Desactivar micrófono'}
              >
                {callState.isMuted ? '🔇' : '🎤'}
              </button>
              
              <button
                className={`control-btn ${callState.isVideoEnabled ? 'video-on' : 'video-off'}`}
                onClick={toggleVideo}
                title={callState.isVideoEnabled ? 'Desactivar vídeo' : 'Activar vídeo'}
              >
                {callState.isVideoEnabled ? '📹' : '📵'}
              </button>
              
              <button
                className={`control-btn ${callState.isScreenSharing ? 'screen-sharing' : ''}`}
                onClick={toggleScreenShare}
                title={callState.isScreenSharing ? 'Dejar de compartir pantalla' : 'Compartir pantalla'}
              >
                {callState.isScreenSharing ? '🖥️' : '🖥️'}
              </button>
              
              <button
                className="control-btn end-call-btn"
                onClick={endCall}
              >
                🗑️ Colgar
              </button>
            </div>
          )}
        </div>
      </main>
    </div>
  )
}
