import { SafeAvatar } from '../../components/SafeAvatar'
import { Mic, MicOff, Video, VideoOff, MonitorUp, Phone, PhoneOff, Maximize, Minimize2, Settings, Volume2, VolumeX } from 'lucide-react'
import './calls.css'
import { LiveStreamVideo } from '../../components/LiveStreamMedia'
import { useLiveVideoAvailable } from '../../core/media/useLiveVideoAvailable'
import { AudioOutputSelect } from '../../components/AudioOutputSelect'
import { MediaDeviceDialog } from '../../components/MediaDeviceDialog'
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
  const remoteHasVideo = useLiveVideoAvailable(remoteStream)
  const localHasVideo = useLiveVideoAvailable(localStream)
  const remoteParticipant = callState?.remoteParticipant || participantInfo
  const remoteVideoReady = remoteHasVideo && remoteParticipant?.isVideoEnabled !== false
  const [isDeafened, setIsDeafened] = useState(false)
  const [showSettings, setShowSettings] = useState(false)
  const [devices, setDevices] = useState<MediaDeviceInfo[]>([])
  const [outputDevice, setOutputDevice] = useState('')
  const [mediaBusy, setMediaBusy] = useState(false)
  const [minimized, setMinimized] = useState(false)
  const container = useRef<HTMLDivElement>(null)
  const [fallbackAccessToken, setFallbackAccessToken] = useState('')

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
    let active = true
    let navigationTimer: ReturnType<typeof setTimeout> | undefined
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
        if (!active) return
        setCallState(state.currentCallId ? state : null)
      })

      manager.onLocalStream((stream) => {
        if (!active) return
        setLocalStream(stream)
      })

      manager.onRemoteStream((stream, participant) => {
        if (!active) return
        setRemoteStream(stream)
        setParticipantInfo(participant)
      })

      manager.onCallEnd((reason, event) => {
        void event.persisted.then(async (persisted) => {
          if (persisted && event.notifyRemote && event.remoteUserId) {
            await sendCallResponse(supabase, event.remoteUserId, { callId: event.callId, action: event.action, fromUserId: user.id }, accessToken)
          }
        }).catch(() => undefined)
        if (reason === 'cleanup' || !active) return
        setCallState(null)
        setRemoteUserId('')
        setParticipantInfo(null)
        setLocalStream(null)
        setRemoteStream(null)

        navigationTimer = setTimeout(() => {
          navigate('/app/messages')
        }, 2000)
      })

      return () => {
        active = false
        if (navigationTimer) clearTimeout(navigationTimer)
        manager.cleanup()
      }
    } catch (error) {
      console.error('Error initializing call manager:', error)
      queueMicrotask(() => setError('No se pudo inicializar el sistema de llamadas'))
    }
  }, [accessToken, avatarUrl, displayName, navigate, signalingBaseUrl, user])

  useEffect(() => {
    if (!showSettings || !navigator.mediaDevices?.enumerateDevices) return
    let active = true
    const refresh = () => { void navigator.mediaDevices.enumerateDevices().then(values => { if (active) setDevices(values) }).catch(() => { if (active) setError('No se pudieron consultar los dispositivos de audio.') }) }
    refresh()
    navigator.mediaDevices.addEventListener?.('devicechange', refresh)
    return () => { active = false; navigator.mediaDevices.removeEventListener?.('devicechange', refresh) }
  }, [showSettings])

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
  }, [callManager])

  const toggleMute = useCallback(() => {
    if (!callManager) return
    callManager.toggleMute()
  }, [callManager])

  const toggleVideo = async () => {
    if (!callManager || mediaBusy) return
    setMediaBusy(true); setError(null)
    try { await callManager.toggleVideo() }
    catch { setError('No se pudo activar la cámara. Revisa sus permisos e inténtalo de nuevo.') }
    finally { setMediaBusy(false) }
  }

  const toggleScreenShare = async () => {
    if (!callManager || mediaBusy) return
    setMediaBusy(true); setError(null)
    try { await callManager.toggleScreenShare() }
    catch (caught) { if (!(caught instanceof DOMException && caught.name === 'NotAllowedError')) setError('No se pudo compartir la pantalla. Inténtalo de nuevo.') }
    finally { setMediaBusy(false) }
  }

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
    <div ref={container} className={`call-interface-container active-call floating-call${minimized ? ' call-minimized' : ''}`}>
      {minimized ? <div className="call-mini-panel">
        <button type="button" className="call-restore" aria-label="Ampliar llamada" onClick={() => setMinimized(false)}>
          <SafeAvatar size="lg" url={remoteParticipant?.avatarUrl} name={remoteParticipant?.name || initialRemoteName || 'Usuario'} />
          <strong>{remoteParticipant?.name || initialRemoteName || 'Llamada'}</strong>
          <small>{callState.connectionLabel || 'En llamada'} · {Math.floor(callState.duration / 60)}:{(callState.duration % 60).toString().padStart(2, '0')}</small>
        </button>
        <div className="call-mini-controls"><button className="icon-btn" type="button" aria-label={callState.isMuted ? 'Activar micrófono' : 'Silenciar micrófono'} onClick={toggleMute}>{callState.isMuted ? <MicOff /> : <Mic />}</button><button className="icon-btn end-call-btn" type="button" aria-label="Colgar llamada" onClick={endCall}><PhoneOff /></button></div>
      </div> : null}
      <header className="call-header">
        <div className="call-info">
          <h2>
            {remoteParticipant?.name || initialRemoteName || (callState.isIncomingCall ? 'Llamada entrante' : 'Llamada')}
          </h2>
          <p>
            {callState.isIncomingCall ? 'Llamada entrante' : callState.connectionLabel || (remoteStream ? 'En llamada' : 'Conectando')} •{' '}
            {Math.floor(callState.duration / 60)}:{(callState.duration % 60).toString().padStart(2, '0')}
          </p>
        </div>
        <div className="call-status">
          <button className="icon-btn" type="button" aria-label="Minimizar llamada" title="Minimizar llamada" onClick={() => { if (document.fullscreenElement) void document.exitFullscreen().catch(() => {}); setShowSettings(false); setMinimized(true) }}><Minimize2 size={18} /></button>
          {callState.isIncomingCall && (
            <span className="incoming-indicator">📞 Entrante</span>
          )}
          {callState.isScreenSharing && (
            <span className="screen-share-indicator">🖥️ Compartiendo pantalla</span>
          )}
        </div>
      </header>

      <main className="call-main">
        <div className={`videos-container ${remoteVideoReady ? 'has-remote-video' : 'audio-only'}`}>
          {/* Remote video */}
          <div className="video-wrapper remote-video">
            <LiveStreamVideo stream={remoteStream} label={remoteParticipant?.name || initialRemoteName || 'Participante'} muted={isDeafened} outputDeviceId={outputDevice} className="remote-video-element" />
            {!remoteVideoReady ? <div className="call-participant-placeholder"><SafeAvatar size="lg" url={remoteParticipant?.avatarUrl} name={remoteParticipant?.name || initialRemoteName || 'Usuario'} /><strong>{remoteParticipant?.name || initialRemoteName || 'Usuario'}</strong><small>{remoteStream ? 'Llamada de voz' : callState.isIncomingCall ? 'Te está llamando' : 'Esperando respuesta…'}</small></div> : null}
            <div className="participant-overlay">
              <div className="participant-info">
                <SafeAvatar url={remoteParticipant?.avatarUrl} name={remoteParticipant?.name || 'Usuario'} className="participant-avatar" />
                <span className="participant-name">{remoteParticipant?.name || 'Usuario'}</span>
              </div>
              <div className="participant-status">
                {!remoteVideoReady && <span className="video-off-indicator" aria-label="Cámara apagada"><VideoOff/></span>}
              </div>
            </div>
          </div>

          {/* Local video */}
          <div className="video-wrapper local-video" hidden={!localHasVideo || (!callState.isVideoEnabled && !callState.isScreenSharing)}>
            <LiveStreamVideo stream={localStream} label={callState.isScreenSharing ? 'Tu pantalla' : 'Tu cámara'} muted className={`local-video-element ${callState.isScreenSharing ? 'screen-preview' : 'camera-preview'}`} />
            <div className="local-video-overlay">
              <span className="local-video-label">{callState.isScreenSharing ? 'Tu pantalla' : 'Tú'}</span>
              {callState.isMuted && <span className="muted-indicator">🔇</span>}
            </div>
          </div>
        </div>

        {error ? <p className="call-media-error" role="status">{error}</p> : null}
        {showSettings ? <MediaDeviceDialog title="Sonido" label="Ajustes de sonido de la llamada" onClose={() => setShowSettings(false)}><AudioOutputSelect devices={devices} value={outputDevice} onChange={setOutputDevice} /></MediaDeviceDialog> : null}
        <div className="call-controls">
          {callState.isIncomingCall ? (
            <div className="incoming-controls">
              <button
                className="control-btn reject-btn"
                onClick={rejectCall}
                disabled={isInitializing}
              >
                <PhoneOff size={20}/> Rechazar
              </button>
              <button
                className="control-btn answer-btn"
                onClick={answerCall}
                disabled={isInitializing}
              >
                <Phone size={20}/>{isInitializing ? 'Conectando…' : 'Contestar'}
              </button>
            </div>
          ) : (
            <div className="active-controls">
              {typeof document.documentElement.requestFullscreen === 'function' ? <button type="button" className="control-btn" aria-label="Ampliar llamada" onClick={() => void (document.fullscreenElement ? document.exitFullscreen() : container.current?.requestFullscreen())?.catch(() => {})}><Maximize/><span>Ampliar</span></button> : null}
              <button
                className={`control-btn ${callState.isMuted ? 'muted' : ''}`}
                type="button"
                onClick={toggleMute}
                aria-pressed={callState.isMuted}
                aria-label={callState.isMuted ? 'Activar micrófono' : 'Silenciar micrófono'}
                title={callState.isMuted ? 'Activar micrófono' : 'Desactivar micrófono'}
              >
                {callState.isMuted ? <MicOff/> : <Mic/>}<span>{callState.isMuted ? 'Silenciado' : 'Micrófono'}</span>
              </button>
              
              <button
                className={`control-btn ${callState.isVideoEnabled ? 'video-on' : 'video-off'}`}
                type="button"
                onClick={() => void toggleVideo()}
                disabled={mediaBusy}
                aria-pressed={callState.isVideoEnabled}
                aria-label={callState.isVideoEnabled ? 'Apagar cámara' : 'Activar cámara'}
                title={callState.isVideoEnabled ? 'Desactivar vídeo' : 'Activar vídeo'}
              >
                {callState.isVideoEnabled ? <Video/> : <VideoOff/>}<span>Cámara</span>
              </button>
              
              {typeof navigator.mediaDevices?.getDisplayMedia === 'function' ? <button
                className={`control-btn ${callState.isScreenSharing ? 'screen-sharing' : ''}`}
                type="button"
                onClick={() => void toggleScreenShare()}
                disabled={mediaBusy}
                aria-pressed={callState.isScreenSharing}
                aria-label={callState.isScreenSharing ? 'Detener pantalla' : 'Compartir pantalla'}
                title={callState.isScreenSharing ? 'Dejar de compartir pantalla' : 'Compartir pantalla'}
              >
                <MonitorUp/><span>{callState.isScreenSharing ? 'Detener' : 'Pantalla'}</span>
              </button> : null}
              <button type="button" className={`control-btn ${isDeafened ? 'muted' : ''}`} aria-label={isDeafened ? 'Escuchar llamada' : 'Silenciar audio de llamada'} aria-pressed={isDeafened} onClick={() => setIsDeafened(value => !value)}>{isDeafened ? <VolumeX/> : <Volume2/>}<span>Audio</span></button>
              <button type="button" className="control-btn" aria-label="Ajustes de sonido" aria-expanded={showSettings} onClick={() => setShowSettings(value => !value)}><Settings/><span>Ajustes</span></button>
              
              <button
                className="control-btn end-call-btn"
                type="button"
                aria-label="Colgar llamada"
                onClick={endCall}
              >
                <PhoneOff/><span>Colgar</span>
              </button>
            </div>
          )}
        </div>
      </main>
    </div>
  )
}
