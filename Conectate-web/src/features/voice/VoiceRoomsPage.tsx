import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { Headphones, Radio, RefreshCw, Users } from 'lucide-react'
import { StatusView } from '../../components/StatusView'
import { readCachedValue, writeCachedValue } from '../../core/localCache'
import {
  removeRealtimeChannel,
  reportSupabaseServiceRestrictionIfPresent,
  resolveSupabasePublicUrl,
  subscribeRealtimeChannel,
  supabase,
} from '../../core/supabase'
import { formatDateTime, friendlyError, textOf, type GenericRow } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
import { loadFallbackVoiceRooms } from '../../core/fallbackData'
import { isFallbackAuthConfigured } from '../../core/auth/fallbackAuth'

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

const joinedRoomStorageKey = 'conectate-web-voice-joined-room'
const roomsCacheKey = 'voice:rooms:list'

function profilePathFor(profileId: string, currentUserId?: string): string {
  const clean = profileId.trim()
  if (!clean) return '/app/profile'
  if (currentUserId && clean === currentUserId) return '/app/profile'
  return `/app/profile/${clean}`
}

function initialsOf(name: string): string {
  const clean = name.trim()
  if (!clean) return 'S'
  return clean
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? '')
    .join('') || 'S'
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

function resolveParticipantCount(row: GenericRow): number {
  const values = [
    Number(row.current_participants ?? Number.NaN),
    Number(row.participant_count ?? Number.NaN),
    Number(row.active_participants ?? Number.NaN),
  ].filter((value) => Number.isFinite(value))
  return values.length > 0 ? Math.max(0, values[0] ?? 0) : 0
}

function mapRoom(row: GenericRow): VoiceRoom {
  return {
    id: textOf(row, ['id'], crypto.randomUUID()),
    title: textOf(row, ['name', 'title', 'readable_id'], 'Sala de voz'),
    topic: textOf(row, ['topic', 'description'], 'Sin descripción'),
    creatorId: textOf(row, ['creator_id', 'owner_id'], ''),
    creatorName: textOf(row, ['creator_name', 'owner_name'], ''),
    creatorAvatarUrl: resolveSupabasePublicUrl('avatars', textOf(row, ['creator_avatar_url', 'avatar_url'], '')),
    participantCount: resolveParticipantCount(row),
    maxParticipants: Math.max(1, Number(row.max_participants ?? 10)),
    updatedAt: textOf(row, ['updated_at', 'created_at'], ''),
  }
}

export function VoiceRoomsPage() {
  const { user, isFallbackSession } = useAuth()
  const useUbuntuVoice = isFallbackAuthConfigured || isFallbackSession
  const [rooms, setRooms] = useState<VoiceRoom[]>([])
  const [loading, setLoading] = useState(true)
  const [syncing, setSyncing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [joinedRoomId, setJoinedRoomId] = useState<string | null>(() => readJoinedRoomFromStorage())

  const joinedRoom = useMemo(
    () => rooms.find((room) => room.id === joinedRoomId) ?? null,
    [joinedRoomId, rooms],
  )
  const activeRooms = rooms.filter((room) => room.participantCount > 0).length
  const totalParticipants = rooms.reduce((sum, room) => sum + room.participantCount, 0)

  const loadRooms = useCallback(async (options?: { silent?: boolean }) => {
    if (useUbuntuVoice) {
      if (options?.silent) {
        setSyncing(true)
      } else {
        setLoading(true)
        setError(null)
      }
      try {
        const response = await loadFallbackVoiceRooms()
        const mapped = (response.rooms ?? response.items ?? []).map(mapRoom)
        setRooms(mapped)
        writeCachedValue(roomsCacheKey, mapped)
      } catch (caught) {
        if (!options?.silent) {
          setError(friendlyError(caught, 'No se pudieron cargar las salas de voz desde Ubuntu'))
        }
      } finally {
        setLoading(false)
        setSyncing(false)
      }
      return
    }

    if (!supabase) {
      setLoading(false)
      setError('Configura VITE_FALLBACK_API_URL para usar salas de voz en Ubuntu.')
      return
    }

    if (options?.silent) {
      setSyncing(true)
    } else {
      setLoading(true)
      setError(null)
    }

    try {
      const { data, error: queryError } = await supabase
        .from('voice_rooms')
        .select('*')
        .order('updated_at', { ascending: false })
        .limit(60)
      if (queryError) throw queryError

      let mapped = ((data ?? []) as GenericRow[]).map(mapRoom)
      const creatorIds = Array.from(new Set(mapped.map((room) => room.creatorId).filter(Boolean)))
      if (creatorIds.length > 0) {
        const { data: profileRows } = await supabase
          .from('profiles')
          .select('id,display_name,username,avatar_url,photo_url,profile_image')
          .in('id', creatorIds)
        const profileById = new Map<string, { name: string; avatarUrl: string }>()
        ;((profileRows ?? []) as GenericRow[]).forEach((profile) => {
          const id = textOf(profile, ['id'], '')
          if (!id) return
          profileById.set(id, {
            name: textOf(profile, ['display_name', 'username'], '') || id,
            avatarUrl: resolveSupabasePublicUrl('avatars', textOf(profile, ['avatar_url', 'photo_url', 'profile_image'], '')),
          })
        })
        mapped = mapped.map((room) => {
          const profile = profileById.get(room.creatorId)
          if (!profile) return room
          return {
            ...room,
            creatorName: room.creatorName.trim() || profile.name,
            creatorAvatarUrl: room.creatorAvatarUrl.trim() || profile.avatarUrl,
          }
        })
      }
      setRooms(mapped)
      writeCachedValue(roomsCacheKey, mapped)
    } catch (caught) {
      reportSupabaseServiceRestrictionIfPresent('voice-rooms-load', caught)
      if (!options?.silent) {
        setError(friendlyError(caught, 'No se pudieron cargar las salas de voz'))
      }
    } finally {
      setLoading(false)
      setSyncing(false)
    }
  }, [useUbuntuVoice])

  useEffect(() => {
    const cached = readCachedValue<VoiceRoom[]>(roomsCacheKey, 1000 * 60 * 60 * 8)
    if (cached && cached.length > 0) {
      setRooms(cached)
      setLoading(false)
      void loadRooms({ silent: true })
      return
    }
    void loadRooms({ silent: false })
  }, [loadRooms])

  useEffect(() => {
    writeJoinedRoomToStorage(joinedRoomId)
  }, [joinedRoomId])

  useEffect(() => {
    const client = supabase
    if (!client || useUbuntuVoice) return
    let refreshTimer: ReturnType<typeof setTimeout> | null = null
    const scheduleRefresh = () => {
      if (refreshTimer) {
        clearTimeout(refreshTimer)
      }
      refreshTimer = setTimeout(() => {
        void loadRooms({ silent: true })
      }, 260)
    }

    const channel = client
      .channel('conectate-web-voice-rooms-live')
      .on('postgres_changes', { event: '*', schema: 'public', table: 'voice_rooms' }, scheduleRefresh)
    const subscribedChannel = subscribeRealtimeChannel(channel, { reason: 'voice-rooms-live' })

    return () => {
      if (refreshTimer) clearTimeout(refreshTimer)
      void removeRealtimeChannel(subscribedChannel ?? channel)
    }
  }, [loadRooms, useUbuntuVoice])

  useEffect(() => {
    if (!useUbuntuVoice) return
    const intervalId = window.setInterval(() => {
      void loadRooms({ silent: true })
    }, 7000)
    return () => window.clearInterval(intervalId)
  }, [loadRooms, useUbuntuVoice])

  if (loading) return <StatusView title="Cargando voz" description="Buscando salas..." />
  if (error && rooms.length === 0) return <StatusView title="Voz no disponible" description={error} />

  return (
    <section className="section-stack voice-lobby">
      <header className="voice-lobby-hero">
        <div className="voice-lobby-copy">
          <span className="shell-kicker">Voz en vivo</span>
          <h2>Salas de Voz</h2>
          <p>Canales para conversar con tu comunidad.</p>
        </div>
        <div className="voice-lobby-stats" aria-label="Resumen de salas">
          <span className="voice-lobby-stat">
            <Radio aria-hidden="true" />
            <strong>{activeRooms}</strong>
            <small>activas</small>
          </span>
          <span className="voice-lobby-stat">
            <Users aria-hidden="true" />
            <strong>{totalParticipants}</strong>
            <small>conectados</small>
          </span>
          <span className="voice-lobby-stat">
            <Headphones aria-hidden="true" />
            <strong>{rooms.length}</strong>
            <small>salas</small>
          </span>
        </div>
      </header>

      {error ? (
        <article className="voice-room-alert" role="status">
          {error}
        </article>
      ) : null}

      {joinedRoom ? (
        <article className="voice-active-card">
          <div className="voice-room-card-head">
            <span className="voice-room-avatar avatar-fallback">{initialsOf(joinedRoom.title)}</span>
            <div>
              <small>Sala activa</small>
              <h3>{joinedRoom.title}</h3>
              <p>{joinedRoom.topic}</p>
            </div>
          </div>
          <div className="voice-room-card-actions">
            <Link className="primary-btn" to={`/app/voice/${joinedRoom.id}`}>
              Continuar
            </Link>
            <button className="ghost-btn" type="button" onClick={() => setJoinedRoomId(null)}>
              Limpiar
            </button>
          </div>
        </article>
      ) : null}

      <div className="voice-list-toolbar">
            <strong>Canales de voz</strong>
        <button className="ghost-btn" type="button" onClick={() => void loadRooms({ silent: true })}>
          <RefreshCw aria-hidden="true" size={16} />
          {syncing ? 'Actualizando' : 'Actualizar'}
        </button>
      </div>

      {rooms.length === 0 ? (
        <StatusView title="No hay salas activas" description="Las salas creadas en la aplicación aparecerán aquí." />
      ) : (
        <div className="voice-room-grid">
          {rooms.map((room) => {
            const joined = joinedRoomId === room.id
            const occupancy = Math.min(100, Math.round((room.participantCount / room.maxParticipants) * 100))
            return (
              <article key={room.id} className={joined ? 'voice-room-card active' : 'voice-room-card'}>
                <div className="voice-room-card-head">
                  {room.creatorAvatarUrl ? (
                    <img src={room.creatorAvatarUrl} alt={room.creatorName || 'Creador'} className="voice-room-avatar" />
                  ) : (
                    <span className="voice-room-avatar avatar-fallback">{initialsOf(room.creatorName || room.title)}</span>
                  )}
                  <div>
                    <div className="row-between voice-room-title-row">
                      <h3>{room.title}</h3>
                      <small>{formatDateTime(room.updatedAt)}</small>
                    </div>
                    <p>{room.topic}</p>
                  </div>
                </div>

                <div className="voice-room-status-row">
                  <span className="folder-tag">
                    {room.participantCount}/{room.maxParticipants} participantes
                  </span>
                  {room.creatorId ? (
                    <Link className="inline-link" to={profilePathFor(room.creatorId, user?.id)}>
                      @{room.creatorName || 'creador'}
                    </Link>
                  ) : null}
                </div>

                <div className="voice-room-meter" aria-hidden="true">
                  <span style={{ width: `${occupancy}%` }} />
                </div>

                <div className="voice-room-card-actions">
                  <Link
                    className={joined ? 'primary-btn' : 'ghost-btn'}
                    to={`/app/voice/${room.id}`}
                    onClick={() => setJoinedRoomId(room.id)}
                  >
                    {joined ? 'Entrar' : 'Unirse'}
                  </Link>
                </div>
              </article>
            )
          })}
        </div>
      )}
    </section>
  )
}
