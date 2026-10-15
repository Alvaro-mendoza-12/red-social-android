import { StreamingVideo } from '../../components/StreamingMedia'
import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import {
  ArrowLeft,
  Camera,
  CameraOff,
  Link as LinkIcon,
  MessageCircle,
  Mic,
  MicOff,
  MonitorUp,
  Heart,
  Volume2,
  VolumeX,
  PhoneOff,
  Radio,
  Send,
  Users,
} from 'lucide-react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { io, type Socket } from 'socket.io-client'
import { SafeAvatar } from '../../components/SafeAvatar'
import { StatusView } from '../../components/StatusView'
import { ensureFallbackAccessToken } from '../../core/auth/fallbackAuth'
import { useAuth } from '../../core/auth/AuthProvider'
import {
  loadFallbackLiveComments,
  loadFallbackLiveStream,
  sendFallbackLiveComment,
  updateFallbackLiveState,
  type FallbackLiveComment,
  type FallbackLiveStream,
} from '../../core/fallbackData'
import { getRuntimeBackendUrl } from '../../core/runtimeBackend'
import { buildSignalingAffinity } from '../../core/signalingAffinity'
import { subscribeUbuntuRealtime } from '../../core/ubuntuRealtime'
import { friendlyError, numberOf, textOf, type GenericRow } from '../../core/utils'
import WebRTCManager from '../voice/WebRTCManager'
import './live.css'

type LiveRole = 'host' | 'viewer'
type LiveSource = 'camera' | 'screen'
type LiveState = {
  source: LiveSource
  isPaused: boolean
  videoEnabled: boolean
  micEnabled: boolean
}
type SignalParticipant = { socketId: string; userId?: string; name?: string; avatarUrl?: string }
type SignalPayload = { sender: string; userId?: string; name?: string }
type OfferPayload = SignalPayload & { offer: RTCSessionDescriptionInit }
type AnswerPayload = SignalPayload & { answer: RTCSessionDescriptionInit }
type IcePayload = SignalPayload & { candidate: RTCIceCandidateInit }
type LiveReaction = { id: string; emoji: string }

type ServerEvents = {
  connect: () => void
  disconnect: (reason: string) => void
  connect_error: (error: Error) => void
  'auth-error': (payload: { error?: string }) => void
  'current-viewers': (participants: SignalParticipant[]) => void
  'viewer-joined': (participant: SignalParticipant) => void
  'viewer-left': (socketId: string) => void
  'host-info': (participant: SignalParticipant) => void
  'viewer-count': (payload: { viewerCount?: number }) => void
  'reaction-count': (payload: { reactionCount?: number }) => void
  'live-state': (payload: Partial<LiveState>) => void
  'live-ended': () => void
  reaction: (payload: { emoji?: string }) => void
  offer: (payload: OfferPayload) => void
  answer: (payload: AnswerPayload) => void
  'ice-candidate': (payload: IcePayload) => void
}

type ClientEvents = {
  'join-live': (payload: {
    liveId: string
    userId: string
    name: string
    avatarUrl: string
    role: LiveRole
    source: LiveSource
    isPaused: boolean
    videoEnabled: boolean
    micEnabled: boolean
  }) => void
  'end-live': (payload: { liveId: string }) => void
  'send-reaction': (payload: { liveId: string; emoji: string; userId: string }) => void
  'update-live-state': (payload: LiveState & { liveId: string }) => void
  offer: (payload: { target: string; offer: RTCSessionDescriptionInit; userId: string; name: string }) => void
  answer: (payload: { target: string; answer: RTCSessionDescriptionInit; userId: string; name: string }) => void
  'ice-candidate': (payload: { target: string; candidate: RTCIceCandidateInit; userId: string; name: string }) => void
}

type LiveSocket = Socket<ServerEvents, ClientEvents>

function asRecord(value: unknown): GenericRow | null {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as GenericRow : null
}

function extractIceServers(payload: unknown): RTCIceServer[] {
  const envelope = asRecord(payload)
  const nestedV = asRecord(envelope?.v)
  const nestedD = asRecord(envelope?.d)
  const candidate =
    (Array.isArray(envelope?.iceServers) && envelope.iceServers) ||
    (Array.isArray(nestedV?.iceServers) && nestedV.iceServers) ||
    (Array.isArray(nestedD?.iceServers) && nestedD.iceServers) ||
    []
  return candidate.filter((entry): entry is RTCIceServer => {
    const record = asRecord(entry)
    const urls = record?.urls
    return typeof urls === 'string' || (Array.isArray(urls) && urls.every((url) => typeof url === 'string'))
  })
}

function sourceFrom(value: string | null | undefined): LiveSource {
  return value === 'screen' ? 'screen' : 'camera'
}

function displayNameOf(user: ReturnType<typeof useAuth>['user']): string {
  if (!user) return 'Usuario'
  return String(user.user_metadata?.display_name || user.user_metadata?.full_name || 'Usuario').trim()
}

function avatarOf(user: ReturnType<typeof useAuth>['user']): string {
  return String(user?.user_metadata?.avatar_url || '').trim()
}

function StreamVideo({ stream, muted, className }: { stream: MediaStream | null; muted: boolean; className: string }) {
  const ref = useRef<HTMLVideoElement | null>(null)
  useEffect(() => {
    const element = ref.current
    if (!element) return
    element.srcObject = stream
    if (stream) void element.play().catch(() => {})
    return () => {
      element.srcObject = null
    }
  }, [stream])
  return <StreamingVideo ref={ref} className={className} autoPlay playsInline muted={muted} />
}

export function LiveSessionPage() {
  const { liveId = '' } = useParams()
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const { user, isFallbackSession } = useAuth()
  const role: LiveRole = searchParams.get('host') === '1' ? 'host' : 'viewer'
  const initialSource = sourceFrom(searchParams.get('source'))
  const [stream, setStream] = useState<FallbackLiveStream | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [connectionLabel, setConnectionLabel] = useState('Preparando')
  const [viewerCount, setViewerCount] = useState(0)
  const [reactionCount, setReactionCount] = useState(0)
  const [viewerMuted, setViewerMuted] = useState(true)
  const [liveState, setLiveState] = useState<LiveState>({
    source: initialSource,
    isPaused: false,
    videoEnabled: true,
    micEnabled: true,
  })
  const [localMedia, setLocalMedia] = useState<MediaStream | null>(null)
  const hostCaptureRef = useRef<Promise<MediaStream> | null>(null)
  const [hostCaptureAttempt, setHostCaptureAttempt] = useState(0)
  const [remoteMedia, setRemoteMedia] = useState<MediaStream | null>(null)
  const [comments, setComments] = useState<FallbackLiveComment[]>([])
  const [commentDraft, setCommentDraft] = useState('')
  const [sendingComment, setSendingComment] = useState(false)
  const [reactions, setReactions] = useState<LiveReaction[]>([])
  const socketRef = useRef<LiveSocket | null>(null)
  const managerRef = useRef<WebRTCManager | null>(null)
  const pendingIceRef = useRef(new Map<string, RTCIceCandidateInit[]>())
  const endingRef = useRef(false)
  const liveStateRef = useRef(liveState)
  const cleanLiveId = liveId.trim()
  const userId = user?.id || ''
  const streamId = stream?.id || ''
  const displayName = displayNameOf(user)
  const avatarUrl = avatarOf(user)
  const hostName = stream ? textOf(stream, ['host_name'], 'Creador') : 'Creador'
  const isOwner = Boolean(user && stream && textOf(stream, ['host_user_id'], '') === user.id)

  function beginBroadcast() {
    if (role !== 'host' || !isOwner || hostCaptureRef.current) return
    setError(null)
    // Screen capture must start in this click, before network requests consume user activation.
    const capture = async () => {
      if (liveStateRef.current.source === 'screen') {
        if (!navigator.mediaDevices?.getDisplayMedia) throw new Error('Este navegador no permite compartir pantalla. Prueba en Chrome o Edge desde una computadora.')
        const display = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: true })
        if (display.getAudioTracks().length === 0) {
          try {
            const mic = await navigator.mediaDevices.getUserMedia({ audio: true, video: false })
            mic.getAudioTracks().forEach(track => display.addTrack(track))
          } catch { /* Screen sharing also works without a microphone. */ }
        }
        return display
      }
      try { return await navigator.mediaDevices.getUserMedia({ video: true, audio: true }) }
      catch { return navigator.mediaDevices.getUserMedia({ video: true, audio: false }) }
    }
    const request = capture()
    void request.catch(() => {})
    hostCaptureRef.current = request
    setHostCaptureAttempt(current => current + 1)
  }

  useEffect(() => {
    liveStateRef.current = liveState
  }, [liveState])

  const loadComments = useCallback(async () => {
    if (!cleanLiveId || !isFallbackSession) return
    try {
      const response = await loadFallbackLiveComments(cleanLiveId, 120)
      setComments(response.items)
    } catch {
      // Realtime or the next polling cycle can recover comments without interrupting video.
    }
  }, [cleanLiveId, isFallbackSession])

  useEffect(() => {
    if (!cleanLiveId || !isFallbackSession) {
      setLoading(false)
      return
    }
    let active = true
    void loadFallbackLiveStream(cleanLiveId)
      .then((response) => {
        if (!active) return
        setStream(response.stream)
        setViewerCount(numberOf(response.stream, ['viewer_count'], 0))
        setReactionCount(numberOf(response.stream, ['total_reactions'], 0))
        setLiveState((current) => ({ ...current, source: sourceFrom(textOf(response.stream, ['source'], initialSource)) }))
      })
      .catch((caught) => active && setError(friendlyError(caught, 'No se pudo abrir el directo')))
      .finally(() => active && setLoading(false))
    return () => {
      active = false
    }
  }, [cleanLiveId, initialSource, isFallbackSession])

  useEffect(() => {
    void loadComments()
    if (!cleanLiveId || !isFallbackSession) return
    const unsubscribe = subscribeUbuntuRealtime({
      channel: `live-comments:${cleanLiveId}`,
      onEvent: () => void loadComments(),
    })
    const interval = window.setInterval(() => void loadComments(), 8_000)
    return () => {
      unsubscribe()
      window.clearInterval(interval)
    }
  }, [cleanLiveId, isFallbackSession, loadComments])

  const addIceCandidate = useCallback(async (peerId: string, candidate: RTCIceCandidateInit) => {
    const manager = managerRef.current
    if (!manager?.hasPeerConnection(peerId)) {
      pendingIceRef.current.set(peerId, [...(pendingIceRef.current.get(peerId) ?? []), candidate])
      return
    }
    try {
      await manager.handleIceCandidate(peerId, candidate)
    } catch {
      pendingIceRef.current.set(peerId, [...(pendingIceRef.current.get(peerId) ?? []), candidate])
    }
  }, [])

  const drainIce = useCallback(async (peerId: string) => {
    const manager = managerRef.current
    const queued = pendingIceRef.current.get(peerId) ?? []
    if (!manager || queued.length === 0) return
    pendingIceRef.current.delete(peerId)
    for (const candidate of queued) {
      try {
        await manager.handleIceCandidate(peerId, candidate)
      } catch {
        pendingIceRef.current.set(peerId, [candidate, ...(pendingIceRef.current.get(peerId) ?? [])])
        break
      }
    }
  }, [])

  const startOffer = useCallback(async (peerId: string) => {
    const manager = managerRef.current
    if (!manager || !peerId || manager.hasPeerConnection(peerId)) return
    try {
      manager.createPeerConnection(peerId, true)
      await manager.createOffer(peerId)
      await drainIce(peerId)
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo conectar con un espectador'))
      manager.disconnectPeer(peerId)
    }
  }, [drainIce])

  useEffect(() => {
    if (!streamId || !userId || !isFallbackSession || !cleanLiveId) return
    if (role === 'host' && !isOwner) {
      setError('Esta cuenta no es propietaria del directo.')
      return
    }
    if (role === 'host' && !hostCaptureRef.current) return
    let token = ''
    let disposed = false
    const captureForAttempt = hostCaptureRef.current
    const currentUser = { id: userId }
    const manager = new WebRTCManager(currentUser.id)
    const pendingIce = pendingIceRef.current
    managerRef.current = manager

    async function configureTurn() {
      try {
        const response = await fetch(`${getRuntimeBackendUrl().replace(/\/$/, '')}/turn-credentials`, {
          headers: { Accept: 'application/json', Authorization: `Bearer ${token}` },
          signal: AbortSignal.timeout(6000),
        })
        if (!response.ok) return
        const servers = extractIceServers(await response.json())
        if (servers.length > 0) manager.setIceServers(servers)
      } catch {
        // The manager retains its STUN fallback.
      }
    }

    async function prepareHostMedia() {
      if (role !== 'host') return
      const capture = captureForAttempt
      if (!capture) return
      const media = await capture
      if (disposed) { media.getTracks().forEach(track => track.stop()); return }
      manager.setLocalStream(media, false)
      const next = { ...liveStateRef.current, micEnabled: media.getAudioTracks().length > 0 }
      liveStateRef.current = next
      setLiveState(next)
      setLocalMedia(media)
      media.getVideoTracks()[0]?.addEventListener('ended', () => {
        if (disposed) return
        const stopped = { ...liveStateRef.current, isPaused: true, videoEnabled: false }
        liveStateRef.current = stopped
        setLiveState(stopped)
        socketRef.current?.emit('update-live-state', { liveId: cleanLiveId, ...stopped })
        setConnectionLabel('La pantalla dejó de compartirse')
      }, { once: true })
    }

    async function connect() {
      try {
        token = await ensureFallbackAccessToken()
        if (!token) throw new Error('No hay una sesión de producción activa.')
        setConnectionLabel('Solicitando permisos')
        await configureTurn()
        await prepareHostMedia()
        if (disposed) { manager.stopLocalStream(); return }

        manager.onTrack((_track, media) => {
          if (role === 'viewer') setRemoteMedia(media)
        })
        manager.onConnectionState((state) => {
          if (state === 'connected') setConnectionLabel('En vivo')
          if (state === 'failed' || state === 'disconnected') setConnectionLabel('Reconectando')
        })

        const socket: LiveSocket = io(`${getRuntimeBackendUrl().replace(/\/$/, '')}/live`, {
          transports: ['websocket', 'polling'],
          query: { affinity: buildSignalingAffinity('live', cleanLiveId) },
          auth: { token },
          autoConnect: false,
          reconnection: true,
          reconnectionAttempts: 10,
          reconnectionDelay: 700,
          timeout: 12_000,
        })
        socketRef.current = socket
        manager.onIceCandidate((target, candidate) => {
          socket.emit('ice-candidate', { target, candidate, userId: currentUser.id, name: displayName })
        })
        manager.onOfferNeeded((target, offer) => {
          socket.emit('offer', { target, offer, userId: currentUser.id, name: displayName })
        })

        socket.on('connect', () => {
          setConnectionLabel(role === 'host' ? 'Emitiendo' : 'Conectando al directo')
          socket.emit('join-live', {
            liveId: cleanLiveId,
            userId: currentUser.id,
            name: displayName,
            avatarUrl,
            role,
            source: liveStateRef.current.source,
            isPaused: liveStateRef.current.isPaused,
            videoEnabled: liveStateRef.current.videoEnabled,
            micEnabled: liveStateRef.current.micEnabled,
          })
        })
        socket.on('connect_error', (caught) => {
          setConnectionLabel('Reconectando')
          setError(friendlyError(caught, 'No se pudo conectar con el directo. Reintentando…'))
        })
        socket.on('disconnect', () => setConnectionLabel('Reconectando'))
        socket.on('auth-error', (payload) => setError(payload.error || 'La sesión del directo fue rechazada'))
        socket.on('current-viewers', (participants) => {
          if (role !== 'host') return
          participants.forEach((participant) => void startOffer(participant.socketId))
        })
        socket.on('viewer-joined', (participant) => {
          if (role === 'host') void startOffer(participant.socketId)
        })
        socket.on('viewer-left', (socketId) => manager.disconnectPeer(socketId))
        socket.on('host-info', () => setConnectionLabel('Esperando video'))
        socket.on('viewer-count', (payload) => setViewerCount(Math.max(0, Number(payload.viewerCount || 0))))
        socket.on('reaction-count', payload => setReactionCount(Math.max(0, Number(payload.reactionCount) || 0)))
        socket.on('live-state', (payload) => {
          setLiveState((current) => {
            const next = {
              source: sourceFrom(payload.source || current.source),
              isPaused: Boolean(payload.isPaused),
              videoEnabled: payload.videoEnabled !== false,
              micEnabled: payload.micEnabled !== false,
            }
            liveStateRef.current = next
            return next
          })
        })
        socket.on('live-ended', () => {
          if (endingRef.current) return
          setError('El directo terminó.')
          setConnectionLabel('Finalizado')
          window.setTimeout(() => navigate('/app/live', { replace: true }), 1200)
        })
        socket.on('reaction', (payload) => {
          const reaction = { id: crypto.randomUUID(), emoji: String(payload.emoji || 'Me gusta') }
          setReactions((current) => [...current.slice(-14), reaction])
          window.setTimeout(() => setReactions((current) => current.filter((item) => item.id !== reaction.id)), 2200)
        })
        socket.on('offer', (payload) => {
          if (role !== 'viewer' || !payload.sender || !payload.offer) return
          void (async () => {
            try {
              if (!manager.hasPeerConnection(payload.sender)) manager.createPeerConnection(payload.sender, false)
              const answer = await manager.createAnswer(payload.sender, payload.offer)
              if (!answer) return
              socket.emit('answer', { target: payload.sender, answer, userId: currentUser.id, name: displayName })
              await drainIce(payload.sender)
            } catch (caught) {
              setError(friendlyError(caught, 'No se pudo recibir el video del directo'))
            }
          })()
        })
        socket.on('answer', (payload) => {
          if (role !== 'host' || !payload.sender || !payload.answer) return
          void manager.handleAnswer(payload.sender, payload.answer)
            .then(() => drainIce(payload.sender))
            .catch((caught) => setError(friendlyError(caught, 'No se pudo completar la conexión del espectador')))
        })
        socket.on('ice-candidate', (payload) => {
          if (payload.sender && payload.candidate) void addIceCandidate(payload.sender, payload.candidate)
        })
        socket.connect()
      } catch (caught) {
        if (disposed) return
        if (hostCaptureRef.current === captureForAttempt) hostCaptureRef.current = null
        manager.stopLocalStream()
        setConnectionLabel('Permiso requerido')
        setError(friendlyError(caught, role === 'host' ? 'No se pudo acceder a la cámara o pantalla' : 'No se pudo abrir el directo'))
      }
    }

    void connect()
    return () => {
      disposed = true
      socketRef.current?.disconnect()
      socketRef.current = null
      manager.disconnectAll()
      manager.stopLocalStream()
      if (role === 'host') void captureForAttempt?.then(media => media.getTracks().forEach(track => track.stop())).catch(() => {})
      managerRef.current = null
      pendingIce.clear()
    }
  }, [addIceCandidate, avatarUrl, cleanLiveId, displayName, drainIce, hostCaptureAttempt, isFallbackSession, isOwner, navigate, role, startOffer, streamId, userId])

  const emitState = useCallback((next: LiveState) => {
    liveStateRef.current = next
    setLiveState(next)
    socketRef.current?.emit('update-live-state', { liveId: cleanLiveId, ...next })
  }, [cleanLiveId])

  async function switchVideoSource(nextSource: LiveSource) {
    if (role !== 'host' || !managerRef.current) return
    try {
      const media = nextSource === 'screen'
        ? await navigator.mediaDevices.getDisplayMedia({ video: true, audio: false })
        : await navigator.mediaDevices.getUserMedia({ video: true, audio: false })
      const track = media.getVideoTracks()[0]
      if (!track) throw new Error('No se recibió una pista de video')
      await managerRef.current.replaceOutgoingTrack('video', track)
      const current = managerRef.current.getLocalStream()
      setLocalMedia(current ? new MediaStream(current.getTracks()) : media)
      const next = { ...liveState, source: nextSource, isPaused: false, videoEnabled: true }
      emitState(next)
      await updateFallbackLiveState(cleanLiveId, { source: nextSource })
      if (nextSource === 'screen') {
        track.addEventListener('ended', () => void switchVideoSource('camera'), { once: true })
      }
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo cambiar la fuente de video'))
    }
  }

  function toggleMic() {
    const nextEnabled = !liveState.micEnabled
    managerRef.current?.getLocalStream()?.getAudioTracks().forEach((track) => { track.enabled = nextEnabled })
    emitState({ ...liveState, micEnabled: nextEnabled })
  }

  function toggleVideo() {
    const nextEnabled = !liveState.videoEnabled
    managerRef.current?.getLocalStream()?.getVideoTracks().forEach((track) => { track.enabled = nextEnabled })
    emitState({ ...liveState, videoEnabled: nextEnabled, isPaused: !nextEnabled })
  }

  function sendReaction(emoji: string) {
    if (!user) return
    socketRef.current?.emit('send-reaction', { liveId: cleanLiveId, emoji, userId: user.id })
  }

  async function submitComment(event: FormEvent) {
    event.preventDefault()
    const content = commentDraft.trim()
    if (!content || sendingComment) return
    setSendingComment(true)
    try {
      const response = await sendFallbackLiveComment(cleanLiveId, content)
      setComments((current) => [...current.filter((item) => item.id !== response.comment.id), response.comment])
      setCommentDraft('')
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo enviar el comentario'))
    } finally {
      setSendingComment(false)
    }
  }

  async function leaveOrEnd() {
    if (endingRef.current) return
    endingRef.current = true
    try {
      if (role === 'host') {
        socketRef.current?.emit('end-live', { liveId: cleanLiveId })
        await updateFallbackLiveState(cleanLiveId, {
          isActive: false,
          viewerCount: 0,
          peakViewerCount: viewerCount,
          totalComments: comments.length,
        }).catch(() => null)
      }
    } finally {
      navigate('/app/live', { replace: true })
    }
  }

  async function shareLive() {
    const url = window.location.href.replace(/([?&])host=1(&|$)/, '$1').replace(/[?&]$/, '')
    try {
      if (navigator.share) await navigator.share({ title: stream ? textOf(stream, ['title'], 'Directo') : 'Directo', url })
      else await navigator.clipboard.writeText(url)
    } catch {
      // Native share cancellation needs no error state.
    }
  }

  const media = role === 'host' ? localMedia : remoteMedia
  const reactionButtons = useMemo(() => ['👏', '🔥', '😮'], [])

  if (!isFallbackSession) return <StatusView title="Directo no disponible" description="Inicia sesión con el servidor de producción." />
  if (loading) return <StatusView title="Abriendo directo" description="Preparando la sesión segura..." />
  if (!stream) return <StatusView title="Directo no encontrado" description={error || 'La emisión terminó o ya no está disponible.'} />

  return (
    <section className="live-session-page">
      <div className="live-video-stage" data-source={liveState.source} data-video-disabled={!liveState.videoEnabled || liveState.isPaused}>
        <StreamVideo stream={media} muted={role === 'host' || viewerMuted} className="live-main-video" />
        {!media || !liveState.videoEnabled || liveState.isPaused ? (
          <div className="live-video-placeholder">
            <SafeAvatar url={textOf(stream, ['host_avatar_url'], '')} name={hostName} />
            <strong>{liveState.isPaused ? 'Emisión pausada' : role === 'host' && !hostCaptureRef.current ? 'Listo para transmitir' : connectionLabel}</strong>
            {role === 'host' && isOwner && !hostCaptureRef.current ? <button type="button" className="live-start-button" onClick={beginBroadcast}>{liveState.source === 'screen' ? 'Compartir pantalla y comenzar' : 'Activar cámara y comenzar'}</button> : null}
          </div>
        ) : null}

        <div className="live-session-topbar">
          <button className="live-icon-button" type="button" onClick={() => void leaveOrEnd()} title="Volver" aria-label="Volver"><ArrowLeft aria-hidden="true" /></button>
          <Link className="live-host-link" to={textOf(stream, ['host_user_id'], '') ? `/app/profile/${encodeURIComponent(textOf(stream, ['host_user_id'], ''))}` : '/app/live'}>
            <SafeAvatar url={textOf(stream, ['host_avatar_url'], '')} name={hostName} />
            <span><strong>{hostName}</strong><small><Radio aria-hidden="true" /> {connectionLabel}</small></span>
          </Link>
          <span className="live-session-viewers"><Users aria-hidden="true" /> {viewerCount}</span>
          <button className="live-icon-button" type="button" onClick={() => void shareLive()} title="Compartir" aria-label="Compartir directo"><LinkIcon aria-hidden="true" /></button>
        </div>

        <div className="live-reaction-overlay" aria-live="polite">
          {reactions.map((reaction) => <span key={reaction.id}>{reaction.emoji}</span>)}
        </div>

        <aside className="live-comments-panel">
          <div className="live-comments-list">
            {comments.slice(-60).map((comment) => (
              <div className="live-comment" key={comment.id}>
                <SafeAvatar url={textOf(comment, ['avatar_url'], '')} name={textOf(comment, ['user_name'], 'Usuario')} />
                <p><strong>{textOf(comment, ['user_name'], 'Usuario')}</strong> {textOf(comment, ['content'], '')}</p>
              </div>
            ))}
          </div>
          <form className="live-comment-composer" onSubmit={submitComment}>
            <MessageCircle aria-hidden="true" />
            <input maxLength={300} value={commentDraft} onChange={(event) => setCommentDraft(event.target.value)} placeholder="Comentar..." />
            <button type="submit" disabled={!commentDraft.trim() || sendingComment} title="Enviar" aria-label="Enviar comentario"><Send aria-hidden="true" /></button>
          </form>
        </aside>

        <div className="live-session-controls">
          {role === 'viewer' ? <button className="live-control" type="button" onClick={() => setViewerMuted(current => !current)} aria-label={viewerMuted ? 'Activar sonido' : 'Silenciar directo'} title={viewerMuted ? 'Activar sonido' : 'Silenciar directo'}>{viewerMuted ? <VolumeX /> : <Volume2 />}</button> : null}
          {role === 'host' ? (
            <>
              <button className={liveState.micEnabled ? 'live-control' : 'live-control off'} type="button" onClick={toggleMic} title="Micrófono" aria-label="Alternar micrófono">{liveState.micEnabled ? <Mic aria-hidden="true" /> : <MicOff aria-hidden="true" />}</button>
              <button className={liveState.videoEnabled ? 'live-control' : 'live-control off'} type="button" onClick={toggleVideo} title="Cámara" aria-label="Alternar video">{liveState.videoEnabled ? <Camera aria-hidden="true" /> : <CameraOff aria-hidden="true" />}</button>
              <button className={liveState.source === 'screen' ? 'live-control active' : 'live-control'} type="button" onClick={() => void switchVideoSource(liveState.source === 'screen' ? 'camera' : 'screen')} title="Compartir pantalla" aria-label="Alternar pantalla"><MonitorUp aria-hidden="true" /></button>
              <button className="live-control danger" type="button" onClick={() => void leaveOrEnd()} title="Finalizar" aria-label="Finalizar directo"><PhoneOff aria-hidden="true" /></button>
            </>
          ) : null}
          <button className="live-like-button" type="button" onClick={() => sendReaction('❤️')} aria-label={`Me gusta, ${reactionCount}`}><Heart aria-hidden="true" /><span>{reactionCount.toLocaleString('es')}</span></button>
          {reactionButtons.map((reaction, index) => <button className="live-reaction-button" type="button" key={reaction} aria-label={['Aplausos', 'Fuego', 'Sorpresa'][index]} onClick={() => sendReaction(reaction)}>{reaction}</button>)}
        </div>

        {error ? <div className="live-session-error">{error}</div> : null}
      </div>
    </section>
  )
}
