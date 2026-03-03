import { useCallback, useEffect, useState } from 'react'
import { StatusView } from '../../components/StatusView'
import { supabase } from '../../core/supabase'
import { formatDateTime, friendlyError, isMissingTableError, textOf, type GenericRow } from '../../core/utils'
import { useAuth } from '../auth/AuthProvider'

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
    senderId: textOf(row, ['sender_id'], ''),
    receiverId: textOf(row, ['receiver_id'], ''),
    status: textOf(row, ['status'], 'pending'),
    createdAt: textOf(row, ['created_at'], ''),
  }
}

export function FriendRequestsPage() {
  const { user } = useAuth()
  const [items, setItems] = useState<FriendRequest[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [tableAvailable, setTableAvailable] = useState(true)

  const loadRequests = useCallback(async () => {
    if (!supabase || !user) {
      setLoading(false)
      return
    }
    setLoading(true)
    setError(null)
    try {
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
        setError('La tabla friends no existe en este entorno Supabase.')
      } else {
        setError(friendlyError(caught, 'No se pudieron cargar las solicitudes'))
      }
    } finally {
      setLoading(false)
    }
  }, [user])

  useEffect(() => {
    void loadRequests()
  }, [loadRequests])

  async function updateStatus(id: string, status: 'accepted' | 'rejected') {
    if (!supabase || !user) return
    const { error: updateError } = await supabase
      .from('friends')
      .update({ status })
      .eq('id', id)
      .eq('receiver_id', user.id)
    if (updateError) {
      setError(friendlyError(updateError, 'No se pudo actualizar la solicitud'))
      return
    }
    await loadRequests()
  }

  async function sendRequest(targetId: string) {
    if (!supabase || !user) return
    const cleanTarget = targetId.trim().toLowerCase()
    if (!cleanTarget) return
    const { error: insertError } = await supabase.from('friends').insert({
      sender_id: user.id,
      receiver_id: cleanTarget,
      status: 'pending',
    })
    if (insertError) {
      setError(friendlyError(insertError, 'No se pudo enviar solicitud'))
      return
    }
    await loadRequests()
  }

  if (!supabase) {
    return <StatusView title="Supabase no configurado" description="Configura .env para usar amistad." />
  }
  if (loading) return <StatusView title="Cargando solicitudes" description="Espera un momento..." />
  if (error && !tableAvailable) return <StatusView title="Solicitudes no disponibles" description={error} />

  return (
    <section className="section-stack">
      <header className="section-header">
        <h2>Solicitudes de amistad</h2>
        <p>Acepta/rechaza solicitudes y envía una nueva por ID de usuario.</p>
      </header>

      <form
        className="composer"
        onSubmit={(event) => {
          event.preventDefault()
          const form = event.currentTarget
          const input = form.elements.namedItem('target_id') as HTMLInputElement | null
          void sendRequest(input?.value ?? '')
          if (input) input.value = ''
        }}
      >
        <input name="target_id" className="text-input" placeholder="UUID del usuario destino" />
        <button className="primary-btn" type="submit">
          Enviar solicitud
        </button>
      </form>

      {error ? <p className="error-text">{error}</p> : null}

      {items.length === 0 ? (
        <StatusView title="Sin solicitudes" description="No tienes solicitudes pendientes." />
      ) : (
        <div className="list-stack">
          {items.map((request) => (
            <article key={request.id} className="surface-card">
              <div className="row-between">
                <strong>De: {request.senderId}</strong>
                <small>{formatDateTime(request.createdAt)}</small>
              </div>
              <p>Estado: {request.status}</p>
              {request.status === 'pending' ? (
                <div className="row-actions">
                  <button className="ghost-btn" type="button" onClick={() => void updateStatus(request.id, 'accepted')}>
                    Aceptar
                  </button>
                  <button className="ghost-btn" type="button" onClick={() => void updateStatus(request.id, 'rejected')}>
                    Rechazar
                  </button>
                </div>
              ) : null}
            </article>
          ))}
        </div>
      )}
    </section>
  )
}
