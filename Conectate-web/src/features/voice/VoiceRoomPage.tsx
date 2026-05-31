import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
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
import { readFallbackSession } from '../../core/auth/fallbackAuth'
import WebRTCManager from './WebRTCManager'

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
  isMuted: boolean
  isSpeaking: boolean
  joinedAt: string
}

type SignalingParticipant = {
  socketId: string
  userId: string
  name?: string
  avatarUrl?: string
  isMuted?: boolean
}

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

type ServerEvents = {
  connect: () => void
  disconnect: (reason: string) => void
  connect_error: (error: Error) => void
  'current-participants': (participants: SignalingParticipant[]) => void
  'user-joined': (participant: SignalingParticipant) => void
  'user-left': (socketId: string) => void
  offer: (payload: OfferPayload) => void
  answer: (payload: AnswerPayload) => void
  'ice-candidate': (payload: IcePayload) => void
  'speaking-status': (payload: SpeakingPayload) => void
  'mute-state': (payload: MutePayload) => void
  'auth-error': (payload: { error?: string }) => void
}

type ClientEvents = {
  'join-room': (payload: { roomId: string; userId: string; name: string; avatarUrl: string }) => void
  offer: (payload: { target: string; offer: RTCSessionDescriptionInit; userId: string; name: string }) => void
  answer: (payload: { target: string; answer: RTCSessionDescriptionInit; userId: string; name: string }) => void
  'ice-candidate': (payload: { target: string; candidate: RTCIceCandidateInit; userId: string; name: string }) => void
  'speaking-status': (payload: SpeakingPayload) => void
  'mute-state': (payload: MutePayload) => void
}

type VoiceSocket = Socket<ServerEvents, ClientEvents>

const joinedRoomStorageKey = 'conectate-web-voice-joined-room'
const fallbackSignalingUrl = 'https://voice.srv1495648.hstgr.cloud'

function readSignalingUrl(): string {
  const configured = String(import.meta.env.VITE_SIGNALING_URL ?? '').trim()
  const fallbackApi = String(import.meta.env.VITE_FALLBACK_API_URL ?? '').trim()
  return configured || fallbackApi || fallbackSignalingUrl
}

function readJoinedRoomFromStorage(): string | null {
  if (typeof window === 'undefined') return null
  const value = window.localStorage.getItem(joinedRoomStorageKey)
  const clean = (value ?? '').trim()
  return clean.length > 0 ? clean : null
}

function writeJoinedRoomToStorage(roomId: string | null) {
  if (typeof window === 'undefined') return
  if (roomId) {
    window.localStorage.setItem(joinedRoomStorageKey, roomId)
    return
  }
  window.localStorage.removeItem(joinedRoomStorageKey)
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
    isMuted: Boolean(row.is_muted),
    isSpeaking: Boolean(row.is_speaking),
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

function RemoteAudio({ stream, muted }: { stream: MediaStream; muted: boolean }) {
  const audioRef = useRef<HTMLAudioElement | null>(null)

  useEffect(() => {
    const element = audioRef.current
    if (!element) return
    element.srcObject = stream
    element.muted = muted
    void element.play().catch(() => {})
    return () => {
      element.srcObject = null
    }
  }, [muted, stream])

  return <audio ref={audioRef} autoPlay playsInline />
}

export function VoiceRoomPage() {
  const { user, isFallbackSession } = useAuth()
  const { roomId } = useParams<{ roomId: string }>()
  const navigate = useNavigate()
  const [room, setRoom] = useState<VoiceRoom | null>(null)
  const [participants, setParticipants] = useState<Participant[]>([])
  const [remoteStreams, setRemoteStreams] = useState<Record<string, MediaStream>>({})
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

  const socketRef = useRef<VoiceSocket | null>(null)
  const webrtcManagerRef = useRef<WebRTCManager | null>(null)
  const audioContextRef = useRef<AudioContext | null>(null)
  const analyserRef = useRef<AnalyserNode | null>(null)
  const speakingFrameRef = useRef<number | null>(null)
  const mutedRef = useRef(false)
  const joinedRef = useRef(false)
  const autoJoinAttemptedRef = useRef(false)
  const socketToUserIdRef = useRef(new Map<string, string>())
  const pendingIceRef = useRef(new Map<string, RTCIceCandidateInit[]>())

  const displayName = useMemo(() => displayNameFromUser(user), [user])
  const avatarUrl = useMemo(() => avatarFromUser(user), [user])
  const localParticipantInList = participants.some((participant) => participant.userId === user?.id)
  const visibleParticipantCount = Math.max(
    room?.participantCount ?? 0,
    participants.length + (isJoined && !localParticipantInList ? 1 : 0),
  )
  const remoteParticipants = participants.filter((participant) => participant.userId !== user?.id)
  const connectedPeerCount = Object.values(peerStates).filter((state) => state === 'connected').length
  const connectionQuality = connectedPeerCount > 0 ? 'P2P activo' : isJoined ? 'Conectado a señalización' : 'Sin conectar'

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
    const socket = socketRef.current
    if (socket) {
      socket.removeAllListeners()
      socket.disconnect()
      socketRef.current = null
    }
    socketToUserIdRef.current.clear()
    pendingIceRef.current.clear()
    setPeerStates({})
    setRemoteStreams({})
  }, [])

  const stopLocalMedia = useCallback(() => {
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
    if (!socketRef.current || !room || !user) return
    socketRef.current.emit('mute-state', {
      roomId: room.id,
      userId: user.id,
      isMuted: nextMuted,
    })
  }, [room, user])

  const emitSpeakingState = useCallback((nextSpeaking: boolean) => {
    if (!socketRef.current || !room || !user) return
    socketRef.current.emit('speaking-status', {
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
      if (isFallbackSession) {
        const payload = await loadFallbackVoiceRoom(roomId)
        setRoom(mapRoom(payload.room, roomId))
        setParticipants((payload.participants ?? []).map(mapParticipant))
        return
      }

      if (!supabase) {
        setError('Supabase no está configurado.')
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
  }, [isFallbackSession, roomId])

  const loadParticipants = useCallback(async (targetRoomId: string, options?: { silent?: boolean }) => {
    if (isFallbackSession) {
      if (options?.silent) {
        setSyncingParticipants(true)
      }

      try {
        const payload = await loadFallbackVoiceRoom(targetRoomId)
        const mapped = (payload.participants ?? []).map(mapParticipant)
        setParticipants((current) => {
          const signalOnly = current.filter((participant) => participant.socketId && !mapped.some((db) => db.userId === participant.userId))
          return [...mapped, ...signalOnly]
        })
        setRoom((current) =>
          current && current.id === targetRoomId
            ? { ...current, participantCount: Math.max(mapped.length, current.participantCount) }
            : current,
        )

        const self = mapped.find((participant) => participant.userId === user?.id)
        if (self) {
          setIsJoined(true)
          joinedRef.current = true
          setIsMuted(self.isMuted)
        }
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
        return [...mapped, ...signalOnly]
      })

      const self = mapped.find((participant) => participant.userId === user?.id)
      if (self) {
        setIsJoined(true)
        joinedRef.current = true
        setIsMuted(self.isMuted)
      }
    } catch (caught) {
      if (!isMissingTableError(caught)) {
        reportSupabaseServiceRestrictionIfPresent('voice-participants-load', caught)
        setJoinError(friendlyError(caught, 'No se pudieron cargar los participantes'))
      }
    } finally {
      setSyncingParticipants(false)
    }
  }, [isFallbackSession, user?.id])

  const fallbackParticipantUpdate = useCallback(async (targetRoomId: string, delta: number) => {
    if (isFallbackSession) {
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
  }, [isFallbackSession])

  const syncParticipantCounter = useCallback(async (targetRoomId: string, delta: number) => {
    if (isFallbackSession) {
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
  }, [fallbackParticipantUpdate, isFallbackSession])

  const upsertSignalParticipant = useCallback((participant: SignalingParticipant) => {
    const socketId = String(participant.socketId ?? '').trim()
    const userId = String(participant.userId ?? '').trim()
    if (!socketId || !userId || userId === user?.id) return
    socketToUserIdRef.current.set(socketId, userId)
    setParticipants((current) => {
      const nextParticipant: Participant = {
        id: `signal-${socketId}`,
        socketId,
        userId,
        userName: String(participant.name ?? '').trim() || userId,
        userAvatarUrl: String(participant.avatarUrl ?? '').trim(),
        isMuted: Boolean(participant.isMuted),
        isSpeaking: false,
        joinedAt: new Date().toISOString(),
      }
      const withoutDuplicate = current.filter((item) => item.userId !== userId && item.socketId !== socketId)
      return [...withoutDuplicate, nextParticipant]
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
    const socket = socketRef.current
    if (!manager || !socket || !user || !peerId || manager.hasPeerConnection(peerId)) return

    try {
      manager.createPeerConnection(peerId, true)
      const offer = await manager.createOffer(peerId)
      socket.emit('offer', {
        target: peerId,
        offer,
        userId: user.id,
        name: displayName,
      })
      await drainIceCandidates(peerId)
    } catch (caught) {
      setJoinError(friendlyError(caught, 'No se pudo iniciar la conexión de voz'))
    }
  }, [displayName, drainIceCandidates, user])

  const shouldOfferToPeer = useCallback((peerId: string) => {
    const ownSocketId = socketRef.current?.id
    if (!ownSocketId || !peerId) return false
    return ownSocketId.localeCompare(peerId) > 0
  }, [])

  const configureIceServers = useCallback(async (manager: WebRTCManager, accessToken: string) => {
    const signalingUrl = readSignalingUrl()
    try {
      const response = await fetch(`${signalingUrl.replace(/\/$/, '')}/turn-credentials`, {
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
    const accessToken = isFallbackSession
      ? readFallbackSession()?.accessToken?.trim()
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
      auth: { token: accessToken },
      autoConnect: false,
      reconnection: true,
      reconnectionAttempts: 8,
      reconnectionDelay: 700,
      timeout: 12000,
    })

    socketRef.current = socket
    manager.onIceCandidate((target, candidate) => {
      socket.emit('ice-candidate', {
        target,
        candidate,
        userId: user.id,
        name: displayName,
      })
    })
    manager.onTrack((_track, stream, peerId) => {
      setRemoteStreams((current) => ({ ...current, [peerId]: stream }))
    })
    manager.onConnectionState((state, peerId) => {
      setPeerStates((current) => ({ ...current, [peerId]: state }))
    })

    socket.on('connect', () => {
      setConnectionLabel('Señalización conectada')
      socket.emit('join-room', {
        roomId: room.id,
        userId: user.id,
        name: displayName,
        avatarUrl,
      })
    })

    socket.on('connect_error', (socketError) => {
      setConnectionLabel('Reconectando voz')
      setJoinError(socketError.message || 'No se pudo conectar con el servidor de voz')
    })

    socket.on('disconnect', () => {
      setConnectionLabel('Reconectando voz')
    })

    socket.on('auth-error', (payload) => {
      setJoinError(payload.error || 'La señalización de voz rechazó la sesión')
    })

    socket.on('current-participants', (signalParticipants) => {
      signalParticipants.forEach(upsertSignalParticipant)
      signalParticipants.forEach((participant) => {
        if (shouldOfferToPeer(participant.socketId)) {
          void startPeerOffer(participant.socketId)
        }
      })
    })

    socket.on('user-joined', (participant) => {
      upsertSignalParticipant(participant)
      if (shouldOfferToPeer(participant.socketId)) {
        void startPeerOffer(participant.socketId)
      }
    })

    socket.on('user-left', (socketId) => {
      const userIdForSocket = socketToUserIdRef.current.get(socketId)
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
            manager.createPeerConnection(payload.sender, false)
          }
          const answer = await manager.createAnswer(payload.sender, payload.offer)
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

    socket.on('speaking-status', (payload) => {
      setParticipants((current) =>
        current.map((participant) =>
          participant.userId === payload.userId ? { ...participant, isSpeaking: payload.isSpeaking } : participant,
        ),
      )
    })

    socket.on('mute-state', (payload) => {
      setParticipants((current) =>
        current.map((participant) =>
          participant.userId === payload.userId ? { ...participant, isMuted: payload.isMuted } : participant,
        ),
      )
    })

    socket.connect()
  }, [
    avatarUrl,
    configureIceServers,
    displayName,
    drainIceCandidates,
    queueOrAddIceCandidate,
    room,
    shouldOfferToPeer,
    startPeerOffer,
    stopSignaling,
    upsertSignalParticipant,
    isFallbackSession,
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
    if (!user || !room || isJoining || isJoined) return
    setIsJoining(true)
    setJoinError(null)
    setConnectionLabel('Conectando')
    let createdParticipant = false
    let incrementedCounter = false

    try {
      if (isFallbackSession) {
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
        writeJoinedRoomToStorage(room.id)
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
      writeJoinedRoomToStorage(room.id)
      await loadParticipants(room.id, { silent: true })
      const manager = webrtcManagerRef.current
      if (manager) {
        await connectSignaling(manager)
      }
    } catch (caught) {
      if (createdParticipant) {
        if (isFallbackSession) {
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
    isFallbackSession,
    isMuted,
    loadParticipants,
    room,
    startLocalAudio,
    stopLocalMedia,
    syncParticipantCounter,
    user,
    visibleParticipantCount,
  ])

  const leaveRoom = useCallback(async () => {
    if (!user || !room || isLeaving) {
      navigate('/app/voice')
      return
    }

    setIsLeaving(true)
    setJoinError(null)
    try {
      if (isFallbackSession) {
        await leaveFallbackVoiceRoom(room.id)
        setIsJoined(false)
        joinedRef.current = false
        writeJoinedRoomToStorage(null)
        stopLocalMedia()
        await loadParticipants(room.id, { silent: true })
        navigate('/app/voice')
        return
      }

      if (!supabase) throw new Error('Supabase no está configurado.')

      const { data, error: deleteError } = await supabase
        .from('voice_room_participants')
        .delete()
        .eq('room_id', room.id)
        .eq('user_id', user.id)
        .select('id')

      if (deleteError && !isMissingTableError(deleteError)) throw deleteError
      if ((data?.length ?? 0) > 0) await syncParticipantCounter(room.id, -1)
      setIsJoined(false)
      joinedRef.current = false
      writeJoinedRoomToStorage(null)
      stopLocalMedia()
      await loadParticipants(room.id, { silent: true })
      navigate('/app/voice')
    } catch (caught) {
      setJoinError(friendlyError(caught, 'No se pudo salir de la sala'))
    } finally {
      setIsLeaving(false)
    }
  }, [isFallbackSession, isLeaving, loadParticipants, navigate, room, stopLocalMedia, syncParticipantCounter, user])

  const toggleMute = useCallback(async () => {
    if (!user || !room || !isJoined) return
    const nextMuted = !isMuted
    setIsMuted(nextMuted)
    mutedRef.current = nextMuted
    webrtcManagerRef.current?.getLocalStream()?.getAudioTracks().forEach((track) => {
      track.enabled = !nextMuted
    })
    emitMuteState(nextMuted)

    try {
      if (isFallbackSession) {
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
      emitMuteState(!nextMuted)
      setJoinError(friendlyError(caught, 'No se pudo actualizar el micrófono'))
    }
  }, [avatarUrl, displayName, emitMuteState, isFallbackSession, isJoined, isMuted, room, user])

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
    if (!room || !user || autoJoinAttemptedRef.current || isJoined) return
    const storedRoomId = readJoinedRoomFromStorage()
    if (storedRoomId && storedRoomId !== room.id) return
    autoJoinAttemptedRef.current = true
    void joinRoom()
  }, [isJoined, joinRoom, room, user])

  useEffect(() => {
    const client = supabase
    if (!client || !room?.id || isFallbackSession) return
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
  }, [isFallbackSession, loadParticipants, room?.id])

  useEffect(() => {
    if (!isFallbackSession || !room?.id) return
    const intervalId = window.setInterval(() => {
      void loadParticipants(room.id, { silent: true })
    }, 5000)
    return () => window.clearInterval(intervalId)
  }, [isFallbackSession, loadParticipants, room?.id])

  useEffect(() => {
    return () => {
      stopLocalMedia()
    }
  }, [stopLocalMedia])

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
    <section className="voice-room-stage">
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

        <div className="voice-room-controls" aria-label="Controles de sala">
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
                title={isDeafened ? 'Escuchar sala' : 'Silenciar audio de sala'}
              >
                {isDeafened ? <VolumeX aria-hidden="true" size={18} /> : <Volume2 aria-hidden="true" size={18} />}
                {isDeafened ? 'Sordo' : 'Audio'}
              </button>
              <button className="voice-control-btn danger" type="button" onClick={() => void leaveRoom()} disabled={isLeaving}>
                <PhoneOff aria-hidden="true" size={18} />
                {isLeaving ? 'Saliendo' : 'Salir'}
              </button>
            </>
          )}
        </div>
      </header>

      {joinError ? (
        <article className="voice-room-alert" role="status">
          {joinError}
        </article>
      ) : null}

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

          <div className="voice-participant-grid">
            {isJoined ? (
              <article className={isSpeaking ? 'voice-participant-card local speaking' : 'voice-participant-card local'}>
                <div className="voice-avatar-wrap">
                  {avatarUrl ? (
                    <img className="voice-avatar" src={avatarUrl} alt={displayName} />
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
                  {isMuted || !micAvailable ? 'Mute' : 'Live'}
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
                    <img className="voice-avatar" src={participant.userAvatarUrl} alt={participant.userName || 'Participante'} />
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
                  {participant.isMuted ? 'Mute' : 'Live'}
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

          <div className="voice-remote-audio" aria-hidden="true">
            {Object.entries(remoteStreams).map(([peerId, stream]) => (
              <RemoteAudio key={peerId} stream={stream} muted={isDeafened} />
            ))}
          </div>
        </main>

        <aside className="voice-room-side-panel">
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
                <small>P2P</small>
              </span>
            </div>
          </section>

          <section className="voice-side-section">
            <small>Creador</small>
            {room.creatorId ? (
              <Link className="voice-creator-link" to={profilePathFor(room.creatorId, user?.id)}>
                {room.creatorAvatarUrl ? (
                  <img src={room.creatorAvatarUrl} alt={room.creatorName || 'Creador'} />
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
    </section>
  )
}
