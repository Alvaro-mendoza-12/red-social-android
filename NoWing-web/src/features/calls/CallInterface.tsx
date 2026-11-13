import { SafeAvatar } from '../../components/SafeAvatar'
import { Mic, MicOff, Video, VideoOff, MonitorUp, Phone, PhoneOff, Maximize, Minimize2, Settings, Volume2, VolumeX, UserRoundPlus } from 'lucide-react'
import { CallInviteDialog } from './CallInviteDialog'
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
import { publicBackendTransportUrl } from '../../core/publicBackendTransport'
import WebCallManager, { type CallState, type CallParticipant } from './WebCallManager'

type CallInterfaceProps = {
  initialContextId?: string
  initialCallId?: string
  initialRemoteUserId?: string
  initialRemoteName?: string
  initialVideoEnabled?: boolean
  joinExisting?: boolean
}

function callFailureMessage(cause: unknown) {
  if (cause instanceof DOMException) {
    if (cause.name === 'NotAllowedError') return 'Permite el acceso al micrófono y, para videollamadas, a la cámara en tu navegador.'
    if (cause.name === 'NotFoundError') return 'No encontramos el dispositivo de audio o video. Conéctalo y vuelve a intentarlo.'
    if (cause.name === 'NotReadableError') return 'Otra aplicación podría estar usando el micrófono o la cámara. Libera el dispositivo y vuelve a intentarlo.'
  }
  return cause instanceof Error && !/^[A-Z_]+$/.test(cause.message) ? cause.message : 'No se pudo conectar la llamada. Revisa tu conexión e inténtalo de nuevo.'
}

function RemoteParticipantTile({ participant, deafened, outputDevice }: { participant: CallParticipant; deafened: boolean; outputDevice: string }) {
  const hasVideo = useLiveVideoAvailable(participant.stream ?? null) && participant.isVideoEnabled
  return <div className="video-wrapper remote-video" data-participant-id={participant.id}>
    <LiveStreamVideo stream={participant.stream ?? null} label={participant.name} muted={deafened} outputDeviceId={outputDevice} className="remote-video-element"/>
    {!hasVideo ? <div className="call-participant-placeholder"><SafeAvatar size="lg" url={participant.avatarUrl} name={participant.name}/><strong>{participant.name}</strong><small>{participant.stream ? 'Llamada de voz' : 'Esperando respuesta…'}</small></div> : null}
    <div className="participant-overlay"><div className="participant-info"><SafeAvatar url={participant.avatarUrl} name={participant.name} className="participant-avatar"/><span className="participant-name">{participant.name}</span></div><div className="participant-status">{!participant.isAudioEnabled ? <MicOff size={18} aria-label="Micrófono silenciado"/> : null}{!hasVideo ? <VideoOff size={18} aria-label="Cámara apagada"/> : null}</div></div>
  </div>
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
  const remoteUserId = initialRemoteUserId
  const videoEnabled = initialVideoEnabled
  const [participantInfo, setParticipantInfo] = useState<CallParticipant | null>(null)
  const [localStream, setLocalStream] = useState<MediaStream | null>(null)
  const [remoteStream, setRemoteStream] = useState<MediaStream | null>(null)
  const [isInitializing, setIsInitializing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const localHasVideo = useLiveVideoAvailable(localStream)
  const remoteParticipant = callState?.remoteParticipant || participantInfo
  const participants = callState?.participants?.length ? callState.participants : remoteParticipant ? [remoteParticipant] : []
  const [showInvites, setShowInvites] = useState(false)
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
  const signalingBaseUrl = publicBackendTransportUrl(String(import.meta.env.VITE_CALL_SIGNALING_URL ?? import.meta.env.VITE_SIGNALING_URL ?? '').trim())

  useEffect(() => {
    if (!isFallbackSession) {
      queueMicrotask(() => setFallbackAccessToken(''))
      return
    }
    let cancelled = false
    void ensureFallbackAccessToken()
      .then((token) => {
        if (cancelled) return
        if (token) setFallbackAccessToken(token)
        else setError('Vuelve a iniciar sesión para hacer la llamada.')
      })
      .catch(() => { if (!cancelled) setError('No se pudo preparar la llamada. Revisa tu conexión y vuelve a intentarlo.') })
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
      queueMicrotask(() => { if (active) setCallManager(manager) })

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
          if (persisted && event.notifyRemote) {
            await Promise.allSettled(event.remoteUserIds.map(target => sendCallResponse(supabase, target, { callId: event.callId, action: event.action, fromUserId: user.id }, accessToken)))
          }
        }).catch(() => undefined)
        if (reason === 'cleanup' || !active) return
        setCallState(null)
        setParticipantInfo(null)
        setLocalStream(null)
        setRemoteStream(null)

        if (reason === 'start_failed') return
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
      const participant: CallParticipant = {
        id: remoteUserId,
        name: initialRemoteName || 'Contacto',
        avatarUrl: '',
        isAudioEnabled: true,
        isVideoEnabled: videoEnabled,
        isSpeaking: false,
      }

      await callManager.initiateCall(callId, remoteUserId, {
        name: participant.name,
        avatarUrl: participant.avatarUrl,
        videoEnabled,
        contextId,
      })

      await sendCallInvite(supabase, remoteUserId, {
        callId,
        title: displayName,
        callerUserId: user.id,
        callerName: displayName,
        callerAvatarUrl: avatarUrl,
        contextType: 'dm',
        contextId,
        isVideo: videoEnabled,
      }, accessToken)

      setParticipantInfo(participant)
      setIsInitializing(false)
    } catch (error) {
      callManager.endCall('start_failed')
      setError(callFailureMessage(error))
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
      setError(callFailureMessage(error))
      setIsInitializing(false)
    }
  }, [accessToken, callManager, initialCallId, initialRemoteName, initialRemoteUserId, user, videoEnabled])

  useEffect(() => {
    if (!callManager || autoStartedRef.current) return
    if (!(joinExisting ? initialCallId : initialRemoteUserId)) return
    const timer = window.setTimeout(() => {
      autoStartedRef.current = true
      void (joinExisting ? joinCall() : initiateCall())
    }, 0)
    return () => window.clearTimeout(timer)
  }, [callManager, initialCallId, initialRemoteUserId, initiateCall, joinCall, joinExisting])

  useEffect(() => {
    if (!user || !callState?.currentCallId) return
    const currentCallId = callState.currentCallId
    const receive = (payload: { callId?: string; action?: string }) => {
      if (payload.callId !== currentCallId) return
      void callManager?.receiveResponse(currentCallId, payload.action ?? '')
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
          <button className="back-btn" onClick={() => navigate('/app/calls', { replace: true })}>
            Volver a contactos
          </button>
        </div>
      </div>
    )
  }

  if (!callState) {
    return <div className="call-interface-container call-preparing">
      <p role="status">{isInitializing ? 'Conectando…' : 'Preparando llamada…'}</p>
      <button type="button" className="back-btn" onClick={() => navigate('/app/calls', { replace: true })}>Volver a contactos</button>
    </div>
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
            {participants.length > 1 ? `Llamada grupal · ${participants.length + 1} personas` : remoteParticipant?.name || initialRemoteName || (callState.isIncomingCall ? 'Llamada entrante' : 'Llamada')}
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
        <div className={`videos-container${participants.length > 1 ? ' group-call-grid' : ''}`}>
          {participants.map(participant => <RemoteParticipantTile key={participant.id} participant={participant} deafened={isDeafened} outputDevice={outputDevice}/>)}

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
        {showInvites && callState.isOwner ? <CallInviteDialog excludedIds={[user.id, ...participants.map(participant => participant.id)]} onClose={() => setShowInvites(false)} onInvite={async contact => {
          if (!callManager || !callState.currentCallId) throw new Error('La llamada terminó.')
          const invitation = await callManager.inviteParticipant(contact.id)
          if (invitation.state === 'accepted') throw new Error('Esta persona ya está en la llamada.')
          await sendCallInvite(supabase, invitation.inviteeId, { callId: callState.currentCallId, title: displayName, callerUserId: user.id, callerName: displayName, callerAvatarUrl: avatarUrl, contextType: 'dm', contextId: '', isVideo: callState.isVideoEnabled }, accessToken)
          return invitation.expiresAt
        }}/> : null}
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
              {callState.isOwner ? <button type="button" className="control-btn" aria-label="Invitar personas a la llamada" disabled={callState.isOutgoingCall} onClick={() => setShowInvites(true)}><UserRoundPlus aria-hidden="true"/><span>Invitar</span></button> : null}
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
