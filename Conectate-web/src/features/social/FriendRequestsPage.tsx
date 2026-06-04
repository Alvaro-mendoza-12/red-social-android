import { useCallback, useEffect, useState } from 'react'
import { StatusView } from '../../components/StatusView'
import { formatDateTime, friendlyError, textOf, type GenericRow } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
import { shouldUseUbuntuBackend } from '../../core/ubuntuBackend'
import {
  loadFallbackFriends,
  sendFallbackFriendRequest,
  updateFallbackFriendRequest,
} from '../../core/fallbackData'
import { isFallbackAuthConfigured } from '../../core/auth/fallbackAuth'
import { supabase } from '../../core/supabase'
import { isMissingTableError } from '../../core/utils'

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

  const loadRequests = useCallback(async () => {
    if (!user) {
      setLoading(false)
      return
    }
    setLoading(true)
    setError(null)
    try {
      if (useUbuntu) {
        const response = await loadFallbackFriends('incoming')
        setItems(response.items.map(mapRequest))
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
        .eq('receiver_id', user.id)
        .order('created_at', { ascending: false })
      if (queryError) throw queryError
      setItems(((data ?? []) as GenericRow[]).map(mapRequest))
      setTableAvailable(true)
    } catch (caught) {
      if (isMissingTableError(caught)) {
        setTableAvailable(false)
        setError('La tabla friends no existe en este entorno.')
      } else {
        setError(friendlyError(caught, 'No se pudieron cargar las solicitudes'))
      }
    } finally {
      setLoading(false)
    }
  }, [useUbuntu, user])

  useEffect(() => {
    void loadRequests()
  }, [loadRequests])

  async function updateStatus(id: string, status: 'accepted' | 'rejected') {
    if (!user) return
    try {
      if (useUbuntu) {
        await updateFallbackFriendRequest(id, status)
        await loadRequests()
        return
      }
      if (!supabase) return
      const { error: updateError } = await supabase
        .from('friends')
        .update({ status })
        .eq('id', id)
        .eq('receiver_id', user.id)
      if (updateError) throw updateError
      await loadRequests()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo actualizar la solicitud'))
    }
  }

  async function sendRequest(targetId: string) {
    if (!user) return
    const cleanTarget = targetId.trim().toLowerCase()
    if (!cleanTarget) return
    try {
      if (useUbuntu) {
        await sendFallbackFriendRequest(cleanTarget)
        await loadRequests()
        return
      }
      if (!supabase) return
      const { error: insertError } = await supabase.from('friends').insert({
        sender_id: user.id,
        receiver_id: cleanTarget,
        status: 'pending',
      })
      if (insertError) throw insertError
      await loadRequests()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo enviar solicitud'))
    }
  }

  if (!useUbuntu && !supabase && !isFallbackAuthConfigured) {
    return <StatusView title="Backend no configurado" description="Configura VITE_FALLBACK_API_URL." />
  }
  if (loading) return <StatusView title="Cargando solicitudes" description="Espera un momento..." />
  if (error && !tableAvailable) return <StatusView title="Solicitudes no disponibles" description={error} />

  return (
    <section className="section-stack">
      <header className="section-header">
        <h2>Solicitudes de amistad</h2>
        <p>Gestiona invitaciones en tu servidor Ubuntu.</p>
      </header>
      {error ? <p className="error-text">{error}</p> : null}
      {items.length === 0 ? (
        <StatusView title="Sin solicitudes" description="No tienes invitaciones pendientes." />
      ) : (
        <ul className="list-stack">
          {items.map((item) => (
            <li key={item.id} className="list-row">
              <div>
                <strong>{item.senderId}</strong>
                <small>{formatDateTime(item.createdAt)}</small>
              </div>
              {item.status === 'pending' ? (
                <div className="inline-actions">
                  <button type="button" onClick={() => void updateStatus(item.id, 'accepted')}>
                    Aceptar
                  </button>
                  <button type="button" className="ghost-btn" onClick={() => void updateStatus(item.id, 'rejected')}>
                    Rechazar
                  </button>
                </div>
              ) : (
                <span>{item.status}</span>
              )}
            </li>
          ))}
        </ul>
      )}
      <form
        className="inline-form"
        onSubmit={(event) => {
          event.preventDefault()
          const form = event.currentTarget
          const input = form.elements.namedItem('targetId') as HTMLInputElement
          void sendRequest(input.value)
          input.value = ''
        }}
      >
        <input name="targetId" placeholder="ID de usuario destino" required />
        <button type="submit">Enviar solicitud</button>
      </form>
    </section>
  )
}
