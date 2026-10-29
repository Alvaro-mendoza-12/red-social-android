import { MovableVoicePanel } from '../../components/MovableVoicePanel'
import { ReactionCloud } from '../../components/ReactionCloud'
import { useReactionBursts } from '../../components/useReactionBursts'
import { RoomVideo } from './RoomVideo'
import { RoomChatPhoto } from './RoomChatPhoto'
import { roomSocketReady } from './voiceMembership'
import { voiceSocketAuth, voiceReconnectOptions } from './voiceSocketRecovery'
import { RoomReactionMenu } from '../../components/RoomReactionMenu'
import { MessageText } from '../../components/MessageText'
import { boundedRoomChat, firstRoomClipboardImage, prepareRoomImage, validRoomImage, roomChatTtlMs } from './roomChatImage'
import { ImagePlus, X, MoreHorizontal, Trash2 } from 'lucide-react'
import { claimInteractiveMediaSession, releaseInteractiveMediaSession, useInteractiveMediaSession } from '../../core/interactiveMediaSession'
import { MediaSessionConflict } from '../../components/MediaSessionConflict'
import { mediaPeerIsInitiator } from '../../core/mediaPeerRole'
import {useVoiceRoomCues} from './useVoiceRoomCues'
import { Camera, CameraOff, MonitorUp, Settings, MessageCircle, Send, Maximize2, Minimize2 } from 'lucide-react'
import './voice.css'
import { SafeAvatar } from '../../components/SafeAvatar'
import { LiveStreamAudio } from '../../components/LiveStreamMedia'
import { AudioOutputSelect } from '../../components/AudioOutputSelect'
import { MediaDeviceDialog } from '../../components/MediaDeviceDialog'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { io, type Socket } from 'socket.io-client'
import {
  ArrowLeft,
  Headphones,
  Mic,
  MicOff,
  PhoneOff,
  Radio,
  RefreshCw,
  ShieldCheck,
  Users,
  Volume2,
  VolumeX,
} from 'lucide-react'
import { StatusView } from '../../components/StatusView'
import {
  removeRealtimeChannel,
  reportSupabaseServiceRestrictionIfPresent,
  subscribeRealtimeChannel,
  supabase,
} from '../../core/supabase'
import { formatDateTime, friendlyError, isMissingTableError, textOf, type GenericRow } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
import { joinFallbackVoiceRoom, leaveFallbackVoiceRoom, loadFallbackVoiceRoom } from '../../core/fallbackData'
import { ensureFallbackAccessToken } from '../../core/auth/fallbackAuth'
import { getRuntimeBackendUrl } from '../../core/runtimeBackend'
import { publicBackendTransportUrl } from '../../core/publicBackendTransport'
import { buildSignalingAffinity } from '../../core/signalingAffinity'
import { shouldUseUbuntuBackend } from '../../core/ubuntuBackend'
import WebRTCManager from './WebRTCManager'
import toast from 'react-hot-toast'
import {
  voiceBoolean,
  updateRemoteVoiceState,
  normalizeSignalingParticipant,
  normalizeSignalingParticipants,
  type NormalizedSignalingParticipant,
} from './voiceSignalingProtocol'

type VoiceRoom = {
  id: string
  title: string
  topic: string
  creatorId: string
  creatorName: string
  creatorAvatarUrl: string
  participantCount: number
  maxParticipants: number
  updatedAt: string
}

type Participant = {
  id: string
  userId: string
  socketId: string
  userName: string
  userAvatarUrl: string
  isMuted: boolean | null
  isSpeaking: boolean
  joinedAt: string
}

type SignalingParticipant = NormalizedSignalingParticipant

type SignalPayload = {
  sender: string
  userId?: string
  name?: string
}

type OfferPayload = SignalPayload & {
  offer: RTCSessionDescriptionInit
}

type AnswerPayload = SignalPayload & {
  answer: RTCSessionDescriptionInit
}

type IcePayload = SignalPayload & {
  candidate: RTCIceCandidateInit
}

type SpeakingPayload = {
  roomId?: string
  userId: string
  isSpeaking: boolean
}

type MutePayload = {
  roomId?: string
  userId: string
  isMuted: boolean
}

type RoomChatMessage = { roomId: string; userId: string; userName: string; text: string; id: string; timestamp: number; imageData?: string; expiresAt?: number; delivery?: 'sending' | 'sent' | 'failed' }
type RoomChatAck = { ok: boolean; error?: string; timestamp?: number; expiresAt?: number; id?: string }

type RoomReaction = { roomId?: string; userId: string; emoji: string; reactionId: string; timestamp: number }

type ServerEvents = {
  reaction: (payload: RoomReaction) => void
  'chat-message': (payload: Partial<RoomChatMessage>) => void
  'chat-message-deleted': (payload: { roomId: string; userId: string; id: string }) => void
  'media-status': (payload: { roomId?: string; userId: string; isCameraSharing: boolean; isScreenSharing: boolean }) => void
  connect: () => void
  disconnect: (reason: string) => void
  connect_error: (error: Error) => void
  'room-joined': (payload: { roomId?: string; socketId?: string; participantCount?: number }) => void
  'room-join-error': (payload: { roomId?: string; error?: string }) => void
  'current-participants': (participants: unknown) => void
  'user-joined': (participant: unknown) => void
  'user-left': (socketId: string) => void
  offer: (payload: OfferPayload) => void
  answer: (payload: AnswerPayload) => void
  'ice-candidate': (payload: IcePayload) => void
  'speaking-status': (payload: SpeakingPayload) => void
  'mute-state': (payload: MutePayload) => void
  'auth-error': (payload: { error?: string }) => void
}

type ClientEvents = {
  reaction: (payload: RoomReaction) => void
  'chat-message': (payload: RoomChatMessage, acknowledge: (result: RoomChatAck) => void) => void
  'chat-message-delete': (payload: { roomId: string; userId: string; id: string }, acknowledge: (result: RoomChatAck) => void) => void
  'media-status': (payload: { roomId: string; userId: string; isCameraSharing: boolean; isScreenSharing: boolean }) => void
  'join-room': (payload: { roomId: string; userId: string; name: string; avatarUrl: string; isMuted?: boolean }) => void
  offer: (payload: { target: string; offer: RTCSessionDescriptionInit; userId: string; name: string }) => void
  answer: (payload: { target: string; answer: RTCSessionDescriptionInit; userId: string; name: string }) => void
  'ice-candidate': (payload: { target: string; candidate: RTCIceCandidateInit; userId: string; name: string }) => void
  'speaking-status': (payload: SpeakingPayload) => void
  'mute-state': (payload: MutePayload) => void
}

type VoiceSocket = Socket<ServerEvents, ClientEvents>

const joinedRoomStoragePrefix = 'conectate-web-voice-joined-room:'

function joinedRoomStorageKey(accountId?: string): string {
  return `${joinedRoomStoragePrefix}${encodeURIComponent(accountId?.trim().toLowerCase() || 'signed-out')}`
}

function readSignalingUrl(): string {
  const configured = String(import.meta.env.VITE_SIGNALING_URL ?? '').trim()
  const fallbackApi = getRuntimeBackendUrl()
  return configured ? publicBackendTransportUrl(configured) : fallbackApi
}

function writeJoinedRoomToStorage(accountId: string | undefined, roomId: string | null) {
  if (typeof window === 'undefined') return
  const key = joinedRoomStorageKey(accountId)
  if (roomId) {
    window.localStorage.setItem(key, roomId)
    return
  }
  window.localStorage.removeItem(key)
}

function profilePathFor(profileId: string, currentUserId?: string): string {
  const clean = profileId.trim()
  if (!clean) return '/app/profile'
  if (currentUserId && clean === currentUserId) return '/app/profile'
  return `/app/profile/${clean}`
}

function initialsOf(name: string): string {
  const clean = name.trim()
  if (!clean) return 'U'
  return clean
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? '')
    .join('') || 'U'
}

function displayNameFromUser(user: ReturnType<typeof useAuth>['user']): string {
  const metadata = user?.user_metadata ?? {}
  const displayName = String(metadata.display_name ?? metadata.full_name ?? metadata.name ?? '').trim()
  return displayName || user?.email?.split('@')[0] || 'Usuario'
}

function avatarFromUser(user: ReturnType<typeof useAuth>['user']): string {
  const metadata = user?.user_metadata ?? {}
  return String(metadata.avatar_url ?? metadata.picture ?? '').trim()
}

function isMissingFunctionError(error: unknown): boolean {
  const message = error instanceof Error ? error.message.toLowerCase() : String(error ?? '').toLowerCase()
  return (
    message.includes('could not find') ||
    message.includes('function') ||
    message.includes('does not exist') ||
    message.includes('42883')
  )
}

function isMissingColumnError(error: unknown): boolean {
  const message = error instanceof Error ? error.message.toLowerCase() : String(error ?? '').toLowerCase()
  return message.includes('column') || message.includes('42703')
}

function resolveParticipantCount(row: GenericRow): number {
  const values = [
    Number(row.current_participants ?? Number.NaN),
    Number(row.participant_count ?? Number.NaN),
    Number(row.active_participants ?? Number.NaN),
  ].filter((value) => Number.isFinite(value))
  return values.length > 0 ? Math.max(0, values[0] ?? 0) : 0
}

function mapRoom(row: GenericRow, fallbackId: string): VoiceRoom {
  return {
    id: textOf(row, ['id'], fallbackId),
    title: textOf(row, ['name', 'title', 'readable_id'], 'Sala de voz'),
    topic: textOf(row, ['topic', 'description'], 'Sin descripción'),
    creatorId: textOf(row, ['creator_id', 'owner_id'], ''),
    creatorName: textOf(row, ['creator_name', 'owner_name'], ''),
    creatorAvatarUrl: textOf(row, ['creator_avatar_url', 'avatar_url'], ''),
    participantCount: resolveParticipantCount(row),
    maxParticipants: Math.max(1, Number(row.max_participants ?? 10)),
    updatedAt: textOf(row, ['updated_at', 'created_at'], ''),
  }
}

function mapParticipant(row: GenericRow): Participant {
  const userId = textOf(row, ['user_id'], '')
  return {
    id: textOf(row, ['id'], userId || crypto.randomUUID()),
    userId,
    socketId: textOf(row, ['socket_id'], ''),
    userName: textOf(row, ['user_name', 'display_name', 'name'], ''),
    userAvatarUrl: textOf(row, ['user_avatar_url', 'avatar_url'], ''),
    isMuted: voiceBoolean(row.is_muted) ?? null,
    isSpeaking: voiceBoolean(row.is_muted) === true ? false : voiceBoolean(row.is_speaking) ?? false,
    joinedAt: textOf(row, ['joined_at', 'created_at'], ''),
  }
}

function asRecord(value: unknown): GenericRow | null {
  if (value && typeof value === 'object' && !Array.isArray(value)) return value as GenericRow
  return null
}

function isIceServer(value: unknown): value is RTCIceServer {
  const record = asRecord(value)
  if (!record) return false
  const urls = record.urls
  return typeof urls === 'string' || (Array.isArray(urls) && urls.every((url) => typeof url === 'string'))
}

function extractIceServers(payload: unknown): RTCIceServer[] {
  const envelope = asRecord(payload)
  const nestedV = asRecord(envelope?.v)
  const nestedD = asRecord(envelope?.d)
  const candidate =
    (Array.isArray(envelope?.iceServers) && envelope?.iceServers) ||
    (Array.isArray(nestedV?.iceServers) && nestedV?.iceServers) ||
    (Array.isArray(nestedD?.iceServers) && nestedD?.iceServers) ||
    []
  return candidate.filter(isIceServer)
}

export function VoiceRoomPage({ roomIdOverride, minimized = false, onExit }: { roomIdOverride?: string; minimized?: boolean; onExit?: () => void } = {}) {
  const [searchParams] = useSearchParams()
  const explicitJoin = searchParams.get('join') === '1'
  const { user, isFallbackSession } = useAuth()
  const useUbuntuVoice = shouldUseUbuntuBackend(isFallbackSession)
  const params = useParams<{ roomId: string }>()
  const roomId = roomIdOverride || params.roomId
  const { items: voiceReactionItems, add: addVoiceReaction } = useReactionBursts(roomId || 'voice')
  const activeMedia = useInteractiveMediaSession()
  const mediaKey = `voice:${user?.id}:${roomId}`
  const conflict = activeMedia && user && activeMedia.accountId === user.id && activeMedia.key !== mediaKey ? activeMedia : null
  const closeMediaRef = useRef<() => Promise<void>>(async () => {})
  useEffect(() => () => releaseInteractiveMediaSession(mediaKey), [mediaKey])
  const navigate = useNavigate()
  const [room, setRoom] = useState<VoiceRoom | null>(null)
  const [participants, setParticipants] = useState<Participant[]>([])
  const [remoteStreams, setRemoteStreams] = useState<Record<string, MediaStream>>({})
  const [remoteVisualSources, setRemoteVisualSources] = useState<Record<string, 'camera' | 'screen' | 'none'>>({})
  const [peerStates, setPeerStates] = useState<Record<string, RTCPeerConnectionState>>({})
  const [loading, setLoading] = useState(true)
  const [syncingParticipants, setSyncingParticipants] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [joinError, setJoinError] = useState<string | null>(null)
  const [isJoined, setIsJoined] = useState(false)
  const [isJoining, setIsJoining] = useState(false)
  const [isLeaving, setIsLeaving] = useState(false)
  const [isMuted, setIsMuted] = useState(false)
  const [isDeafened, setIsDeafened] = useState(false)
  const [isSpeaking, setIsSpeaking] = useState(false)
  const [micAvailable, setMicAvailable] = useState(true)
  const [connectionLabel, setConnectionLabel] = useState('Listo')
  const [visualSource, setVisualSource] = useState<'none' | 'camera' | 'screen'>('none')
  const [localVideo, setLocalVideo] = useState<MediaStream | null>(null)
  const [devices, setDevices] = useState<MediaDeviceInfo[]>([])
  const [showDeviceSettings, setShowDeviceSettings] = useState(false)
  const [cameraDevice, setCameraDevice] = useState('')
  const [microphoneDevice, setMicrophoneDevice] = useState('')
  const [outputDevice, setOutputDevice] = useState('')
  const [mediaBusy, setMediaBusy] = useState(false)
  const [cinemaMode, setCinemaMode] = useState(false)
  const [chatMessages, setChatMessages] = useState<RoomChatMessage[]>([])
  const [chatDraft, setChatDraft] = useState('')
  const [chatOpen, setChatOpen] = useState(true)
  const [chatPhoto, setChatPhoto] = useState(''), [photoBusy, setPhotoBusy] = useState(false), [photoError, setPhotoError] = useState('')
  const photoOperation = useRef(0)
  const photoInput = useRef<HTMLInputElement>(null), photoRoom = useRef('')
  photoRoom.current = `${user?.id}:${roomId}`
  useEffect(() => { photoOperation.current++; setChatPhoto(''); setPhotoError(''); setPhotoBusy(false) }, [roomId, user?.id])
  const [chatUnread, setChatUnread] = useState(0)
  const receivedChatIds = useRef(new Set<string>())
  const deletedChatIds = useRef(new Set<string>())
  const confirmedDeletedChatIds = useRef(new Set<string>())
  const unreadChatIds = useRef(new Set<string>())
  const chatVisibleRef = useRef(true)
  const chatEndRef = useRef<HTMLDivElement | null>(null)
  const participantsRef = useRef(participants)
  useEffect(() => { chatVisibleRef.current = chatOpen && !minimized; participantsRef.current = participants }, [chatOpen, minimized, participants])
  const visualTrackRef = useRef<MediaStreamTrack | null>(null)
  const visualOperationRef = useRef(0)
  const audioOperationRef = useRef(0)

  const socketRef = useRef<VoiceSocket | null>(null)
  const acceptedSocketRef = useRef('')
  const [signalingReady, setSignalingReady] = useState(false)
  const lastPhotoSentRef = useRef(0)
  const webrtcManagerRef = useRef<WebRTCManager | null>(null)
  const audioContextRef = useRef<AudioContext | null>(null)
  const analyserRef = useRef<AnalyserNode | null>(null)
  const speakingFrameRef = useRef<number | null>(null)
  const mutedRef = useRef(false)
  const joinedRef = useRef(false)
  const autoJoinAttemptedRef = useRef(false)
  const socketToUserIdRef = useRef(new Map<string, string>())
  const liveParticipantStateRef = useRef(new Map<string, { socketId: string; isMuted: boolean | null; isSpeaking: boolean }>())
  const visualSourceRef = useRef<'none' | 'camera' | 'screen'>('none')
  const pendingIceRef = useRef(new Map<string, RTCIceCandidateInit[]>())

  const {session:roomCues,settings:cueSettings,setSettings:setCueSettings}=useVoiceRoomCues(user?.id||'',roomId||'',isDeafened,outputDevice)
  const [cueAudioBlocked,setCueAudioBlocked]=useState(false)
  const displayName = useMemo(() => displayNameFromUser(user), [user])
  const avatarUrl = useMemo(() => avatarFromUser(user), [user])
  const localParticipantInList = participants.some((participant) => participant.userId === user?.id)
  const visibleParticipantCount = Math.max(
    room?.participantCount ?? 0,
    participants.length + (isJoined && !localParticipantInList ? 1 : 0),
  )
  const remoteParticipants = participants.filter((participant) => participant.userId !== user?.id)
  const connectedPeerCount = Object.values(peerStates).filter((state) => state === 'connected').length
  const connectionQuality = connectedPeerCount > 0 ? 'Audio conectado' : isJoined ? (remoteParticipants.length ? 'Conectando audio…' : 'En la sala') : 'Sin conectar'

  useEffect(() => { receivedChatIds.current.clear(); deletedChatIds.current.clear(); confirmedDeletedChatIds.current.clear(); unreadChatIds.current.clear(); setChatMessages([]); setChatDraft(''); setChatUnread(0) }, [roomId, user?.id])
  useEffect(() => {
    const timer = setInterval(() => setChatMessages(current => { const next = boundedRoomChat(current); return next.length === current.length ? current : next }), 1000)
    return () => clearInterval(timer)
  }, [])
  useEffect(() => {
    if (chatOpen && !minimized) {
      unreadChatIds.current.clear()
      chatEndRef.current?.scrollIntoView?.({ block: 'nearest' })
    } else {
      const existing = new Set(chatMessages.map(message => `${message.userId}:${message.id}`))
      for (const id of unreadChatIds.current) if (!existing.has(id)) unreadChatIds.current.delete(id)
    }
    setChatUnread(unreadChatIds.current.size)
  }, [chatOpen, minimized, chatMessages])

  useEffect(() => {
    mutedRef.current = isMuted
  }, [isMuted])

  const stopSpeakingDetection = useCallback(() => {
    if (speakingFrameRef.current !== null) {
      window.cancelAnimationFrame(speakingFrameRef.current)
      speakingFrameRef.current = null
    }
    analyserRef.current = null
    if (audioContextRef.current && audioContextRef.current.state !== 'closed') {
      void audioContextRef.current.close().catch(() => {})
    }
    audioContextRef.current = null
    setIsSpeaking(false)
  }, [])

  const stopSignaling = useCallback(() => {
    roomCues.suspend()
    acceptedSocketRef.current = ''
    setSignalingReady(false)
    const socket = socketRef.current
    if (socket) {
      socket.removeAllListeners()
      socket.disconnect()
      socketRef.current = null
    }
    socketToUserIdRef.current.clear()
    liveParticipantStateRef.current.clear()
    pendingIceRef.current.clear()
    setPeerStates({})
    setRemoteStreams({})
  }, [roomCues])

  const stopLocalMedia = useCallback(() => {
    visualOperationRef.current++
    audioOperationRef.current++
    visualTrackRef.current?.stop(); visualTrackRef.current = null; visualSourceRef.current = 'none'; setLocalVideo(null); setVisualSource('none')
    stopSpeakingDetection()
    stopSignaling()
    if (webrtcManagerRef.current) {
      webrtcManagerRef.current.disconnectAll()
      webrtcManagerRef.current.stopLocalStream()
      webrtcManagerRef.current = null
    }
    setConnectionLabel('Listo')
  }, [stopSignaling, stopSpeakingDetection])

  const emitMuteState = useCallback((nextMuted: boolean) => {
    if (!roomSocketReady(socketRef.current, acceptedSocketRef.current) || !room || !user) return
    socketRef.current?.emit('mute-state', {
      roomId: room.id,
      userId: user.id,
      isMuted: nextMuted,
    })
  }, [room, user])

  const emitSpeakingState = useCallback((nextSpeaking: boolean) => {
    if (!roomSocketReady(socketRef.current, acceptedSocketRef.current) || !room || !user) return
    socketRef.current?.emit('speaking-status', {
      roomId: room.id,
      userId: user.id,
      isSpeaking: nextSpeaking,
    })
  }, [room, user])

  const beginSpeakingDetection = useCallback((stream: MediaStream) => {
    stopSpeakingDetection()
    const AudioContextCtor = window.AudioContext
    if (!AudioContextCtor) return
    const audioContext = new AudioContextCtor()
    const analyser = audioContext.createAnalyser()
    analyser.fftSize = 256
    audioContext.createMediaStreamSource(stream).connect(analyser)
    audioContextRef.current = audioContext
    analyserRef.current = analyser

    const samples = new Uint8Array(analyser.frequencyBinCount)
    let lastSpeaking = false
    const tick = () => {
      if (!analyserRef.current) return
      analyserRef.current.getByteFrequencyData(samples)
      const average = samples.reduce((sum, value) => sum + value, 0) / samples.length
      const nextSpeaking = average > 28 && !mutedRef.current
      if (nextSpeaking !== lastSpeaking) {
        lastSpeaking = nextSpeaking
        setIsSpeaking(nextSpeaking)
        emitSpeakingState(nextSpeaking)
      }
      speakingFrameRef.current = window.requestAnimationFrame(tick)
    }
    speakingFrameRef.current = window.requestAnimationFrame(tick)
  }, [emitSpeakingState, stopSpeakingDetection])

  const loadRoom = useCallback(async () => {
    if (!roomId) {
      setLoading(false)
      setError('ID de sala no válido')
      return
    }

    setLoading(true)
    setError(null)
    try {
      if (useUbuntuVoice) {
        const payload = await loadFallbackVoiceRoom(roomId)
        setRoom(mapRoom(payload.room, roomId))
        setParticipants((payload.participants ?? []).map(mapParticipant))
        return
      }

      if (!supabase) {
        setError('Configura VITE_FALLBACK_API_URL para usar salas de voz en Ubuntu.')
        return
      }

      const { data, error: queryError } = await supabase
        .from('voice_rooms')
        .select('*')
        .eq('id', roomId)
        .limit(1)

      if (queryError) throw queryError
      const row = ((data ?? []) as GenericRow[])[0]
      if (!row) {
        setError('Sala no encontrada')
        return
      }

      let mappedRoom = mapRoom(row, roomId)
      if (mappedRoom.creatorId) {
        const { data: profileRows } = await supabase
          .from('profiles')
          .select('id,display_name,username,avatar_url,photo_url,profile_image')
          .eq('id', mappedRoom.creatorId)
          .limit(1)
        const profile = ((profileRows ?? []) as GenericRow[])[0]
        if (profile) {
          mappedRoom = {
            ...mappedRoom,
            creatorName: mappedRoom.creatorName || textOf(profile, ['display_name', 'username'], mappedRoom.creatorId),
            creatorAvatarUrl: mappedRoom.creatorAvatarUrl || textOf(profile, ['avatar_url', 'photo_url', 'profile_image'], ''),
          }
        }
      }

      setRoom(mappedRoom)
    } catch (caught) {
      reportSupabaseServiceRestrictionIfPresent('voice-room-load', caught)
      setError(friendlyError(caught, 'No se pudo cargar la sala'))
    } finally {
      setLoading(false)
    }
  }, [roomId, useUbuntuVoice])

  const loadParticipants = useCallback(async (targetRoomId: string, options?: { silent?: boolean }) => {
    if (useUbuntuVoice) {
      if (options?.silent) {
        setSyncingParticipants(true)
      }

      try {
        const payload = await loadFallbackVoiceRoom(targetRoomId)
        const mapped = (payload.participants ?? []).map(mapParticipant)
        setParticipants((current) => {
          const signalOnly = current.filter((participant) => participant.socketId && !mapped.some((db) => db.userId === participant.userId))
          return [...mapped.map(db => {
            const live = liveParticipantStateRef.current.get(db.userId)
            return live ? { ...db, socketId: live.socketId, isMuted: live.isMuted, isSpeaking: live.isSpeaking } : db
          }), ...signalOnly]
        })
        setRoom((current) =>
          current && current.id === targetRoomId
            ? { ...current, participantCount: mapped.length }
            : current,
        )

        // Persisted presence may belong to another device; only this tab's
        // admission/media lifecycle can mark its own connection as joined.
      } catch (caught) {
        setJoinError(friendlyError(caught, 'No se pudieron cargar los participantes desde Ubuntu'))
      } finally {
        setSyncingParticipants(false)
      }
      return
    }

    if (!supabase) return
    if (options?.silent) {
      setSyncingParticipants(true)
    }

    try {
      const { data, error: queryError } = await supabase
        .from('voice_room_participants')
        .select('*')
        .eq('room_id', targetRoomId)
        .order('joined_at', { ascending: true })

      if (queryError) throw queryError
      let mapped = ((data ?? []) as GenericRow[]).map(mapParticipant)
      const missingProfileIds = Array.from(
        new Set(
          mapped
            .filter((participant) => participant.userId && (!participant.userName || !participant.userAvatarUrl))
            .map((participant) => participant.userId),
        ),
      )

      if (missingProfileIds.length > 0) {
        const { data: profileRows } = await supabase
          .from('profiles')
          .select('id,display_name,username,avatar_url,photo_url,profile_image')
          .in('id', missingProfileIds)
        const profileById = new Map<string, { name: string; avatarUrl: string }>()
        ;((profileRows ?? []) as GenericRow[]).forEach((profile) => {
          const id = textOf(profile, ['id'], '')
          if (!id) return
          profileById.set(id, {
            name: textOf(profile, ['display_name', 'username'], '') || id,
            avatarUrl: textOf(profile, ['avatar_url', 'photo_url', 'profile_image'], ''),
          })
        })
        mapped = mapped.map((participant) => {
          const profile = profileById.get(participant.userId)
          if (!profile) return participant
          return {
            ...participant,
            userName: participant.userName || profile.name,
            userAvatarUrl: participant.userAvatarUrl || profile.avatarUrl,
          }
        })
      }

      setParticipants((current) => {
        const signalOnly = current.filter((participant) => participant.socketId && !mapped.some((db) => db.userId === participant.userId))
        return [...mapped.map(db => {
          const live = liveParticipantStateRef.current.get(db.userId)
          return live ? { ...db, socketId: live.socketId, isMuted: live.isMuted, isSpeaking: live.isSpeaking } : db
        }), ...signalOnly]
      })
      setRoom((current) =>
        current && current.id === targetRoomId
          ? { ...current, participantCount: mapped.length }
          : current,
      )

      // Presence is shared between devices; admission belongs to this session.
    } catch (caught) {
      if (!isMissingTableError(caught)) {
        reportSupabaseServiceRestrictionIfPresent('voice-participants-load', caught)
        setJoinError(friendlyError(caught, 'No se pudieron cargar los participantes'))
      }
    } finally {
      setSyncingParticipants(false)
    }
  }, [useUbuntuVoice])

  const fallbackParticipantUpdate = useCallback(async (targetRoomId: string, delta: number) => {
    if (useUbuntuVoice) {
      setRoom((current) =>
        current && current.id === targetRoomId
          ? { ...current, participantCount: Math.max(0, current.participantCount + delta) }
          : current,
      )
      return
    }
    if (!supabase) return
    const { data, error: readError } = await supabase.from('voice_rooms').select('*').eq('id', targetRoomId).limit(1)
    if (readError) {
      if (isMissingColumnError(readError)) return
      throw readError
    }

    const row = ((data ?? []) as GenericRow[])[0] ?? {}
    const columns = ['current_participants', 'participant_count', 'active_participants']
    for (const column of columns) {
      const currentCount = Number(row[column] ?? 0)
      const nextCount = Math.max(0, (Number.isFinite(currentCount) ? currentCount : 0) + delta)
      const { error: updateError } = await supabase
        .from('voice_rooms')
        .update({ [column]: nextCount })
        .eq('id', targetRoomId)
      if (updateError) {
        if (isMissingColumnError(updateError)) continue
        throw updateError
      }
      setRoom((current) => current && current.id === targetRoomId ? { ...current, participantCount: nextCount } : current)
      return
    }
  }, [useUbuntuVoice])

  const syncParticipantCounter = useCallback(async (targetRoomId: string, delta: number) => {
    if (useUbuntuVoice) {
      setRoom((current) =>
        current && current.id === targetRoomId
          ? { ...current, participantCount: Math.max(0, current.participantCount + delta) }
          : current,
      )
      return
    }
    if (!supabase) return
    const rpcName = delta > 0 ? 'increment_participant_count' : 'decrement_participant_count'
    const rpcResult = await supabase.rpc(rpcName, { room_id: targetRoomId })
    if (!rpcResult.error) {
      setRoom((current) =>
        current && current.id === targetRoomId
          ? { ...current, participantCount: Math.max(0, current.participantCount + delta) }
          : current,
      )
      return
    }
    if (!isMissingFunctionError(rpcResult.error)) throw rpcResult.error
    await fallbackParticipantUpdate(targetRoomId, delta)
  }, [fallbackParticipantUpdate, useUbuntuVoice])

  const upsertSignalParticipant = useCallback((participant: SignalingParticipant) => {
    const socketId = String(participant.socketId ?? '').trim()
    const userId = String(participant.userId ?? '').trim()
    if (!socketId || !userId || userId === user?.id) return
    socketToUserIdRef.current.set(socketId, userId)
    const existing = liveParticipantStateRef.current.get(userId)
    const state = updateRemoteVoiceState(existing || { isMuted: null, isSpeaking: false }, { isMuted: participant.isMuted })
    const { isMuted } = state
    if (participant.isCameraSharing !== undefined || participant.isScreenSharing !== undefined) setRemoteVisualSources(current => ({ ...current, [userId]: participant.isScreenSharing ? 'screen' : participant.isCameraSharing ? 'camera' : 'none' }))
    liveParticipantStateRef.current.set(userId, { socketId, ...state })
    setParticipants((current) => {
      const nextParticipant: Participant = {
        id: `signal-${socketId}`,
        socketId,
        userId,
        userName: String(participant.name ?? '').trim() || userId,
        userAvatarUrl: String(participant.avatarUrl ?? '').trim(),
        isMuted,
        isSpeaking: state.isSpeaking,
        joinedAt: new Date().toISOString(),
      }
      const withoutDuplicate = current.filter((item) => item.userId !== userId && item.socketId !== socketId)
      const previous = current.find(item => item.userId === userId || item.socketId === socketId)
      return [...withoutDuplicate, { ...nextParticipant, userName: participant.name || previous?.userName || nextParticipant.userName, userAvatarUrl: participant.avatarUrl || previous?.userAvatarUrl || '', joinedAt: previous?.joinedAt || nextParticipant.joinedAt }]
    })
  }, [user?.id])

  const drainIceCandidates = useCallback(async (peerId: string) => {
    const manager = webrtcManagerRef.current
    const candidates = pendingIceRef.current.get(peerId) ?? []
    if (!manager || candidates.length === 0) return
    pendingIceRef.current.delete(peerId)
    for (const candidate of candidates) {
      try {
        await manager.handleIceCandidate(peerId, candidate)
      } catch {
        pendingIceRef.current.set(peerId, [candidate, ...(pendingIceRef.current.get(peerId) ?? [])])
        return
      }
    }
  }, [])

  const queueOrAddIceCandidate = useCallback(async (peerId: string, candidate: RTCIceCandidateInit) => {
    const manager = webrtcManagerRef.current
    if (!manager || !manager.hasPeerConnection(peerId)) {
      pendingIceRef.current.set(peerId, [...(pendingIceRef.current.get(peerId) ?? []), candidate])
      return
    }
    try {
      await manager.handleIceCandidate(peerId, candidate)
    } catch {
      pendingIceRef.current.set(peerId, [...(pendingIceRef.current.get(peerId) ?? []), candidate])
    }
  }, [])

  const startPeerOffer = useCallback(async (peerId: string) => {
    const manager = webrtcManagerRef.current
    if (!manager || !peerId || manager.hasPeerConnection(peerId)) return

    try {
      manager.createPeerConnection(peerId, true)
      await manager.createOffer(peerId)
      await drainIceCandidates(peerId)
    } catch (caught) {
      setJoinError(friendlyError(caught, 'No se pudo iniciar la conexión de voz'))
    }
  }, [drainIceCandidates])

  const shouldOfferToPeer = useCallback((peerSocketId: string, peerUserId?: string) => {
    const ownSocketId = socketRef.current?.id
    const ownUserId = user?.id
    if (!ownSocketId || !peerSocketId || !ownUserId) return false
    
    const resolvedPeerUserId = peerUserId || socketToUserIdRef.current.get(peerSocketId) || peerSocketId
    
    // WebRTC Perfect Negotiation Parity with Android
    // Android isPolite: (currentUserId < remoteUserId) || (currentUserId == remoteUserId && localSocketId < remoteSocketId)
    // The "impolite" peer (who sends the offer) is the one where isPolite == false.
    return mediaPeerIsInitiator(ownUserId, resolvedPeerUserId, ownSocketId, peerSocketId)
  }, [user?.id])

  const configureIceServers = useCallback(async (manager: WebRTCManager, accessToken: string) => {
    const signalingUrl = readSignalingUrl()
    try {
      const response = await fetch(`${signalingUrl.replace(/\/$/, '')}/turn-credentials`, {
        signal: AbortSignal.timeout(6000),
        headers: {
          Accept: 'application/json',
          Authorization: `Bearer ${accessToken}`,
        },
      })
      if (!response.ok) return
      const payload = await response.json() as unknown
      const iceServers = extractIceServers(payload)
      manager.setIceServers(iceServers)
    } catch {
      manager.setIceServers([
        { urls: 'stun:stun.l.google.com:19302' },
        { urls: 'stun:stun1.l.google.com:19302' },
      ])
    }
  }, [])

  const connectSignaling = useCallback(async (manager: WebRTCManager) => {
    if (!room || !user) return
    const accessToken = useUbuntuVoice
      ? await ensureFallbackAccessToken()
      : (await supabase?.auth.getSession())?.data.session?.access_token?.trim()
    if (!accessToken) {
      setConnectionLabel('Sin token de voz')
      return
    }

    await configureIceServers(manager, accessToken)
    stopSignaling()

    const signalingUrl = readSignalingUrl()
    const socket: VoiceSocket = io(signalingUrl, {
      transports: ['websocket', 'polling'],
      tryAllTransports: true,
      query: { affinity: buildSignalingAffinity('voice', room.id) },
      auth: voiceSocketAuth(async () => useUbuntuVoice
        ? await ensureFallbackAccessToken()
        : (await supabase?.auth.getSession())?.data.session?.access_token,
      () => joinedRef.current && socketRef.current === socket),
      autoConnect: false,
      ...voiceReconnectOptions,
      timeout: 12000,
    })
    let roomJoinPending = false
    const requestRoomMembership = () => {
      if (!socket.connected || roomJoinPending || !joinedRef.current || socketRef.current !== socket) return
      roomJoinPending = true
      acceptedSocketRef.current = ''
      setSignalingReady(false)
      socket.emit('join-room', { roomId: room.id, userId: user.id, name: displayName, avatarUrl, isMuted: mutedRef.current })
    }

    socketRef.current = socket
    socket.on('chat-message-deleted', payload => {
      if (payload.roomId !== room.id || !payload.id || !payload.userId) return
      const key = `${payload.userId}:${payload.id}`
      deletedChatIds.current.add(key)
      confirmedDeletedChatIds.current.add(key)
      while (deletedChatIds.current.size > 1000) deletedChatIds.current.delete(deletedChatIds.current.values().next().value!)
      while (confirmedDeletedChatIds.current.size > 1000) confirmedDeletedChatIds.current.delete(confirmedDeletedChatIds.current.values().next().value!)
      setChatMessages(current => current.filter(message => `${message.userId}:${message.id}` !== key))
    })
    socket.on('chat-message', payload => {
      if (payload.roomId !== room.id || !payload.userId || typeof payload.text !== 'string' || !payload.text.trim()) return
      const participant = participantsRef.current.find(item => item.userId === payload.userId)
      const message: RoomChatMessage = {
        roomId: room.id, userId: payload.userId,
        userName: participant?.userName || (typeof payload.userName === 'string' ? payload.userName.slice(0, 100) : 'Participante'),
        text: payload.text.slice(0, 2000), id: String(payload.id || crypto.randomUUID()).slice(0, 160),
        timestamp: typeof payload.timestamp === 'number' && Number.isFinite(payload.timestamp) ? payload.timestamp : Date.now(),
        expiresAt: typeof payload.expiresAt === 'number' && Number.isFinite(payload.expiresAt) ? Math.min(payload.expiresAt, Date.now() + roomChatTtlMs) : Date.now() + roomChatTtlMs,
        delivery: 'sent',
        ...(validRoomImage(payload.imageData) ? { imageData: payload.imageData } : {}),
      }
      const deliveryId = `${message.userId}:${message.id}`
      if (deletedChatIds.current.has(deliveryId)) return
      const known = receivedChatIds.current.has(deliveryId)
      receivedChatIds.current.add(deliveryId)
      if (receivedChatIds.current.size > 400) receivedChatIds.current.delete(receivedChatIds.current.values().next().value!)
      setChatMessages(current => boundedRoomChat([...current,message]))
      if (!known && !chatVisibleRef.current && payload.userId.toLowerCase() !== user.id.toLowerCase()) unreadChatIds.current.add(deliveryId)
    })
    manager.onIceCandidate((target, candidate) => {
      if (!roomSocketReady(socket, acceptedSocketRef.current)) return
      socket.emit('ice-candidate', {
        target,
        candidate,
        userId: user.id,
        name: displayName,
      })
    })
    manager.onOfferNeeded((target, offer) => {
      if (!roomSocketReady(socket, acceptedSocketRef.current)) return
      socket.emit('offer', {
        target,
        offer,
        userId: user.id,
        name: displayName,
      })
    })
    manager.onTrack((_track, stream, peerId) => {
      setRemoteStreams((current) => current[peerId] === stream ? current : ({ ...current, [peerId]: stream }))
    })
    manager.onConnectionState((state, peerId) => {
      setPeerStates((current) => ({ ...current, [peerId]: state }))
    })

    socket.on('connect', () => {
      acceptedSocketRef.current = ''
      setSignalingReady(false)
      setConnectionLabel('Señalización conectada')
      roomJoinPending = false
      requestRoomMembership()
    })

    socket.on('connect_error', (socketError) => {
      setConnectionLabel('Reconectando voz')
      setJoinError(/xhr|transport|websocket|timeout/i.test(socketError.message)
        ? 'Se interrumpió la conexión de voz. Reintentando automáticamente…'
        : socketError.message || 'No se pudo conectar con el servidor de voz')
    })

    socket.on('disconnect', () => {
      roomJoinPending = false
      acceptedSocketRef.current = ''
      setSignalingReady(false)
      roomCues.suspend()
      setConnectionLabel('Reconectando voz')
    })

    socket.on('auth-error', (payload) => {
      if (String(payload.error || '').includes('not joined to requested room')) {
        acceptedSocketRef.current = ''
        setSignalingReady(false)
        requestRoomMembership()
        setConnectionLabel('Sincronizando acceso a la sala')
        return
      }
      setJoinError(payload.error || 'La señalización de voz rechazó la sesión')
    })

    socket.on('room-joined', (payload) => {
      if (payload.roomId && payload.roomId !== room.id) return
      if (socketRef.current !== socket || !joinedRef.current) return
      roomJoinPending = false
      acceptedSocketRef.current = socket.id || ''
      setSignalingReady(true)
      setJoinError(null)
      void roomCues.prepare().then(ready=>setCueAudioBlocked(!ready))
      roomCues.connect(mutedRef.current)
      socket.emit('media-status', { roomId: room.id, userId: user.id, isCameraSharing: visualSourceRef.current === 'camera', isScreenSharing: visualSourceRef.current === 'screen' })
      setConnectionLabel('Sala sincronizada')
      if (Number.isFinite(Number(payload.participantCount))) {
        setRoom((current) => current ? {
          ...current,
          participantCount: Math.max(0, Number(payload.participantCount)),
        } : current)
      }
    })

    socket.on('room-join-error', (payload) => {
      if (payload.roomId && payload.roomId !== room.id) return
      roomJoinPending = false
      acceptedSocketRef.current = ''
      setSignalingReady(false)
      setConnectionLabel('Acceso rechazado')
      setJoinError(payload.error || 'El servidor rechazó el acceso a esta sala de voz')
      socket.disconnect()
    })

    socket.on('current-participants', (rawParticipants) => {
      const signalParticipants = normalizeSignalingParticipants(rawParticipants)
      const present = new Set(signalParticipants.map(participant => participant.socketId))
      for (const peerId of manager.getConnectionStats().keys()) if (!present.has(peerId)) manager.disconnectPeer(peerId)
      setRemoteStreams(current => Object.fromEntries(Object.entries(current).filter(([peerId]) => present.has(peerId))))
      setPeerStates(current => Object.fromEntries(Object.entries(current).filter(([peerId]) => present.has(peerId))))
      roomCues.baseline(signalParticipants.map(p=>({userId:p.userId,socketId:p.socketId,isMuted:p.isMuted??true})))
      signalParticipants.forEach(upsertSignalParticipant)
      signalParticipants.forEach((participant) => {
        if (shouldOfferToPeer(participant.socketId, participant.userId)) {
          void startPeerOffer(participant.socketId)
        }
      })
    })

    socket.on('user-joined', (rawParticipant) => {
      const participant = normalizeSignalingParticipant(rawParticipant)
      if (!participant) return
      roomCues.join({userId:participant.userId,socketId:participant.socketId,isMuted:participant.isMuted})
      upsertSignalParticipant(participant)
      if (shouldOfferToPeer(participant.socketId, participant.userId)) {
        void startPeerOffer(participant.socketId)
      }
    })

    socket.on('user-left', (socketId) => {
      roomCues.left(socketId)
      const userIdForSocket = socketToUserIdRef.current.get(socketId)
      if (userIdForSocket && liveParticipantStateRef.current.get(userIdForSocket)?.socketId === socketId) liveParticipantStateRef.current.delete(userIdForSocket)
      socketToUserIdRef.current.delete(socketId)
      manager.disconnectPeer(socketId)
      setRemoteStreams((current) => {
        const next = { ...current }
        delete next[socketId]
        return next
      })
      setPeerStates((current) => {
        const next = { ...current }
        delete next[socketId]
        return next
      })
      setParticipants((current) =>
        current.filter((participant) => participant.socketId !== socketId && participant.userId !== userIdForSocket),
      )
    })

    socket.on('offer', (payload) => {
      void (async () => {
        try {
          if (!payload.sender || !payload.offer) return
          upsertSignalParticipant({
            socketId: payload.sender,
            userId: payload.userId ?? payload.sender,
            name: payload.name,
          })
          if (!manager.hasPeerConnection(payload.sender)) {
            manager.createPeerConnection(payload.sender, shouldOfferToPeer(payload.sender, payload.userId))
          }
          const answer = await manager.createAnswer(payload.sender, payload.offer)
          if (!answer || !roomSocketReady(socket, acceptedSocketRef.current)) return
          socket.emit('answer', {
            target: payload.sender,
            answer,
            userId: user.id,
            name: displayName,
          })
          await drainIceCandidates(payload.sender)
        } catch (caught) {
          setJoinError(friendlyError(caught, 'No se pudo responder a la conexión de voz'))
        }
      })()
    })

    socket.on('answer', (payload) => {
      void (async () => {
        try {
          if (!payload.sender || !payload.answer) return
          await manager.handleAnswer(payload.sender, payload.answer)
          await drainIceCandidates(payload.sender)
        } catch (caught) {
          setJoinError(friendlyError(caught, 'No se pudo completar la conexión de voz'))
        }
      })()
    })

    socket.on('ice-candidate', (payload) => {
      if (!payload.sender || !payload.candidate) return
      void queueOrAddIceCandidate(payload.sender, payload.candidate)
    })

    const applyVoiceState = (payload: SpeakingPayload | MutePayload) => {
      if (payload.roomId && payload.roomId !== room.id) return
      const live = liveParticipantStateRef.current.get(payload.userId)
      if (live) liveParticipantStateRef.current.set(payload.userId, { ...live, ...updateRemoteVoiceState(live, payload) })
      setParticipants(current => current.map(participant => participant.userId === payload.userId
        ? { ...participant, ...updateRemoteVoiceState(participant, payload) } : participant))
    }
    socket.on('speaking-status', applyVoiceState)
    socket.on('mute-state', payload => {
      if (payload.roomId && payload.roomId !== room.id) return
      applyVoiceState(payload)
      const muted = voiceBoolean(payload.isMuted)
      if (muted !== undefined) roomCues.mute(payload.userId, muted)
    })
    socket.on('reaction', payload => { if ((!payload.roomId || payload.roomId === room.id) && payload.userId && payload.emoji) addVoiceReaction({ id: payload.reactionId || `${payload.userId}-${payload.timestamp}`, emoji: payload.emoji }) })
    socket.on('media-status', payload => {
      if (payload.roomId && payload.roomId !== room.id) return
      setRemoteVisualSources(current => ({ ...current, [payload.userId]: payload.isScreenSharing ? 'screen' : payload.isCameraSharing ? 'camera' : 'none' }))
    })

    socket.connect()
  }, [
    avatarUrl,
    configureIceServers,
    addVoiceReaction,
    displayName,
    drainIceCandidates,
    queueOrAddIceCandidate,
    room,
    shouldOfferToPeer,
    startPeerOffer,
    stopSignaling,
    upsertSignalParticipant,
    roomCues,
    useUbuntuVoice,
    user,
  ])

  const startLocalAudio = useCallback(async () => {
    if (!user) return false
    const manager = webrtcManagerRef.current ?? new WebRTCManager(user.id)
    webrtcManagerRef.current = manager

    try {
      const stream = await manager.initializeLocalMedia(true, false)
      stream.getAudioTracks().forEach((track) => {
        track.enabled = !mutedRef.current
      })
      beginSpeakingDetection(stream)
      setMicAvailable(true)
      return true
    } catch {
      setMicAvailable(false)
      setIsMuted(true)
      mutedRef.current = true
      setJoinError('Micrófono bloqueado: entraste en modo escucha.')
      return false
    }
  }, [beginSpeakingDetection, user])

  const joinRoom = useCallback(async () => {
    void roomCues.prepare().then(ready=>setCueAudioBlocked(!ready))
    if (!user || !room || isJoining || isJoined) return
    if (!claimInteractiveMediaSession({ accountId: user.id, key: mediaKey, kind: 'voice', title: room.title, path: `/app/voice/${encodeURIComponent(room.id)}`, close: () => closeMediaRef.current() })) return
    setIsJoining(true)
    setJoinError(null)
    setConnectionLabel('Conectando')
    let createdParticipant = false
    let incrementedCounter = false

    try {
      if (useUbuntuVoice) {
        if (visibleParticipantCount >= room.maxParticipants && !isJoined) {
          throw new Error('Sala llena')
        }

        const micStarted = await startLocalAudio()
        const nextMuted = isMuted || !micStarted
        setIsMuted(nextMuted)
        mutedRef.current = nextMuted

        const response = await joinFallbackVoiceRoom(room.id, {
          userName: displayName,
          userAvatarUrl: avatarUrl,
          isMuted: nextMuted,
        })
        createdParticipant = true
        const mappedParticipants = (response.participants ?? []).map(mapParticipant)
        setParticipants(mappedParticipants)
        setRoom((current) =>
          current && current.id === room.id
            ? { ...current, participantCount: Math.max(mappedParticipants.length, current.participantCount + 1) }
            : current,
        )
        setIsJoined(true)
        joinedRef.current = true
        writeJoinedRoomToStorage(user.id, room.id)
        const manager = webrtcManagerRef.current
        if (manager) {
          await connectSignaling(manager)
        }
        return
      }

      if (!supabase) throw new Error('Supabase no está configurado.')

      const existingResult = await supabase
        .from('voice_room_participants')
        .select('id')
        .eq('room_id', room.id)
        .eq('user_id', user.id)
        .limit(1)
      if (existingResult.error && !isMissingTableError(existingResult.error)) {
        throw existingResult.error
      }
      const wasAlreadyJoined = !existingResult.error && (existingResult.data?.length ?? 0) > 0
      if (!wasAlreadyJoined && visibleParticipantCount >= room.maxParticipants) {
        throw new Error('Sala llena')
      }

      const micStarted = await startLocalAudio()
      const nextMuted = isMuted || !micStarted
      setIsMuted(nextMuted)
      mutedRef.current = nextMuted

      const { error: upsertError } = await supabase
        .from('voice_room_participants')
        .upsert({
          room_id: room.id,
          user_id: user.id,
          user_name: displayName,
          user_avatar_url: avatarUrl,
          is_muted: nextMuted,
          joined_at: new Date().toISOString(),
        }, { onConflict: 'room_id,user_id' })

      if (upsertError) throw upsertError
      createdParticipant = !wasAlreadyJoined
      if (!wasAlreadyJoined) {
        await syncParticipantCounter(room.id, 1)
        incrementedCounter = true
      }

      setIsJoined(true)
      joinedRef.current = true
      writeJoinedRoomToStorage(user.id, room.id)
      await loadParticipants(room.id, { silent: true })
      const manager = webrtcManagerRef.current
      if (manager) {
        await connectSignaling(manager)
      }
    } catch (caught) {
      if (createdParticipant) {
        if (useUbuntuVoice) {
          await leaveFallbackVoiceRoom(room.id).catch(() => {})
        } else {
          await supabase
            ?.from('voice_room_participants')
            .delete()
            .eq('room_id', room.id)
            .eq('user_id', user.id)
        }
      }
      if (incrementedCounter) {
        await syncParticipantCounter(room.id, -1).catch(() => {})
      }
      stopLocalMedia()
      releaseInteractiveMediaSession(mediaKey)
      setIsJoined(false)
      joinedRef.current = false
      setConnectionLabel('Sin conectar')
      setJoinError(friendlyError(caught, 'No se pudo unir a la sala'))
    } finally {
      setIsJoining(false)
    }
  }, [
    avatarUrl,
    connectSignaling,
    displayName,
    isJoined,
    isJoining,
    isMuted,
    loadParticipants,
    mediaKey,
    room,
    startLocalAudio,
    stopLocalMedia,
    syncParticipantCounter,
    roomCues,
    useUbuntuVoice,
    user,
    visibleParticipantCount,
  ])

  const leaveRoom = useCallback(async (keepPage = false) => {
    if (isLeaving) return
    setIsLeaving(true)
    setJoinError(null)
    joinedRef.current = false
    setIsJoined(false)
    writeJoinedRoomToStorage(user?.id, null)
    stopLocalMedia()
    releaseInteractiveMediaSession(mediaKey)
    roomCues.leave()
    onExit?.()
    if (!keepPage) navigate('/app/voice', { replace: true })
    if (!user || !room) { setIsLeaving(false); return }
    try {
      if (useUbuntuVoice) {
        await leaveFallbackVoiceRoom(room.id, user.id)
      } else {
        if (!supabase) throw new Error('Supabase no está configurado.')
        const { data, error: deleteError } = await supabase.from('voice_room_participants')
          .delete().eq('room_id', room.id).eq('user_id', user.id).select('id')
        if (deleteError && !isMissingTableError(deleteError)) throw deleteError
        if ((data?.length ?? 0) > 0) await syncParticipantCounter(room.id, -1)
        window.dispatchEvent(new Event('novvin:voice-rooms-changed'))
      }
    } catch {
      toast.error('Saliste de la sala. No se pudo confirmar la salida en el servidor; la conexión ya está cerrada.')
    } finally { setIsLeaving(false) }
  }, [isLeaving, mediaKey, navigate, onExit, room, roomCues, stopLocalMedia, syncParticipantCounter, useUbuntuVoice, user])
  closeMediaRef.current = () => leaveRoom(true)

  const toggleMute = useCallback(async () => {
    if (!user || !room || !isJoined) return
    const nextMuted = !isMuted
    if (!nextMuted && !micAvailable) {
      const manager = webrtcManagerRef.current
      if (!manager) return
      try {
        const capture = await navigator.mediaDevices.getUserMedia({audio: true})
        if (!joinedRef.current) { capture.getTracks().forEach(track => track.stop()); return }
        const track = capture.getAudioTracks()[0]
        if (!track) { capture.getTracks().forEach(item => item.stop()); return }
        await manager.replaceOutgoingTrack('audio', track)
        beginSpeakingDetection(capture)
        setMicAvailable(true)
      } catch { setJoinError('Permite el acceso al micrófono para hablar en la sala.'); return }
    }
    setIsMuted(nextMuted)
    mutedRef.current = nextMuted
    webrtcManagerRef.current?.getLocalStream()?.getAudioTracks().forEach((track) => {
      track.enabled = !nextMuted
    })
    emitMuteState(nextMuted)
    roomCues.mute(user.id,nextMuted)

    try {
      if (useUbuntuVoice) {
        await joinFallbackVoiceRoom(room.id, {
          userName: displayName,
          userAvatarUrl: avatarUrl,
          isMuted: nextMuted,
        })
        return
      }

      if (!supabase) throw new Error('Supabase no está configurado.')

      const { error: updateError } = await supabase
        .from('voice_room_participants')
        .update({ is_muted: nextMuted })
        .eq('room_id', room.id)
        .eq('user_id', user.id)
      if (updateError && !isMissingTableError(updateError)) throw updateError
    } catch (caught) {
      setIsMuted(!nextMuted)
      mutedRef.current = !nextMuted
      webrtcManagerRef.current?.getLocalStream()?.getAudioTracks().forEach(track => { track.enabled = nextMuted })
      emitMuteState(!nextMuted)
      setJoinError(friendlyError(caught, 'No se pudo actualizar el micrófono'))
    }
  }, [avatarUrl, beginSpeakingDetection, displayName, emitMuteState, isJoined, isMuted, micAvailable, room, roomCues, useUbuntuVoice, user])

  async function stopVisual() {
    visualOperationRef.current++
    visualTrackRef.current?.stop(); visualTrackRef.current = null
    visualSourceRef.current = 'none'
    setVisualSource('none'); setLocalVideo(null)
    if (room && user && roomSocketReady(socketRef.current, acceptedSocketRef.current)) socketRef.current?.emit('media-status', { roomId: room.id, userId: user.id, isCameraSharing: false, isScreenSharing: false })
    try { await webrtcManagerRef.current?.removeOutgoingTrack('video') }
    catch { if (joinedRef.current) setJoinError('No se pudo detener el envío de video. Vuelve a conectar a la sala.') }
  }
  async function startVisual(source: 'camera' | 'screen', deviceId = cameraDevice) {
    if (!isJoined || mediaBusy) return
    setMediaBusy(true); setJoinError(null)
    const operation = ++visualOperationRef.current
    try {
      const capture = source === 'screen'
        ? await navigator.mediaDevices.getDisplayMedia({ video: true, audio: false })
        : await navigator.mediaDevices.getUserMedia({ video: deviceId ? { deviceId: { exact: deviceId }, width: { ideal: 1280 }, height: { ideal: 720 } } : { width: { ideal: 1280 }, height: { ideal: 720 } }, audio: false })
      const track = capture.getVideoTracks()[0]
      if (!track || !joinedRef.current || operation !== visualOperationRef.current) { capture.getTracks().forEach(value => value.stop()); return }
      try { await webrtcManagerRef.current!.replaceOutgoingTrack('video', track) } catch(error) { capture.getTracks().forEach(value => value.stop()); throw error }
      if (!joinedRef.current || operation !== visualOperationRef.current) { capture.getTracks().forEach(value => value.stop()); return }
      visualTrackRef.current = track; setLocalVideo(new MediaStream([track])); setVisualSource(source)
      visualSourceRef.current = source
      if (room && user && roomSocketReady(socketRef.current, acceptedSocketRef.current)) socketRef.current?.emit('media-status', { roomId: room.id, userId: user.id, isCameraSharing: source === 'camera', isScreenSharing: source === 'screen' })
      track.onended = () => { if (visualTrackRef.current?.id === track.id) void stopVisual() }
    } catch(error) {
      if (source === 'camera' || !(error instanceof DOMException && error.name === 'NotAllowedError')) setJoinError('No se pudo activar la cámara o pantalla. Revisa los permisos del navegador.')
    } finally { setMediaBusy(false) }
  }
  async function toggleVisual(source: 'camera' | 'screen') {
    if (visualSource === source) await stopVisual()
    else await startVisual(source)
  }
  async function selectMicrophone(deviceId: string) {
    const manager = webrtcManagerRef.current
    if (!isJoined || mediaBusy || !manager) return
    const operation = ++audioOperationRef.current
    setMediaBusy(true)
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: deviceId ? { deviceId: { exact: deviceId } } : true, video: false })
      const track = stream.getAudioTracks()[0]
      if (!track || !joinedRef.current || operation !== audioOperationRef.current || manager !== webrtcManagerRef.current) { stream.getTracks().forEach(value => value.stop()); return }
      track.enabled = !mutedRef.current
      try { await manager.replaceOutgoingTrack('audio', track) } catch(error) { track.stop(); throw error }
      if (!joinedRef.current || operation !== audioOperationRef.current) { track.stop(); return }
      setMicrophoneDevice(deviceId)
      beginSpeakingDetection(stream); setMicAvailable(true)
    } catch { setJoinError('No se pudo usar este micrófono. Comprueba sus permisos.') } finally { setMediaBusy(false) }
  }
  async function openDeviceSettings() {
    setShowDeviceSettings(value => !value)
    try { setDevices(await navigator.mediaDevices.enumerateDevices()) } catch { setJoinError('No se pudieron consultar los dispositivos.') }
  }
  useEffect(() => {
    if (!showDeviceSettings || !navigator.mediaDevices?.addEventListener) return
    let active = true
    const refresh = () => { void navigator.mediaDevices.enumerateDevices().then(values => { if (active) setDevices(values) }).catch(() => {}) }
    navigator.mediaDevices.addEventListener('devicechange', refresh)
    return () => { active = false; navigator.mediaDevices.removeEventListener('devicechange', refresh) }
  }, [showDeviceSettings])

  const toggleDeafen = useCallback(() => {
    setIsDeafened((current) => !current)
  }, [])

  useEffect(() => {
    void loadRoom()
  }, [loadRoom])

  useEffect(() => {
    if (!room?.id) return
    void loadParticipants(room.id)
  }, [loadParticipants, room?.id])

  useEffect(() => {
    if (!room || !user || !explicitJoin || autoJoinAttemptedRef.current || isJoined) return
    autoJoinAttemptedRef.current = true
    const nextParams = new URLSearchParams(searchParams)
    nextParams.delete('join')
    navigate({ search: nextParams.toString() }, { replace: true })
    void joinRoom()
  }, [explicitJoin, isJoined, joinRoom, navigate, room, searchParams, user])

  useEffect(() => {
    const client = supabase
    if (!client || !room?.id || useUbuntuVoice) return
    let refreshTimer: ReturnType<typeof setTimeout> | null = null
    const scheduleRefresh = () => {
      if (refreshTimer) clearTimeout(refreshTimer)
      refreshTimer = setTimeout(() => {
        void loadParticipants(room.id, { silent: true })
      }, 200)
    }
    const channel = client
      .channel(`conectate-web-voice-room-${room.id}`)
      .on('postgres_changes', {
        event: '*',
        schema: 'public',
        table: 'voice_room_participants',
        filter: `room_id=eq.${room.id}`,
      }, scheduleRefresh)
    const subscribedChannel = subscribeRealtimeChannel(channel, { reason: `voice-room-${room.id}` })

    return () => {
      if (refreshTimer) clearTimeout(refreshTimer)
      void removeRealtimeChannel(subscribedChannel ?? channel)
    }
  }, [loadParticipants, room?.id, useUbuntuVoice])

  useEffect(() => {
    if (!useUbuntuVoice || !room?.id) return
    const intervalId = window.setInterval(() => {
      void loadParticipants(room.id, { silent: true })
    }, 5000)
    return () => window.clearInterval(intervalId)
  }, [loadParticipants, room?.id, useUbuntuVoice])

  useEffect(() => {
    const operations = photoOperation
    return () => {
      operations.current++
      stopLocalMedia()
    }
  }, [stopLocalMedia])

  function dispatchRoomMessage(message: RoomChatMessage) {
    const socket = socketRef.current, owner = photoRoom.current
    if (!roomSocketReady(socket, acceptedSocketRef.current)) { setPhotoError('Espera a que conecte la sala para enviar.'); return false }
    setPhotoError('')
    setChatMessages(current => boundedRoomChat([...current, { ...message, delivery: 'sending' }]))
    socket!.timeout(8000).emit('chat-message', message, (error, result) => {
      if (owner !== photoRoom.current || deletedChatIds.current.has(`${message.userId}:${message.id}`)) return
      setChatMessages(current => current.map(item => item.id !== message.id || item.userId !== message.userId ? item : error || !result?.ok ? item.delivery === 'sent' ? item : { ...item, delivery: 'failed' } : { ...item, delivery: 'sent', timestamp: result.timestamp || item.timestamp, expiresAt: result.expiresAt || item.expiresAt }))
      if (error || !result?.ok) setPhotoError('No se pudo confirmar el envío. Puedes reintentar sin duplicarlo.')
    })
    return true
  }

  function removeRoomMessage(message: RoomChatMessage) {
    const socket = socketRef.current, owner = photoRoom.current
    if (!user || message.userId !== user.id || !roomSocketReady(socket, acceptedSocketRef.current)) { setPhotoError('Conecta la sala para eliminar el mensaje.'); return }
    const key = `${message.userId}:${message.id}`
    deletedChatIds.current.add(key)
    setChatMessages(current => current.filter(item => `${item.userId}:${item.id}` !== key))
    socket!.timeout(8000).emit('chat-message-delete', { roomId: message.roomId, userId: user.id, id: message.id }, (error, result) => {
      if (owner !== photoRoom.current) return
      if ((error || !result?.ok) && !confirmedDeletedChatIds.current.has(key)) {
        deletedChatIds.current.delete(key)
        setChatMessages(current => boundedRoomChat([...current, message]).sort((a,b) => a.timestamp - b.timestamp))
        setPhotoError('No se pudo confirmar el borrado. Vuelve a intentarlo.')
      }
    })
  }

  function sendRoomMessage(text: string, imageData = '', clearDraft = false) {
    const socket = socketRef.current
    if (!room || !user || !isJoined || !roomSocketReady(socket, acceptedSocketRef.current)) {
      setPhotoError('Espera a que termine de conectar la sala para enviar.')
      return false
    }
    if (!text.trim() && !imageData) return false
    if (imageData && Date.now() - lastPhotoSentRef.current < 2000) {
      setPhotoError('Espera dos segundos entre fotos y vuelve a enviar.')
      return false
    }
    const message: RoomChatMessage = { roomId: room.id, userId: user.id, userName: displayName, text: text.trim() || '📷 Foto', id: crypto.randomUUID(), timestamp: Date.now(), expiresAt: Date.now() + roomChatTtlMs, ...(imageData ? { imageData } : {}) }
    if (!dispatchRoomMessage(message)) return false
    receivedChatIds.current.add(`${message.userId}:${message.id}`)
    if (receivedChatIds.current.size > 400) receivedChatIds.current.delete(receivedChatIds.current.values().next().value!)
    if (imageData) lastPhotoSentRef.current = Date.now()
    if (clearDraft) { setChatDraft(''); setChatPhoto('') }
    setPhotoError('')
    return true
  }

  async function prepareChatPhoto(file: File) {
    if (photoBusy || !isJoined) return
    const owner = photoRoom.current, operation = ++photoOperation.current
    setPhotoBusy(true); setPhotoError('')
    try {
      const value = await prepareRoomImage(file)
      if (owner !== photoRoom.current || operation !== photoOperation.current) return
      setChatPhoto(value)
    } catch (caught) {
      if (owner === photoRoom.current && operation === photoOperation.current) setPhotoError(friendlyError(caught, 'No se pudo preparar la foto.'))
    } finally {
      if (owner === photoRoom.current && operation === photoOperation.current) setPhotoBusy(false)
    }
  }

  if (conflict) return minimized ? null : <MediaSessionConflict session={conflict}/>
  if (loading) {
    return <StatusView title="Cargando sala" description="Preparando sala de voz..." />
  }

  if (error && !room) {
    return <StatusView title="Voz no disponible" description={error} />
  }

  if (!room) {
    return <StatusView title="Sala no encontrada" description="La sala de voz no existe o fue eliminada." />
  }

  return (
    <section className={`voice-room-stage${minimized ? ' voice-room-minimized' : ''}${cinemaMode ? ' voice-room-cinema' : ''}${chatOpen ? ' voice-chat-open' : ''}`}>
      {minimized && isJoined ? <MovableVoicePanel accountId={user?.id||''}>
        <button type="button" className="voice-floating-title" onClick={() => navigate(`/app/voice/${room.id}`)}><Radio size={18}/><span><strong>{room.title}</strong><small>{connectionQuality}</small></span></button>
        <button type="button" className="icon-btn" aria-label={isMuted ? 'Activar micrófono en sala' : 'Silenciar micrófono en sala'} aria-pressed={isMuted} onClick={() => void toggleMute()}>{isMuted ? <MicOff/> : <Mic/>}</button>
        <button type="button" className={isDeafened?'icon-btn voice-panel-muted':'icon-btn'} aria-pressed={isDeafened} aria-label={isDeafened?'Escuchar sala':'Silenciar audio de sala'} onClick={toggleDeafen}>{isDeafened?<span className="voice-headphones-muted"><Headphones/></span>:<Headphones/>}</button>
        <button type="button" className="icon-btn danger" aria-label="Salir de sala de voz" disabled={isLeaving} onClick={() => void leaveRoom()}><PhoneOff/></button>
      </MovableVoicePanel> : null}
      <header className="voice-room-stage-header">
        <div className="voice-room-heading">
          <Link className="voice-back-link" to="/app/voice">
            <ArrowLeft aria-hidden="true" size={18} />
            Salas
          </Link>
          <span className="shell-kicker">Sala de voz</span>
          <h2>{room.title}</h2>
          <p>{room.topic}</p>
          <div className="voice-room-status-strip">
            <span className={isJoined ? 'voice-room-pill online' : 'voice-room-pill'}>
              <Radio aria-hidden="true" size={15} />
              {isJoined ? connectionQuality : 'Sin conectar'}
            </span>
            <span className="voice-room-pill">
              <Users aria-hidden="true" size={15} />
              {visibleParticipantCount}/{room.maxParticipants}
            </span>
            <span className="voice-room-pill">
              <ShieldCheck aria-hidden="true" size={15} />
              {connectionLabel}
            </span>
          </div>
        </div>

      </header>

      {cueAudioBlocked&&isJoined?<div role="status" className="voice-room-alert">El navegador requiere una pulsación para activar los avisos. <button className="ghost-btn" onClick={()=>void roomCues.prepare().then(ready=>setCueAudioBlocked(!ready))}>Habilitar sonidos</button></div>:null}
      {joinError ? (
        <article className="voice-room-alert" role="status">
          {joinError}
        </article>
      ) : null}

      <ReactionCloud items={voiceReactionItems} room/>
      <div className="voice-room-console">
        <main className="voice-room-speaker-panel">
          <div className="voice-panel-header">
            <div>
              <small>Conectados</small>
              <h3>Escenario</h3>
            </div>
            <button className="ghost-btn" type="button" onClick={() => void loadParticipants(room.id, { silent: true })}>
              <RefreshCw aria-hidden="true" size={16} />
              {syncingParticipants ? 'Actualizando' : 'Refrescar'}
            </button>
          </div>

          <div className="voice-video-grid">{localVideo ? <RoomVideo stream={localVideo} label={visualSource === 'screen' ? 'Tu pantalla' : 'Tu cámara'} /> : null}{Object.entries(remoteStreams).map(([peerId,stream]) => { const participant = remoteParticipants.find(value => value.socketId === peerId), source = remoteVisualSources[participant?.userId || '']; return source === 'none' ? null : <RoomVideo key={peerId} stream={stream} label={`${participant?.userName || 'Participante'}${source === 'screen' ? ' · Pantalla' : source === 'camera' ? ' · Cámara' : ''}`} /> })}</div>
          {showDeviceSettings ? <MediaDeviceDialog title="Audio y video" label="Ajustes de audio y video" onClose={() => setShowDeviceSettings(false)}><div className="voice-device-fields">
            <label>Micrófono<select aria-label="Micrófono" className="text-input" disabled={!isJoined || mediaBusy} value={microphoneDevice} onChange={event => void selectMicrophone(event.target.value)}><option value="">Micrófono predeterminado</option>{devices.filter(device => device.kind === 'audioinput' && device.deviceId && device.deviceId !== 'default').map((device,index) => <option key={device.deviceId} value={device.deviceId}>{device.label || `Micrófono ${index+1}`}</option>)}</select></label>
            <label>Cámara<select aria-label="Cámara" className="text-input" disabled={mediaBusy} value={cameraDevice} onChange={event => { const id = event.target.value; setCameraDevice(id); if (visualSource === 'camera') void startVisual('camera', id) }}><option value="">Cámara predeterminada</option>{devices.filter(device => device.kind === 'videoinput' && device.deviceId).map((device,index) => <option key={device.deviceId} value={device.deviceId}>{device.label || `Cámara ${index+1}`}</option>)}</select></label>
            <AudioOutputSelect devices={devices} value={outputDevice} onChange={setOutputDevice} />
            <fieldset className="voice-cue-settings"><legend>Sonidos de la sala</legend>
              <label><input type="checkbox" checked={cueSettings.presence} onChange={e=>setCueSettings({...cueSettings,presence:e.target.checked})}/>Entradas y salidas</label>
              <label><input type="checkbox" checked={cueSettings.microphone} onChange={e=>setCueSettings({...cueSettings,microphone:e.target.checked})}/>Silenciar y activar micrófonos</label>
              <label className="voice-cue-volume">Volumen de avisos · {Math.round(cueSettings.volume*100)} %<input type="range" min="0" max="100" value={Math.round(cueSettings.volume*100)} onChange={e=>setCueSettings({...cueSettings,volume:Number(e.target.value)/100})}/></label>
              <button className="ghost-btn" type="button" onClick={()=>void roomCues.prepare().then(ready=>setCueAudioBlocked(!ready))}>{cueAudioBlocked?'Habilitar sonidos':'Activar audio de avisos'}</button>
              <small>Avisos solo para ti. Silenciar el audio de la sala también los desactiva.</small>
            </fieldset>
            <small>Puedes ampliar cualquier cámara o pantalla. La pantalla compartida se detiene desde sus controles o desde el navegador.</small>
          </div></MediaDeviceDialog> : null}
          <div className="voice-participant-grid">
            {isJoined ? (
              <article className={isSpeaking ? 'voice-participant-card local speaking' : 'voice-participant-card local'}>
                <div className="voice-avatar-wrap">
                  {avatarUrl ? (
                    <SafeAvatar className="voice-avatar" url={avatarUrl} name={displayName} />
                  ) : (
                    <span className="voice-avatar voice-avatar-fallback">{initialsOf(displayName)}</span>
                  )}
                  <span className="voice-speaking-ring" aria-hidden="true" />
                </div>
                <div className="voice-participant-meta">
                  <strong>Tú</strong>
                  <span>{displayName}</span>
                </div>
                <span className={isMuted || !micAvailable ? 'voice-muted-badge active' : 'voice-muted-badge'}>
                  {isMuted || !micAvailable ? <MicOff aria-hidden="true" size={14} /> : <Mic aria-hidden="true" size={14} />}
                  {isMuted || !micAvailable ? 'Silenciado' : 'Activo'}
                </span>
              </article>
            ) : null}

            {remoteParticipants.map((participant) => (
              <article
                key={participant.socketId || participant.id}
                className={participant.isSpeaking ? 'voice-participant-card speaking' : 'voice-participant-card'}
              >
                <div className="voice-avatar-wrap">
                  {participant.userAvatarUrl ? (
                    <SafeAvatar className="voice-avatar" url={participant.userAvatarUrl} name={participant.userName || 'Participante'} />
                  ) : (
                    <span className="voice-avatar voice-avatar-fallback">{initialsOf(participant.userName || 'Usuario')}</span>
                  )}
                  <span className="voice-speaking-ring" aria-hidden="true" />
                </div>
                <div className="voice-participant-meta">
                  <strong>{participant.userName || 'Usuario'}</strong>
                  <span>{participant.isSpeaking ? 'Hablando' : participant.joinedAt ? formatDateTime(participant.joinedAt) : 'En sala'}</span>
                </div>
                <span className={participant.isMuted ? 'voice-muted-badge active' : 'voice-muted-badge'}>
                  {participant.isMuted ? <MicOff aria-hidden="true" size={14} /> : <Mic aria-hidden="true" size={14} />}
                  {participant.isMuted === null ? 'Sincronizando' : participant.isMuted ? 'Silenciado' : 'Activo'}
                </span>
              </article>
            ))}

            {!isJoined && remoteParticipants.length === 0 ? (
              <article className="voice-empty-state">
                <Headphones aria-hidden="true" />
                <strong>La sala está lista</strong>
                <span>Únete para activar audio y ver participantes en tiempo real.</span>
              </article>
            ) : null}
          </div>

          <div className="voice-remote-audio">
            {Object.entries(remoteStreams).map(([peerId, stream]) => (
              <LiveStreamAudio key={peerId} stream={stream} muted={isDeafened} outputDeviceId={outputDevice} label={remoteParticipants.find(value => value.socketId === peerId)?.userName || 'Participante'} />
            ))}
          </div>
        </main>

        <aside className="voice-room-side-panel">
          <section className="voice-side-section voice-chat-section">
            <button type="button" className="voice-chat-heading" aria-expanded={chatOpen} onClick={() => setChatOpen(value => !value)}><MessageCircle size={18}/><strong>Chat de la sala</strong>{chatUnread > 0 ? <span>{chatUnread}</span> : null}</button>
            {chatOpen ? <>
              <div className="voice-chat-messages" role="log" aria-label="Mensajes de la sala" aria-live="polite">
                {!chatMessages.length ? <p className="voice-room-subtle">Los mensajes de esta sesión aparecen aquí al entrar en la sala.</p> : null}
                {chatMessages.map(message => <article key={`${message.userId}:${message.id}`} className={message.userId === user?.id ? 'own' : ''}><header><strong>{message.userId === user?.id ? 'Tú' : message.userName}</strong><time>{new Date(message.timestamp).toLocaleTimeString('es', { hour: '2-digit', minute: '2-digit' })}</time>{message.userId === user?.id ? <details className="voice-message-menu"><summary aria-label="Opciones del mensaje"><MoreHorizontal size={17}/></summary><button type="button" disabled={!signalingReady} onClick={() => removeRoomMessage(message)}><Trash2 size={15}/>Eliminar para todos</button></details> : null}</header>{message.imageData ? <RoomChatPhoto source={message.imageData} author={message.userName} active={!minimized && chatOpen} /> : null}<MessageText text={message.text}/>{message.delivery === 'sending' ? <small role="status">Enviando…</small> : message.delivery === 'failed' ? <button type="button" className="voice-message-retry" onClick={() => dispatchRoomMessage(message)}>Reintentar envío</button> : null}</article>)}
                <div ref={chatEndRef}/>
              </div>
              <form className="voice-chat-composer" onSubmit={event => {
                event.preventDefault()
                if (!photoBusy) sendRoomMessage(chatDraft, chatPhoto, true)
              }} onPaste={event => {
                const file = firstRoomClipboardImage(event.clipboardData.items)
                if (!file) return
                event.preventDefault()
                void prepareChatPhoto(file)
              }}>
                <input ref={photoInput} type="file" accept="image/jpeg,image/png,image/webp" hidden onChange={event => {
                  const file = event.target.files?.[0]; event.target.value = ''; if (file) void prepareChatPhoto(file)
                }} />
                {chatPhoto ? <div className="voice-photo-draft"><img src={chatPhoto} alt="Foto lista para enviar" /><button type="button" aria-label="Cancelar foto" onClick={() => { photoOperation.current++; setChatPhoto(''); setPhotoError('') }}><X size={18}/></button></div> : null}
                <button type="button" title="Añadir foto" aria-label="Añadir foto a la sala" disabled={!isJoined || !signalingReady || photoBusy} onClick={() => photoInput.current?.click()}><ImagePlus size={18}/></button>
                <input aria-label="Mensaje para la sala" placeholder={isJoined ? signalingReady ? 'Escribe un mensaje…' : 'Conectando chat…' : 'Únete para escribir'} spellCheck lang="es" maxLength={2000} value={chatDraft} disabled={!isJoined || !signalingReady} onChange={event => setChatDraft(event.target.value)}/>
                <button type="submit" aria-label="Enviar mensaje a la sala" disabled={!isJoined || !signalingReady || photoBusy || (!chatDraft.trim() && !chatPhoto)}><Send size={18}/></button>
              </form>
              {photoBusy ? <div className="voice-photo-preparing" role="status"><small>Preparando foto…</small><button type="button" onClick={() => { photoOperation.current++; setPhotoBusy(false); setChatPhoto('') }}>Cancelar</button></div> : null}
              {photoError ? <p role="alert">{photoError}</p> : null}
              <small className="voice-chat-retention">Chat temporal: los mensajes y las fotos desaparecen a los 90 minutos.</small>
            </> : null}
          </section>
          <section className="voice-side-section">
            <small>Detalle</small>
            <h3>{room.title}</h3>
            <div className="voice-room-metrics">
              <span className="voice-metric">
                <strong>{visibleParticipantCount}</strong>
                <small>personas</small>
              </span>
              <span className="voice-metric">
                <strong>{connectedPeerCount}</strong>
                <small>conexiones de audio</small>
              </span>
            </div>
          </section>

          <section className="voice-side-section">
            <small>Creador</small>
            {room.creatorId ? (
              <Link className="voice-creator-link" to={profilePathFor(room.creatorId, user?.id)}>
                {room.creatorAvatarUrl ? (
                  <SafeAvatar url={room.creatorAvatarUrl} name={room.creatorName || 'Creador'} />
                ) : (
                  <span className="avatar-fallback">{initialsOf(room.creatorName || room.title)}</span>
                )}
                <span>{room.creatorName || 'Perfil'}</span>
              </Link>
            ) : (
              <span className="voice-room-subtle">Sin creador visible</span>
            )}
          </section>

          <section className="voice-connection-card">
            <div>
              <small>Estado de voz</small>
              <strong>{connectionQuality}</strong>
            </div>
            <div className="voice-connection-bars" aria-hidden="true">
              <span className={isJoined ? 'active' : ''} />
              <span className={socketRef.current?.connected ? 'active' : ''} />
              <span className={connectedPeerCount > 0 ? 'active' : ''} />
            </div>
          </section>
        </aside>
      </div>
        <footer className={`voice-room-controls${isJoined ? ' joined' : ''}`} aria-label="Controles de sala">
          <button className="voice-control-btn" type="button" onClick={() => setCinemaMode(value => !value)} aria-pressed={cinemaMode} aria-label={cinemaMode ? 'Salir del modo cine' : 'Activar modo cine'} title={cinemaMode ? 'Salir del modo cine' : 'Modo cine'}>{cinemaMode ? <Minimize2 size={18}/> : <Maximize2 size={18}/>}Modo cine</button>
          <button className="voice-control-btn" type="button" title={chatOpen ? 'Ocultar chat' : 'Mostrar chat'} aria-label={chatOpen ? 'Ocultar chat de la sala' : 'Mostrar chat de la sala'} aria-expanded={chatOpen} onClick={() => { setChatOpen(value => !value); window.setTimeout(() => document.querySelector('.voice-chat-section')?.scrollIntoView({ block: 'nearest', behavior: 'smooth' }), 0) }}><MessageCircle size={18}/>Chat{chatUnread ? ` (${chatUnread})` : ''}</button>
          <button className="voice-control-btn" type="button" onClick={() => void openDeviceSettings()} aria-label="Ajustes de audio y video" aria-expanded={showDeviceSettings}><Settings size={18} />Ajustes</button>
          {isJoined ? <RoomReactionMenu onReaction={emoji => { if (!room || !user || !roomSocketReady(socketRef.current, acceptedSocketRef.current)) return; const reactionId = crypto.randomUUID(); addVoiceReaction({ id: reactionId, emoji }); socketRef.current?.emit('reaction', { roomId: room.id, userId: user.id, emoji, reactionId, timestamp: Date.now() }) }} /> : null}
          {!isJoined ? (
            <button className="voice-control-btn primary" type="button" onClick={() => void joinRoom()} disabled={isJoining}>
              <Headphones aria-hidden="true" size={18} />
              {isJoining ? 'Entrando' : 'Unirse'}
            </button>
          ) : (
            <>
              <button
                className={isMuted ? 'voice-control-btn muted' : 'voice-control-btn'}
                type="button"
                onClick={() => void toggleMute()}
                aria-pressed={isMuted}
                aria-label={isMuted ? 'Activar micrófono' : 'Silenciar micrófono'}
                title={isMuted ? 'Activar micrófono' : 'Silenciar micrófono'}
              >
                {isMuted ? <MicOff aria-hidden="true" size={18} /> : <Mic aria-hidden="true" size={18} />}
                {isMuted ? 'Silenciado' : 'Micrófono'}
              </button>
              <button
                className={isDeafened ? 'voice-control-btn muted' : 'voice-control-btn'}
                type="button"
                onClick={toggleDeafen}
                aria-pressed={isDeafened}
                aria-label={isDeafened ? 'Escuchar sala' : 'Silenciar audio de sala'}
                title={isDeafened ? 'Escuchar sala' : 'Silenciar audio de sala'}
              >
                {isDeafened ? <VolumeX aria-hidden="true" size={18} /> : <Volume2 aria-hidden="true" size={18} />}
                {isDeafened ? 'Audio apagado' : 'Audio'}
              </button>
              <button className="voice-control-btn" type="button" onClick={() => void toggleVisual('camera')} disabled={mediaBusy} aria-pressed={visualSource === 'camera'} aria-label={visualSource === 'camera' ? 'Apagar cámara' : 'Activar cámara'}>{visualSource === 'camera' ? <CameraOff size={18}/> : <Camera size={18}/>}Cámara</button>
              {typeof navigator.mediaDevices?.getDisplayMedia === 'function' ? <button className="voice-control-btn" type="button" onClick={() => void toggleVisual('screen')} disabled={mediaBusy} aria-pressed={visualSource === 'screen'}><MonitorUp size={18}/>{visualSource === 'screen' ? 'Detener pantalla' : 'Compartir pantalla'}</button> : null}
              <button className="voice-control-btn danger" type="button" onClick={() => void leaveRoom()} disabled={isLeaving}>
                <PhoneOff aria-hidden="true" size={18} />
                {isLeaving ? 'Saliendo' : 'Salir'}
              </button>
            </>
          )}
        </footer>
    </section>
  )
}
