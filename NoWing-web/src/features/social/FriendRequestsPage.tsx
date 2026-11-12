import { useCallback, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { RefreshCw } from 'lucide-react'
import { SafeAvatar } from '../../components/SafeAvatar'
import { publicProfileSummaries } from '../../core/publicProfileSummaries'
import { StatusView } from '../../components/StatusView'
import { formatDateTime, friendlyError, textOf, type GenericRow } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
import { shouldUseUbuntuBackend } from '../../core/ubuntuBackend'
import {
  loadFallbackFriends,
  cancelFallbackFriendRequest,
  updateFallbackFriendRequest,
} from '../../core/fallbackData'
import { isFallbackAuthConfigured } from '../../core/auth/fallbackAuth'
import { supabase } from '../../core/supabase'
import { isMissingTableError } from '../../core/utils'
import { getStorageReadIdentity, type StorageReadIdentity } from '../../core/media/storageReadSession'

type FriendRequest = {
  id: string
  senderId: string
  receiverId: string
  status: string
  createdAt: string
}

function mapRequest(row: GenericRow): FriendRequest {
  return {
    id: textOf(row, ['id'], crypto.randomUUID()),
    senderId: textOf(row, ['sender_id', 'senderId'], ''),
    receiverId: textOf(row, ['receiver_id', 'receiverId'], ''),
    status: textOf(row, ['status'], 'pending'),
    createdAt: textOf(row, ['created_at'], ''),
  }
}

export function FriendRequestsPage() {
  const { user, isFallbackSession } = useAuth()
  const useUbuntu = shouldUseUbuntuBackend(isFallbackSession)
  const [items, setItems] = useState<FriendRequest[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [tableAvailable, setTableAvailable] = useState(true)
  const [profiles, setProfiles] = useState(new Map<string, GenericRow>())
  const [pendingId, setPendingId] = useState('')
  const [direction, setDirection] = useState<'incoming' | 'outgoing'>('incoming')
  const [cancellationAvailable, setCancellationAvailable] = useState(false)
  const sequence = useRef(0)
  // Writes remain locked by account while tabs change. View invalidation only
  // discards old UI results; it must not permit a contradictory second PATCH.
  const mutations = useRef(new Map<string, { id: string; identity: StorageReadIdentity }>())
  const activeOwner = useRef(user?.id)
  activeOwner.current = user?.id
  const mounted = useRef(false)

  const loadRequests = useCallback(async () => {
    const identity = getStorageReadIdentity()
    const current = ++sequence.current
    if (!user) {
      setLoading(false)
      return
    }
    setLoading(true)
    setError(null)
    try {
      if (useUbuntu) {
        const response = await loadFallbackFriends(direction)
        const mapped = response.items.map(mapRequest)
        const people = await publicProfileSummaries(mapped.map(item => direction === 'incoming' ? item.senderId : item.receiverId)).catch(() => new Map())
        if (sequence.current !== current || identity !== getStorageReadIdentity()) return
        setCancellationAvailable(response.capabilities?.cancelOutgoingRequests === true)
        setProfiles(people)
        setItems(mapped)
        setTableAvailable(true)
        return
      }

      if (!supabase) {
        setError('Configura VITE_FALLBACK_API_URL para usar amistad en Ubuntu.')
        setLoading(false)
        return
      }

      const { data, error: queryError } = await supabase
        .from('friends')
        .select('*')
        .eq(direction === 'incoming' ? 'receiver_id' : 'sender_id', user.id)
        .order('created_at', { ascending: false })
      if (queryError) throw queryError
      const mapped = ((data ?? []) as GenericRow[]).map(mapRequest)
      const people = await publicProfileSummaries(mapped.map(item => direction === 'incoming' ? item.senderId : item.receiverId)).catch(() => new Map())
      if (sequence.current !== current || identity !== getStorageReadIdentity()) return
      setProfiles(people)
      setItems(mapped)
      setTableAvailable(true)
    } catch (caught) {
      if (sequence.current !== current || identity !== getStorageReadIdentity()) return
      if (isMissingTableError(caught)) {
        setTableAvailable(false)
        setError('Las solicitudes no están disponibles en este momento.')
      } else {
        setError(friendlyError(caught, 'No se pudieron cargar las solicitudes'))
      }
    } finally {
      if (sequence.current === current && identity === getStorageReadIdentity()) setLoading(false)
    }
  }, [direction, useUbuntu, user])

  const latestLoadRequests = useRef(loadRequests)
  latestLoadRequests.current = loadRequests

  useEffect(() => {
    mounted.current = true
    const operation = mutations.current.get(user?.id ?? '')
    setPendingId(operation?.identity === getStorageReadIdentity() ? operation.id : '')
    setItems([])
    setCancellationAvailable(false)
    setProfiles(new Map())
    void loadRequests()
    return () => { sequence.current++; mounted.current = false }
  }, [loadRequests, user?.id])

  async function updateStatus(id: string, status: 'accepted' | 'rejected' | 'cancelled') {
    const identity = getStorageReadIdentity()
    if (!user || mutations.current.get(user.id)?.identity === identity) return
    if (status === 'cancelled' && useUbuntu && !cancellationAvailable) return
    const owner = user.id
    const view = sequence.current
    const operation = { id, identity }
    mutations.current.set(owner, operation)
    setPendingId(id)
    try {
      if (useUbuntu) {
        if (status === 'cancelled') await cancelFallbackFriendRequest(id)
        else await updateFallbackFriendRequest(id, status)
        if (!mounted.current || activeOwner.current !== owner || identity !== getStorageReadIdentity()) return
        await latestLoadRequests.current()
        return
      }
      if (!supabase) return
      if (status === 'cancelled') {
        const { data, error: deleteError } = await supabase.from('friends').delete()
          .eq('id', id).eq('sender_id', owner).eq('status', 'pending').select('id')
        if (deleteError) throw deleteError
        if (!data?.length) throw new Error('La solicitud cambió. Actualiza la lista antes de intentarlo de nuevo.')
      } else {
        const { error: updateError } = await supabase.from('friends').update({ status })
          .eq('id', id).eq('receiver_id', owner)
        if (updateError) throw updateError
      }
      if (!mounted.current || activeOwner.current !== owner || identity !== getStorageReadIdentity()) return
      await latestLoadRequests.current()
    } catch (caught) {
      if (sequence.current !== view || identity !== getStorageReadIdentity()) return
      setError(friendlyError(caught, 'No se pudo actualizar la solicitud'))
    } finally {
      if (mutations.current.get(owner) === operation) {
        mutations.current.delete(owner)
        if (mounted.current && activeOwner.current === owner && identity === getStorageReadIdentity()) setPendingId('')
      }
    }
  }

  if (!useUbuntu && !supabase && !isFallbackAuthConfigured) {
    return <StatusView title="Backend no configurado" description="Configura VITE_FALLBACK_API_URL." />
  }
  if (loading) return <StatusView title="Cargando solicitudes" description="Espera un momento..." />
  if (error && !tableAvailable) return <StatusView title="Solicitudes no disponibles" description={error} />

  return (
    <section className="section-stack friend-requests-page">
      <header className="section-header row-between">
        <div><h2>Solicitudes de amistad</h2>
        <p>Elige con quién conectar.</p></div>
        <button className="icon-btn" type="button" aria-label="Actualizar solicitudes" disabled={Boolean(pendingId)} onClick={() => void loadRequests()}><RefreshCw size={19}/></button>
      </header>
      <nav className="friend-request-tabs" aria-label="Solicitudes de amistad"><button className="chip-btn" type="button" aria-pressed={direction==='incoming'} onClick={() => setDirection('incoming')}>Recibidas</button><button className="chip-btn" type="button" aria-pressed={direction==='outgoing'} onClick={() => setDirection('outgoing')}>Enviadas</button></nav>
      {error ? <p className="error-text">{error}</p> : null}
      {items.length === 0 ? (
        <StatusView title={direction==='incoming'?'Sin solicitudes recibidas':'Sin solicitudes enviadas'} description={direction==='incoming'?'Las nuevas invitaciones aparecerán aquí.':'Busca personas y envíales una invitación desde su perfil.'} />
      ) : (
        <ul className="list-stack">
          {items.map((item) => {
            const personId = direction === 'incoming' ? item.senderId : item.receiverId
            const person = profiles.get(personId) || {}
            const name = textOf(person, ['display_name', 'username'], 'Usuario de Novvin')
            return (
            <li key={item.id} className="list-row">
              <Link className="friend-request-person" to={'/app/profile/' + personId}>
                <SafeAvatar url={textOf(person, ['avatar_url'])} name={name} mediaType={textOf(person, ['avatar_media_type'])} size="lg" />
              <div><strong>{name}</strong>
                <small>{formatDateTime(item.createdAt)}</small>
              </div>
              </Link>
              {item.status === 'pending' && direction === 'incoming' ? (
                <div className="inline-actions">
                  <button className="primary-btn" type="button" disabled={Boolean(pendingId)} onClick={() => void updateStatus(item.id, 'accepted')}>
                    Aceptar
                  </button>
                  <button type="button" className="ghost-btn" disabled={Boolean(pendingId)} onClick={() => void updateStatus(item.id, 'rejected')}>
                    Rechazar
                  </button>
                </div>
              ) : item.status === 'pending' && direction === 'outgoing' && (!useUbuntu || cancellationAvailable) ? (
                <button type="button" className="ghost-btn" disabled={Boolean(pendingId)} onClick={() => void updateStatus(item.id, 'cancelled')}>
                  Cancelar solicitud
                </button>
              ) : (
                <span className="friend-request-status">{item.status === 'pending' ? 'Pendiente de respuesta' : item.status === 'accepted' ? 'Amigos' : 'Solicitud rechazada'}</span>
              )}
            </li>
          )})}
        </ul>
      )}
      <Link className="ghost-btn" to="/app/search">Buscar personas</Link>
    </section>
  )
}
