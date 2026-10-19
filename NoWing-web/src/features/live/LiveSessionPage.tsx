import { ReactionCloud } from '../../components/ReactionCloud'
import { useLiveSfu } from './useLiveSfu'
import { claimInteractiveMediaSession, releaseInteractiveMediaSession, useInteractiveMediaSession } from '../../core/interactiveMediaSession'
import { MediaSessionConflict } from '../../components/MediaSessionConflict'
import { mediaPeerIsInitiator } from '../../core/mediaPeerRole'
import { liveReactionEmoji } from '../../core/liveReaction'
import { publicProfileSummaries } from '../../core/publicProfileSummaries'
import { Settings, Maximize, UserPlus, X } from 'lucide-react'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import { LiveStreamVideo } from '../../components/LiveStreamMedia'
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
  reaction: (payload: { emoji?: string; kind?: string; reactionId?: string }) => void
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
  'send-reaction': (payload: { liveId: string; emoji: string; userId: string; reactionId: string }) => void
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
  return <LiveStreamVideo stream={stream} label="Vídeo del directo" className={className} muted={muted}/>
}

export function LiveSessionPage({ liveIdOverride, initialSearch, minimized = false, onExit }: { liveIdOverride?: string; initialSearch?: string; minimized?: boolean; onExit?: () => void } = {}) {
  const params = useParams()
  const liveId = liveIdOverride || params.liveId || ''
  const [routeSearch] = useSearchParams()
  const searchParams = new URLSearchParams(initialSearch ?? routeSearch.toString())
  const navigate = useNavigate()
  const navigationRef = useRef(navigate); navigationRef.current = navigate
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
  const [sfuAvailable, setSfuAvailable] = useState(false)
  const [capabilitiesReady, setCapabilitiesReady] = useState(false)
  const [settingsBusy, setSettingsBusy] = useState(false)
  const [sourceBusy, setSourceBusy] = useState(false)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [invitations, setInvitations] = useState<GenericRow[]>([])
  const [invitationProfiles, setInvitationProfiles] = useState<Map<string,GenericRow>>(new Map())
  const [guestRequested, setGuestRequested] = useState(false)
  const [guestMedia, setGuestMedia] = useState<MediaStream | null>(null)
  const [guestCamera, setGuestCamera] = useState(true)
  const [guestMic, setGuestMic] = useState(true)
  const [guestBusy, setGuestBusy] = useState(false)
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
  const cleanLiveId = liveId.trim()
  const [closed, setClosed] = useState(false)
  const activeMedia = useInteractiveMediaSession()
  const mediaKey = `live:${user?.id}:${cleanLiveId}`
  const conflict = activeMedia && user && activeMedia.accountId === user.id && activeMedia.key !== mediaKey ? activeMedia : null
  const blocked = Boolean(conflict)
  const closeRef = useRef<() => Promise<void>>(async () => {})
  const eligibleInvitations = useRef(new Set<string>())
  const [comments, setComments] = useState<FallbackLiveComment[]>([])
  const pendingCommentIds = useRef(new Set<string>()), removedCommentIds = useRef(new Set<string>()), commentsRevision = useRef(0)
  const commentScope = useRef(cleanLiveId)
  commentScope.current = cleanLiveId
  useEffect(() => { pendingCommentIds.current.clear(); removedCommentIds.current.clear(); setComments([]) }, [cleanLiveId])
  const [commentDraft, setCommentDraft] = useState('')
  const [sendingComment, setSendingComment] = useState(false)
  const [reactions, setReactions] = useState<LiveReaction[]>([])
  const socketRef = useRef<LiveSocket | null>(null)
  const managerRef = useRef<WebRTCManager | null>(null)
  const pendingIceRef = useRef(new Map<string, RTCIceCandidateInit[]>())
  const endingRef = useRef(false)
  const liveStateRef = useRef(liveState)
  const sourceOperationRef = useRef(0)
  const commentsEndRef = useRef<HTMLDivElement | null>(null)
  const userId = user?.id || ''
  const streamId = stream?.id || ''
  const displayName = displayNameOf(user)
  const avatarUrl = avatarOf(user)
  const hostName = stream ? textOf(stream, ['host_name'], 'Creador') : 'Creador'
  const isOwner = Boolean(user && stream && textOf(stream, ['host_user_id'], '') === user.id)

  const sfu = useLiveSfu({ liveId: cleanLiveId, hostId: textOf(stream || {}, ['host_user_id']), role: role === 'host' ? 'host' : guestMedia ? 'guest' : 'viewer', enabled: !blocked && !closed && sfuAvailable && Boolean(stream?.is_active), localMedia: role === 'host' ? localMedia : guestMedia, source: role === 'host' ? liveState.source : 'camera', micEnabled: role === 'host' ? liveState.micEnabled : guestMic, videoEnabled: role === 'host' ? liveState.videoEnabled && !liveState.isPaused : guestCamera })
  useEffect(() => {
    if (!streamId || !userId || blocked || closed) return
    claimInteractiveMediaSession({ accountId: userId, key: mediaKey, kind: 'live', title: hostName, host: role === 'host', path: `/app/live/${encodeURIComponent(cleanLiveId)}${initialSearch || ''}`, close: () => closeRef.current() })
    return () => releaseInteractiveMediaSession(mediaKey)
  }, [streamId, userId, blocked, closed, mediaKey])
  useEffect(() => {
    let active = true
    setCapabilitiesReady(false)
    void fallbackApiJson<{sfuAvailable:boolean}>('/v1/data/live/capabilities', { expectedAccountId: userId })
      .then(value => { if (active) setSfuAvailable(value.sfuAvailable === true) })
      .catch(() => { if (active) setSfuAvailable(false) })
      .finally(() => { if (active) setCapabilitiesReady(true) })
    return () => { active = false }
  }, [userId])
  const guestMediaRef = useRef(guestMedia), guestCaptureSequence = useRef(0)
  guestMediaRef.current = guestMedia
  useEffect(() => () => { guestCaptureSequence.current++; guestMediaRef.current?.getTracks().forEach(track => track.stop()) }, [cleanLiveId, userId])
  useEffect(() => {
    if (!sfuAvailable || !cleanLiveId || !(isOwner || settingsOpen || guestRequested || guestMedia)) return
    let active = true, pending = false
    const load = async () => {
      if (pending || document.hidden) return
      pending = true
      try {
        const value = await fallbackApiJson<{invitations:GenericRow[]}>(`/v1/data/live/${encodeURIComponent(cleanLiveId)}/guests/invitations`, { expectedAccountId: userId })
        if (!active) return
        setInvitations(value.invitations)
        const profiles = await publicProfileSummaries(value.invitations.map(item => textOf(item,['invitee_user_id'])))
        if (active) setInvitationProfiles(profiles)
      } catch { /* Keep the previous request state while the connection recovers. */ } finally { pending = false }
    }
    void load()
    const timer = window.setInterval(() => void load(), 5000)
    const unsubscribe = subscribeUbuntuRealtime({ channel: `live-collaboration:${cleanLiveId}`, onEvent: () => void load() })
    const visible = () => { if (!document.hidden) void load() }
    document.addEventListener('visibilitychange', visible)
    return () => { active = false; window.clearInterval(timer); unsubscribe(); document.removeEventListener('visibilitychange', visible) }
  }, [sfuAvailable, cleanLiveId, isOwner, settingsOpen, guestRequested, guestMedia, userId])
  const acceptedInvitation = invitations.some(item => eligibleInvitations.current.has(String(item.id)) && item.status === 'accepted' && textOf(item, ['invitee_user_id']).toLowerCase() === userId.toLowerCase())
  const pendingRequests = invitations.filter(item => item.status === 'pending' && item.initiated_by === 'viewer')
  useEffect(() => {
    if (!guestMedia || acceptedInvitation) return
    guestMedia.getTracks().forEach(track => track.stop())
    setGuestMedia(null)
    setGuestRequested(false)
  }, [guestMedia, acceptedInvitation])
  async function requestGuest() {
    if (guestBusy) return; setGuestBusy(true)
    try { const value = await fallbackApiJson<{invitation:GenericRow}>(`/v1/data/live/${encodeURIComponent(cleanLiveId)}/guests/requests`, { method: 'POST', expectedAccountId: userId, body: JSON.stringify({requestNonce:crypto.randomUUID()}) }); eligibleInvitations.current.add(String(value.invitation.id)); setInvitations([value.invitation]); setGuestRequested(true) } catch(caught) { setError(friendlyError(caught,'No se pudo enviar la solicitud.')) } finally { setGuestBusy(false) }
  }
  async function joinAsGuest() {
    if (guestBusy || !acceptedInvitation) return; setGuestBusy(true)
    const operation = ++guestCaptureSequence.current
    try { const capture = guestCamera || guestMic ? await navigator.mediaDevices.getUserMedia({video:guestCamera,audio:guestMic}) : new MediaStream(); if (operation !== guestCaptureSequence.current) { capture.getTracks().forEach(track => track.stop()); return }; setGuestMedia(capture); setSettingsOpen(false) } catch { setError('Revisa los permisos de cámara y micrófono para participar.') } finally { setGuestBusy(false) }
  }
  async function toggleGuestMedia(kind: 'audio' | 'video') {
    if (!guestMedia || guestBusy) return
    const enabled = kind === 'audio' ? guestMic : guestCamera
    const tracks = guestMedia.getTracks().filter(track => track.kind === kind && track.readyState === 'live')
    if (!enabled && !tracks.length) {
      const operation = ++guestCaptureSequence.current; setGuestBusy(true)
      try {
        const capture = await navigator.mediaDevices.getUserMedia({audio:kind === 'audio',video:kind === 'video'})
        if (operation !== guestCaptureSequence.current || !guestMediaRef.current) { capture.getTracks().forEach(track => track.stop()); return }
        const next = new MediaStream([...guestMediaRef.current.getTracks().filter(track => track.readyState === 'live'), ...capture.getTracks()])
        setGuestMedia(next)
      } catch { setError('Permite el acceso a tu cámara o micrófono para activarlo.'); return }
      finally { if (operation === guestCaptureSequence.current) setGuestBusy(false) }
    }
    tracks.forEach(track => { track.enabled = !enabled })
    if (kind === 'audio') setGuestMic(!enabled); else setGuestCamera(!enabled)
  }
  async function respondGuest(id:string,status:'accepted'|'rejected') {
    if(guestBusy)return;setGuestBusy(true)
    try{await fallbackApiJson(`/v1/data/live/${encodeURIComponent(cleanLiveId)}/guests/invitations/${encodeURIComponent(id)}`,{method:'PATCH',expectedAccountId:userId,body:JSON.stringify({status})});setInvitations(current=>current.map(item=>item.id===id?{...item,status}:item))}catch(caught){setError(friendlyError(caught,'No se pudo responder la solicitud.'))}finally{setGuestBusy(false)}
  }
  async function setGuestRequests(allowed:boolean) {
    if (settingsBusy) return
    setSettingsBusy(true)
    try { const value = await updateFallbackLiveState(cleanLiveId,{allowGuestRequests:allowed});setStream(value.stream) } catch(caught) {setError(friendlyError(caught,'No se pudo cambiar esta opción.'))} finally { setSettingsBusy(false) }
  }
  async function setChatPaused(paused: boolean) {
    if (settingsBusy) return
    setSettingsBusy(true)
    try {
      const value = await fallbackApiJson<{moderation: GenericRow}>(`/v1/data/live/${encodeURIComponent(cleanLiveId)}/moderation/settings`, { method: 'PATCH', expectedAccountId: userId, body: JSON.stringify({chatPaused: paused}) })
      setStream(current => current ? { ...current, chat_paused: value.moderation.chat_paused === true } : current)
    } catch (caught) { setError(friendlyError(caught, 'No se pudo cambiar el chat.')) }
    finally { setSettingsBusy(false) }
  }
  async function leaveGuest() {
    guestCaptureSequence.current++
    guestCaptureSequence.current++
    guestMediaRef.current?.getTracks().forEach(track => track.stop())
    setGuestMedia(null)
    setGuestRequested(false)
    eligibleInvitations.current.clear()
    setInvitations([])
    await fallbackApiJson(`/v1/data/live/${encodeURIComponent(cleanLiveId)}/guests/leave`, { method: 'POST', expectedAccountId: userId, body: '{}' }).catch(() => setError('Saliste como invitado. La conexión debe volver para confirmar la salida.'))
  }

  function beginBroadcast() {
    if (blocked || closed || role !== 'host' || !isOwner || hostCaptureRef.current) return
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
      const revision = commentsRevision.current
      const response = await loadFallbackLiveComments(cleanLiveId, 120)
      if (commentScope.current !== cleanLiveId) return
      setComments(current => {
        const retained = revision === commentsRevision.current ? current.filter(item => pendingCommentIds.current.has(item.id)) : current
        return [...new Map([...response.items, ...retained].filter(item => !removedCommentIds.current.has(item.id)).map(item => [item.id, item])).values()].slice(-200)
      })
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
      onEvent: event => {
        if (commentScope.current !== cleanLiveId) return
        const payload = event.payload, comment = payload.comment as FallbackLiveComment | undefined
        if (payload.event === 'live_comment_created' && comment?.id && comment.live_id === cleanLiveId && typeof comment.content === 'string') {
          if (removedCommentIds.current.has(comment.id)) return
          commentsRevision.current++
          setComments(current => [...new Map([...current, comment].map(item => [item.id, item])).values()].slice(-200))
        } else if (payload.event === 'live_comment_deleted' && typeof payload.commentId === 'string') {
          commentsRevision.current++; removedCommentIds.current.add(payload.commentId)
          setComments(current => current.filter(item => item.id !== payload.commentId))
        } else void loadComments()
      },
    })
    const interval = window.setInterval(() => void loadComments(), 8_000)
    return () => {
      unsubscribe()
      window.clearInterval(interval)
    }
  }, [cleanLiveId, isFallbackSession, loadComments])

  useEffect(() => { commentsEndRef.current?.scrollIntoView?.({ block: 'nearest' }) }, [comments])
  useEffect(() => {
    if (!cleanLiveId) return
    const refresh = () => void loadFallbackLiveStream(cleanLiveId).then(value => setStream(value.stream)).catch(() => {})
    const unsubscribe = subscribeUbuntuRealtime({ channel: `live-moderation:${cleanLiveId}`, onEvent: refresh })
    const collaboration = subscribeUbuntuRealtime({ channel: `live-collaboration:${cleanLiveId}`, onEvent: refresh })
    return () => { unsubscribe(); collaboration() }
  }, [cleanLiveId])

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

  const startOffer = useCallback(async (peerId: string, peerUserId?: string) => {
    const manager = managerRef.current
    if (sfuAvailable || !manager || !peerId || manager.hasPeerConnection(peerId)) return
    try {
      manager.createPeerConnection(peerId, !peerUserId || mediaPeerIsInitiator(userId, peerUserId, socketRef.current?.id || '', peerId))
      await manager.createOffer(peerId)
      await drainIce(peerId)
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo conectar con un espectador'))
      manager.disconnectPeer(peerId)
    }
  }, [drainIce, userId, sfuAvailable])

  useEffect(() => {
    if (blocked || closed || !streamId || !userId || !isFallbackSession || !cleanLiveId || !capabilitiesReady) return
    if (role === 'host' && !isOwner) {
      setError('Esta cuenta no es propietaria del directo.')
      return
    }
    if (role === 'host' && !hostCaptureRef.current) return
    let token = ''
    let disposed = false
    const sourceOperation = sourceOperationRef
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
      const videoTrack = media.getVideoTracks()[0]
      videoTrack?.addEventListener('ended', () => {
        if (disposed || manager.getLocalStream()?.getVideoTracks()[0] !== videoTrack) return
        const stopped = { ...liveStateRef.current, videoEnabled: false }
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
        await Promise.all([sfuAvailable ? Promise.resolve() : configureTurn(), prepareHostMedia()])
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
          if (role === 'host') void updateFallbackLiveState(cleanLiveId, { isActive: true }).then(value => { if (!disposed) setStream(value.stream) }).catch(() => { if(!disposed) setError('No se pudo confirmar el inicio del directo. Intenta conectar de nuevo.') })
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
          participants.forEach((participant) => void startOffer(participant.socketId, participant.userId))
        })
        socket.on('viewer-joined', (participant) => {
          if (role === 'host') void startOffer(participant.socketId, participant.userId)
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
          setClosed(true); releaseInteractiveMediaSession(mediaKey); onExit?.()
          window.setTimeout(() => navigationRef.current('/app/live', { replace: true }), 1200)
        })
        socket.on('reaction', (payload) => {
          const reaction = { id: payload.reactionId || crypto.randomUUID(), emoji: liveReactionEmoji(payload) }
          setReactions((current) => current.some(item => item.id === reaction.id) ? current : [...current.slice(-23), reaction])
          window.setTimeout(() => setReactions((current) => current.filter((item) => item.id !== reaction.id)), 3000)
        })
        socket.on('offer', (payload) => {
          if (sfuAvailable || !payload.sender || !payload.offer) return
          void (async () => {
            try {
              if (!manager.hasPeerConnection(payload.sender)) manager.createPeerConnection(payload.sender,
                payload.userId ? mediaPeerIsInitiator(currentUser.id, payload.userId, socket.id || '', payload.sender) : false)
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
          if (sfuAvailable || !payload.sender || !payload.answer) return
          void manager.handleAnswer(payload.sender, payload.answer)
            .then(() => drainIce(payload.sender))
            .catch((caught) => setError(friendlyError(caught, 'No se pudo completar la conexión del espectador')))
        })
        socket.on('ice-candidate', (payload) => {
          if (!sfuAvailable && payload.sender && payload.candidate) void addIceCandidate(payload.sender, payload.candidate)
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
      sourceOperation.current++
      socketRef.current?.disconnect()
      socketRef.current = null
      manager.disconnectAll()
      manager.stopLocalStream()
      if (role === 'host') void captureForAttempt?.then(media => media.getTracks().forEach(track => track.stop())).catch(() => {})
      managerRef.current = null
      pendingIce.clear()
    }
  }, [addIceCandidate, avatarUrl, cleanLiveId, displayName, drainIce, hostCaptureAttempt, isFallbackSession, isOwner, role, startOffer, streamId, userId, capabilitiesReady, sfuAvailable, blocked, closed])

  const emitState = useCallback((next: LiveState) => {
    liveStateRef.current = next
    setLiveState(next)
    socketRef.current?.emit('update-live-state', { liveId: cleanLiveId, ...next })
  }, [cleanLiveId])

  async function switchVideoSource(nextSource: LiveSource) {
    if (role !== 'host' || !managerRef.current || sourceBusy) return
    const manager = managerRef.current, operation = ++sourceOperationRef.current
    setSourceBusy(true)
    try {
      const media = nextSource === 'screen'
        ? await navigator.mediaDevices.getDisplayMedia({ video: true, audio: false })
        : await navigator.mediaDevices.getUserMedia({ video: true, audio: false })
      const track = media.getVideoTracks()[0]
      if (!track) throw new Error('No se recibió una pista de video')
      if (operation !== sourceOperationRef.current || manager !== managerRef.current) { media.getTracks().forEach(value => value.stop()); return }
      await manager.replaceOutgoingTrack('video', track)
      const current = manager.getLocalStream()
      setLocalMedia(current ? new MediaStream(current.getTracks()) : media)
      const next = { ...liveStateRef.current, source: nextSource, isPaused: false, videoEnabled: true }
      emitState(next)
      await updateFallbackLiveState(cleanLiveId, { source: nextSource })
      if (nextSource === 'screen') {
        track.addEventListener('ended', () => { if (managerRef.current === manager && manager.getLocalStream()?.getVideoTracks()[0] === track) void switchVideoSource('camera') }, { once: true })
      }
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo cambiar la fuente de video'))
    } finally { if (operation === sourceOperationRef.current) setSourceBusy(false) }
  }

  function toggleMic() {
    const nextEnabled = !liveState.micEnabled
    managerRef.current?.getLocalStream()?.getAudioTracks().forEach((track) => { track.enabled = nextEnabled })
    emitState({ ...liveState, micEnabled: nextEnabled })
  }

  function toggleVideo() {
    const nextEnabled = !liveState.videoEnabled
    managerRef.current?.getLocalStream()?.getVideoTracks().forEach((track) => { track.enabled = nextEnabled })
    emitState({ ...liveStateRef.current, videoEnabled: nextEnabled })
  }

  function sendReaction(emoji: string) {
    if (!user) return
    if (!socketRef.current?.connected) return
    const id = crypto.randomUUID()
    setReactions(current => [...current.slice(-23), {id, emoji}])
    window.setTimeout(() => setReactions(current => current.filter(item => item.id !== id)), 3000)
    socketRef.current.emit('send-reaction', { liveId: cleanLiveId, emoji, userId: user.id, reactionId: id })
  }

  async function submitComment(event: FormEvent) {
    event.preventDefault()
    const content = commentDraft.trim()
    if (!content || sendingComment) return
    const id = crypto.randomUUID()
    pendingCommentIds.current.add(id); commentsRevision.current++
    setComments(current => [...current, {id, live_id:cleanLiveId, user_id:userId, user_name:displayName, avatar_url:avatarUrl, content, created_at:new Date().toISOString()}].slice(-200))
    setCommentDraft('')
    setSendingComment(true)
    try {
      const response = await sendFallbackLiveComment(cleanLiveId, content, id)
      if (commentScope.current !== cleanLiveId) return
      commentsRevision.current++
      setComments((current) => [...current.filter((item) => item.id !== id && item.id !== response.comment.id), response.comment].slice(-200))
    } catch (caught) {
      if (commentScope.current === cleanLiveId) { setComments(current => current.filter(item => item.id !== id)); setCommentDraft(content); setError(friendlyError(caught, 'No se pudo enviar el comentario')) }
    } finally {
      pendingCommentIds.current.delete(id)
      setSendingComment(false)
    }
  }

  async function leaveOrEnd() {
    if (endingRef.current) return
    endingRef.current = true
    guestCaptureSequence.current++
    setClosed(true)
    guestMediaRef.current?.getTracks().forEach(track => track.stop())
    managerRef.current?.stopLocalStream()
    try {
      if (role === 'host') {
        socketRef.current?.emit('end-live', { liveId: cleanLiveId })
        await updateFallbackLiveState(cleanLiveId, {
          isActive: false,
          viewerCount: 0,
          peakViewerCount: viewerCount,
          totalComments: comments.length,
        }).catch(() => null)
      } else await leaveGuest()
    } finally {
      releaseInteractiveMediaSession(mediaKey)
      onExit?.()
      if (!minimized) navigate('/app/live', { replace: true })
    }
  }
  closeRef.current = leaveOrEnd

  async function shareLive() {
    const url = window.location.href.replace(/([?&])host=1(&|$)/, '$1').replace(/[?&]$/, '')
    try {
      if (navigator.share) await navigator.share({ title: stream ? textOf(stream, ['title'], 'Directo') : 'Directo', url })
      else await navigator.clipboard.writeText(url)
    } catch {
      // Native share cancellation needs no error state.
    }
  }

  const media = role === 'host' ? localMedia : sfuAvailable ? sfu.hostStream : remoteMedia
  const videoEnabled = liveState.videoEnabled && (role === 'host' || !sfuAvailable || sfu.hostVideoEnabled !== false)
  const mediaLabel = sfuAvailable && sfu.connected ? 'En vivo' : connectionLabel
  const reactionButtons = useMemo(() => ['👏', '🔥', '😮'], [])

  if (!isFallbackSession) return <StatusView title="Directo no disponible" description="Inicia sesión con el servidor de producción." />
  if (conflict) return minimized ? null : <MediaSessionConflict session={conflict}/>
  if (closed) return null
  if (loading) return <StatusView title="Abriendo directo" description="Preparando la sesión segura..." />
  if (!stream) return <StatusView title="Directo no encontrado" description={error || 'La emisión terminó o ya no está disponible.'} />

  return (
    <section className={`live-session-page${minimized ? ' live-session-minimized' : ''}`}>
      {minimized ? <aside className="live-floating-controls" aria-label="Directo activo"><strong>{role === 'host' ? 'Transmitiendo' : guestMedia ? 'Participando en directo' : 'Viendo directo'}</strong><span>{hostName}</span><button className="icon-btn" aria-label="Volver al directo" onClick={() => navigate(`/app/live/${encodeURIComponent(cleanLiveId)}${initialSearch || ''}`)}><Maximize/></button>{role === 'host' || guestMedia ? <button className="icon-btn" aria-label="Alternar micrófono del directo" onClick={() => role === 'host' ? toggleMic() : void toggleGuestMedia('audio')}>{(role === 'host' ? liveState.micEnabled : guestMic) ? <Mic/> : <MicOff/>}</button> : <button className="icon-btn" aria-label="Alternar sonido del directo" onClick={() => setViewerMuted(value => !value)}>{viewerMuted ? <VolumeX/> : <Volume2/>}</button>}<button className="icon-btn danger" aria-label={role === 'host' ? 'Finalizar directo' : 'Salir del directo'} onClick={() => void leaveOrEnd()}><PhoneOff/></button></aside> : null}
      <div className="live-console" inert={minimized}>
      {sfu.error && (role === 'host' || !media) ? <div className="live-reconnect-notice" role="status">{sfu.error}<button className="ghost-btn" type="button" onClick={sfu.retry}>Volver a conectar</button></div> : null}
      {settingsOpen ? <div className="app-dialog-backdrop"><section className="surface-card live-settings-dialog" role="dialog" aria-modal="true" aria-label="Ajustes del directo">
        <header className="row-between"><h2>Ajustes del directo</h2><button type="button" className="icon-btn" onClick={() => setSettingsOpen(false)} aria-label="Cerrar ajustes del directo"><X/></button></header>
        {error ? <p role="alert">{error}</p> : null}
        {role === 'host' && isOwner ? <>
          <fieldset className="live-settings-group"><legend>Tu emisión</legend>
            <label className="checkbox-inline"><input type="checkbox" checked={liveState.micEnabled} disabled={!localMedia} onChange={toggleMic}/>Micrófono encendido</label>
            <label className="checkbox-inline"><input type="checkbox" checked={liveState.videoEnabled} disabled={!localMedia || sourceBusy} onChange={toggleVideo}/>Vídeo encendido</label>
            <small>Puedes seguir hablando con tu foto de perfil al apagar el vídeo.</small>
            {localMedia && typeof navigator.mediaDevices?.getDisplayMedia === 'function' ? <button className="ghost-btn" type="button" disabled={sourceBusy} onClick={() => void switchVideoSource(liveState.source === 'screen' ? 'camera' : 'screen')}><MonitorUp size={18}/>{sourceBusy ? 'Cambiando fuente…' : liveState.source === 'screen' ? 'Volver a la cámara' : 'Compartir pantalla o ventana'}</button> : null}
          </fieldset>
          <fieldset className="live-settings-group"><legend>Participación</legend>
            <label className="checkbox-inline"><input type="checkbox" checked={stream.allow_guest_requests === true} disabled={!sfuAvailable || settingsBusy} onChange={event => void setGuestRequests(event.target.checked)}/>Permitir solicitudes para participar</label>
            {!sfuAvailable ? <small>La participación con cámara requiere que el servidor multimedia esté disponible.</small> : <small>Revisa cada solicitud antes de incorporar a una persona.</small>}
            <label className="checkbox-inline"><input type="checkbox" checked={stream.chat_paused !== true} disabled={settingsBusy} onChange={event => void setChatPaused(!event.target.checked)}/>Permitir comentarios</label>
          </fieldset>
          <h3>Solicitudes {pendingRequests.length ? `(${pendingRequests.length})` : ''}</h3>
          {pendingRequests.map(item => { const id=textOf(item,['invitee_user_id']),profile=invitationProfiles.get(id)||{};return <article className="live-guest-request" key={textOf(item,['id'])}><SafeAvatar name={textOf(profile,['display_name','username'],'Participante')} url={textOf(profile,['avatar_url'])}/><strong>{textOf(profile,['display_name','username'],'Participante')}</strong><button className="primary-btn" type="button" disabled={guestBusy} onClick={() => void respondGuest(textOf(item,['id']),'accepted')}>Aceptar</button><button className="ghost-btn" type="button" disabled={guestBusy} onClick={() => void respondGuest(textOf(item,['id']),'rejected')}>Rechazar</button></article>})}
          {!pendingRequests.length ? <p>No hay solicitudes pendientes.</p> : null}
          <button className="live-end-setting" type="button" onClick={() => void leaveOrEnd()}>Finalizar directo</button>
        </> : <><p>Puedes comentar, reaccionar y solicitar participar cuando el anfitrión lo permita.</p><label className="checkbox-inline"><input type="checkbox" checked={!viewerMuted} onChange={() => setViewerMuted(value => !value)}/>Escuchar el directo</label></>}
      </section></div> : null}
      {isOwner && pendingRequests.length > 0 && !settingsOpen ? <div className="live-request-notice" role="status"><UserPlus size={18}/><span>{pendingRequests.length === 1 ? 'Una persona solicita participar' : `${pendingRequests.length} personas solicitan participar`}</span><button type="button" onClick={() => setSettingsOpen(true)}>Revisar</button></div> : null}
      {role === 'viewer' && guestRequested && !acceptedInvitation && !guestMedia ? <p className="live-request-status" role="status">{invitations.some(item => item.status === 'pending') ? 'Solicitud enviada. Esperando al anfitrión…' : 'Tu solicitud ya fue revisada.'}</p> : null}
      {role === 'viewer' && acceptedInvitation && !guestMedia ? <div className="live-guest-confirmation"><strong>Tu solicitud fue aceptada</strong><label><input type="checkbox" checked={guestCamera} onChange={event => setGuestCamera(event.target.checked)}/>Cámara</label><label><input type="checkbox" checked={guestMic} onChange={event => setGuestMic(event.target.checked)}/>Micrófono</label><button className="primary-btn" type="button" disabled={guestBusy} onClick={() => void joinAsGuest()}>Activar y unirme</button></div> : null}
      <div className="live-video-stage" data-source={liveState.source} data-video-disabled={!videoEnabled || liveState.isPaused}>
        <StreamVideo stream={media} muted={role === 'host' || viewerMuted} className="live-main-video" />
        {!media || !videoEnabled || liveState.isPaused ? (
          <div className="live-video-placeholder">
            <SafeAvatar url={textOf(stream, ['host_avatar_url'], '')} name={hostName} />
            <strong>{liveState.isPaused ? 'Emisión pausada' : role === 'host' && !hostCaptureRef.current ? 'Listo para transmitir' : !videoEnabled ? 'En vivo · Solo audio' : mediaLabel}</strong>
            {role === 'host' && isOwner && !hostCaptureRef.current ? <button type="button" className="live-start-button" onClick={beginBroadcast}>{liveState.source === 'screen' ? 'Compartir pantalla y comenzar' : 'Activar cámara y comenzar'}</button> : null}
          </div>
        ) : null}

        <div className="live-session-topbar">
          <button className="live-icon-button" type="button" onClick={() => navigate('/app/live')} title="Minimizar directo" aria-label="Minimizar directo"><ArrowLeft aria-hidden="true" /></button>
          <Link className="live-host-link" to={textOf(stream, ['host_user_id'], '') ? `/app/profile/${encodeURIComponent(textOf(stream, ['host_user_id'], ''))}` : '/app/live'}>
            <SafeAvatar url={textOf(stream, ['host_avatar_url'], '')} name={hostName} />
            <span><strong>{hostName}</strong><small><Radio aria-hidden="true" /> {mediaLabel}</small></span>
          </Link>
          <span className="live-session-viewers"><Users aria-hidden="true" /> {viewerCount}</span>
          <button className="live-icon-button" type="button" onClick={() => void shareLive()} title="Compartir" aria-label="Compartir directo"><LinkIcon aria-hidden="true" /></button>
        </div>

        <ReactionCloud items={reactions}/>

        {sfu.guests.length || guestMedia ? <div className="live-guests-strip">{guestMedia ? <figure><StreamVideo stream={guestMedia} muted className="live-guest-video"/>{!guestCamera ? <div className="live-guest-avatar"><SafeAvatar name={displayName} url={avatarUrl}/></div> : null}<figcaption>Tú</figcaption></figure> : null}{sfu.guests.map(guest => <figure key={guest.id}><StreamVideo stream={guest.stream} muted={role === 'host' ? false : viewerMuted} className="live-guest-video"/>{!guest.videoEnabled ? <div className="live-guest-avatar"><SafeAvatar name={guest.name} url={textOf(invitationProfiles.get(guest.id) || {}, ['avatar_url'])}/></div> : null}<figcaption>{guest.name}</figcaption></figure>)}</div> : null}
        <aside className="live-comments-panel">
          <header className="live-comments-heading"><MessageCircle size={18}/><strong>Chat del directo</strong></header>
          {stream.chat_paused === true ? <small className="live-chat-status" role="status">El anfitrión pausó los comentarios.</small> : null}
          <div className="live-comments-list" role="log" aria-label="Comentarios del directo">
            {!comments.length ? <p className="live-chat-empty">Comparte un comentario con quienes están viendo el directo.</p> : null}
            {comments.slice(-60).map((comment) => (
              <div className="live-comment" key={comment.id}>
                <SafeAvatar url={textOf(comment, ['avatar_url'], '')} name={textOf(comment, ['user_name'], 'Usuario')} />
                <p><strong>{textOf(comment, ['user_name'], 'Usuario')}</strong> {textOf(comment, ['content'], '')}</p>
              </div>
            ))}
            <div ref={commentsEndRef}/>
          </div>
          <form className="live-comment-composer" onSubmit={submitComment}>
            <MessageCircle aria-hidden="true" />
            <input aria-label="Comentario para el directo" spellCheck lang="es" disabled={stream.chat_paused === true && !isOwner} maxLength={300} value={commentDraft} onChange={(event) => setCommentDraft(event.target.value)} placeholder="Comentar..." />
            <button type="submit" disabled={!commentDraft.trim() || sendingComment || (stream.chat_paused === true && !isOwner)} title="Enviar" aria-label="Enviar comentario"><Send aria-hidden="true" /></button>
          </form>
        </aside>

        <div className="live-session-controls">
          <button className="live-control" type="button" onClick={() => setSettingsOpen(value => !value)} aria-label="Ajustes del directo"><Settings/>{isOwner && pendingRequests.length ? <span className="live-request-count">{pendingRequests.length}</span> : null}</button>
          <button className="live-control" type="button" aria-label="Pantalla completa" onClick={() => { const node = document.querySelector<HTMLElement>('.live-session-page'); void (document.fullscreenElement ? document.exitFullscreen() : node?.requestFullscreen())?.catch(() => setError('No se pudo ampliar el directo.')) }}><Maximize/></button>
          {role === 'viewer' && sfuAvailable && stream.allow_guest_requests && !guestMedia && !acceptedInvitation ? <button className="live-control" type="button" aria-label="Solicitar participar" disabled={guestBusy || invitations.some(item => item.status === 'pending')} onClick={() => void requestGuest()}><UserPlus/></button> : null}
          {guestMedia ? <><button type="button" className="live-control" aria-label="Activar o silenciar tu micrófono" disabled={guestBusy} onClick={() => void toggleGuestMedia('audio')}>{guestMic?<Mic/>:<MicOff/>}</button><button type="button" className="live-control" aria-label="Activar o apagar tu cámara" disabled={guestBusy} onClick={() => void toggleGuestMedia('video')}>{guestCamera?<Camera/>:<CameraOff/>}</button><button type="button" className="live-control danger" aria-label="Dejar de participar" onClick={leaveGuest}><PhoneOff/></button></> : null}
          {role === 'viewer' ? <><button className="live-control" type="button" onClick={() => setViewerMuted(current => !current)} aria-label={viewerMuted ? 'Activar sonido' : 'Silenciar directo'} title={viewerMuted ? 'Activar sonido' : 'Silenciar directo'}>{viewerMuted ? <VolumeX /> : <Volume2 />}</button><button className="live-control danger" aria-label="Salir del directo" onClick={() => void leaveOrEnd()}><PhoneOff/></button></> : null}
          {role === 'host' ? (
            <>
              <button className={liveState.micEnabled ? 'live-control' : 'live-control off'} type="button" onClick={toggleMic} title="Micrófono" aria-label="Alternar micrófono">{liveState.micEnabled ? <Mic aria-hidden="true" /> : <MicOff aria-hidden="true" />}</button>
              <button className={liveState.videoEnabled ? 'live-control' : 'live-control off'} type="button" onClick={toggleVideo} title="Cámara" aria-label="Alternar video">{liveState.videoEnabled ? <Camera aria-hidden="true" /> : <CameraOff aria-hidden="true" />}</button>
              {typeof navigator.mediaDevices?.getDisplayMedia === 'function' ? <button className={liveState.source === 'screen' ? 'live-control active' : 'live-control'} type="button" disabled={sourceBusy || !localMedia} onClick={() => void switchVideoSource(liveState.source === 'screen' ? 'camera' : 'screen')} title="Compartir pantalla" aria-label="Alternar pantalla"><MonitorUp aria-hidden="true" /></button> : null}
              <button className="live-control danger" type="button" onClick={() => void leaveOrEnd()} title="Finalizar" aria-label="Finalizar directo"><PhoneOff aria-hidden="true" /></button>
            </>
          ) : null}
          <button className="live-like-button" type="button" onClick={() => sendReaction('❤️')} aria-label={`Me gusta, ${reactionCount}`}><Heart aria-hidden="true" /><span>{reactionCount.toLocaleString('es')}</span></button>
          {reactionButtons.map((reaction, index) => <button className="live-reaction-button" type="button" key={reaction} aria-label={['Aplausos', 'Fuego', 'Sorpresa'][index]} onClick={() => sendReaction(reaction)}>{reaction}</button>)}
        </div>

        {error ? <div className="live-session-error">{error}</div> : null}
      </div>
      </div>
    </section>
  )
}
