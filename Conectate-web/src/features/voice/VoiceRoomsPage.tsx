import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { readCachedValue, writeCachedValue } from '../../core/localCache'
import { supabase } from '../../core/supabase'
import { formatDateTime, friendlyError, textOf, type GenericRow } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'

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
  if (!clean) return 'U'
  return clean[0]?.toUpperCase() ?? 'U'
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
    creatorAvatarUrl: textOf(row, ['creator_avatar_url', 'avatar_url'], ''),
    participantCount: resolveParticipantCount(row),
    maxParticipants: Number(row.max_participants ?? 10),
    updatedAt: textOf(row, ['updated_at', 'created_at'], ''),
  }
}

export function VoiceRoomsPage() {
  const { user } = useAuth()
  const [rooms, setRooms] = useState<VoiceRoom[]>([])
  const [loading, setLoading] = useState(true)
  const [syncing, setSyncing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [joiningRoomId, setJoiningRoomId] = useState<string | null>(null)
  const [joinedRoomId, setJoinedRoomId] = useState<string | null>(() => readJoinedRoomFromStorage())

  const joinedRoom = useMemo(
    () => rooms.find((room) => room.id === joinedRoomId) ?? null,
    [joinedRoomId, rooms],
  )

  const loadRooms = useCallback(async (options?: { silent?: boolean }) => {
    if (!supabase) {
      setLoading(false)
      setError('Supabase no está configurado.')
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
            avatarUrl: textOf(profile, ['avatar_url', 'photo_url', 'profile_image'], ''),
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
      if (!options?.silent) {
        setError(friendlyError(caught, 'No se pudieron cargar las salas de voz'))
      }
    } finally {
      setLoading(false)
      setSyncing(false)
    }
  }, [])

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
    if (!client) return
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
      .subscribe()

    return () => {
      if (refreshTimer) clearTimeout(refreshTimer)
      void client.removeChannel(channel)
    }
  }, [loadRooms])

  function optimisticAdjustParticipants(roomId: string, delta: number) {
    setRooms((current) =>
      current.map((room) =>
        room.id === roomId
          ? { ...room, participantCount: Math.max(0, room.participantCount + delta) }
          : room,
      ),
    )
  }

  async function fallbackParticipantUpdate(roomId: string, delta: number) {
    if (!supabase) return
    const columns = ['current_participants', 'participant_count', 'active_participants']
    for (const column of columns) {
      const readResult = await supabase.from('voice_rooms').select('*').eq('id', roomId).limit(1)
      if (readResult.error) {
        if (isMissingColumnError(readResult.error)) {
          continue
        }
        throw readResult.error
      }

      const row = ((readResult.data ?? []) as GenericRow[])[0]
      const currentCount = Number(row?.[column] ?? 0)
      const nextCount = Math.max(0, (Number.isFinite(currentCount) ? currentCount : 0) + delta)
      const { error: updateError } = await supabase
        .from('voice_rooms')
        .update({ [column]: nextCount })
        .eq('id', roomId)
      if (updateError) {
        if (isMissingColumnError(updateError)) {
          continue
        }
        throw updateError
      }
      return
    }
  }

  async function syncParticipantCounter(roomId: string, delta: number) {
    if (!supabase) return
    const rpcName = delta > 0 ? 'increment_participant_count' : 'decrement_participant_count'
    const rpcResult = await supabase.rpc(rpcName, { room_id: roomId })
    if (!rpcResult.error) return
    if (!isMissingFunctionError(rpcResult.error)) throw rpcResult.error
    await fallbackParticipantUpdate(roomId, delta)
  }

  async function joinRoom(roomId: string) {
    if (!supabase || !roomId || joiningRoomId) return
    if (joinedRoomId === roomId) return
    setJoiningRoomId(roomId)
    setError(null)
    const previousRoomId = joinedRoomId
    try {
      if (previousRoomId && previousRoomId !== roomId) {
        optimisticAdjustParticipants(previousRoomId, -1)
        await syncParticipantCounter(previousRoomId, -1)
      }
      optimisticAdjustParticipants(roomId, 1)
      await syncParticipantCounter(roomId, 1)
      setJoinedRoomId(roomId)
      await loadRooms({ silent: true })
    } catch (caught) {
      setJoinedRoomId(previousRoomId ?? null)
      setError(friendlyError(caught, 'No se pudo unir a la sala de voz'))
      await loadRooms({ silent: true })
    } finally {
      setJoiningRoomId(null)
    }
  }

  async function leaveRoom(targetRoomId?: string) {
    if (!supabase || joiningRoomId) return
    const roomId = (targetRoomId ?? joinedRoomId ?? '').trim()
    if (!roomId) return
    setJoiningRoomId(roomId)
    setError(null)
    try {
      optimisticAdjustParticipants(roomId, -1)
      await syncParticipantCounter(roomId, -1)
      if (joinedRoomId === roomId) {
        setJoinedRoomId(null)
      }
      await loadRooms({ silent: true })
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo salir de la sala'))
      await loadRooms({ silent: true })
    } finally {
      setJoiningRoomId(null)
    }
  }

  if (loading) return <StatusView title="Cargando voz" description="Buscando salas..." />
  if (error && rooms.length === 0) return <StatusView title="Voz no disponible" description={error} />

  return (
    <section className="section-stack">
      <header className="section-header">
        <h2>Salas de Voz</h2>
        <p>Únete y sal de salas en web con sincronización en Supabase.</p>
      </header>

      {error ? (
        <article className="surface-card">
          <small>{error}</small>
        </article>
      ) : null}

      {joinedRoom ? (
        <article className="surface-card row-between">
          <div>
            <strong>En sala: {joinedRoom.title}</strong>
            <p>{joinedRoom.topic}</p>
          </div>
          <button className="chip-btn" type="button" disabled={joiningRoomId === joinedRoom.id} onClick={() => void leaveRoom()}>
            {joiningRoomId === joinedRoom.id ? 'Saliendo...' : 'Salir'}
          </button>
        </article>
      ) : null}

      {syncing ? <small>Sincronizando salas de voz...</small> : null}

      {rooms.length === 0 ? (
        <StatusView title="No hay salas" description="Crea una sala desde móvil o backend para verla aquí." />
      ) : (
        <div className="list-stack">
          {rooms.map((room) => {
            const joined = joinedRoomId === room.id
            return (
              <article key={room.id} className="surface-card conversation-card">
                <div className="conversation-main">
                  {room.creatorAvatarUrl ? (
                    <img src={room.creatorAvatarUrl} alt={room.creatorName || 'Creador'} className="conversation-avatar" />
                  ) : (
                    <span className="conversation-avatar avatar-fallback">{initialsOf(room.creatorName || room.title)}</span>
                  )}
                  <div className="conversation-copy">
                    <div className="row-between">
                      <h3>{room.title}</h3>
                      <small>{formatDateTime(room.updatedAt)}</small>
                    </div>
                    <p>{room.topic}</p>
                    <div className="row-actions">
                      <span className="folder-tag">
                        {room.participantCount}/{room.maxParticipants} participantes
                      </span>
                      {room.creatorId ? (
                        <Link className="inline-link" to={profilePathFor(room.creatorId, user?.id)}>
                          @{room.creatorName || room.creatorId}
                        </Link>
                      ) : null}
                    </div>
                  </div>
                </div>
                <div className="row-actions conversation-actions">
                  <button
                    className={joined ? 'chip-btn active' : 'ghost-btn'}
                    type="button"
                    disabled={joiningRoomId === room.id}
                    onClick={() => void (joined ? leaveRoom(room.id) : joinRoom(room.id))}
                  >
                    {joiningRoomId === room.id ? 'Procesando...' : joined ? 'Salir de sala' : 'Unirse'}
                  </button>
                </div>
              </article>
            )
          })}
        </div>
      )}
    </section>
  )
}
