import { useEffect, useState } from 'react'
import { fallbackApiJson } from '../core/auth/fallbackAuth'
import { friendlyError } from '../core/utils'

type RequestState = { status: 'new' | 'open' | 'pending_incoming' | 'pending_outgoing' | 'rejected' | 'blocked' | 'unavailable' }
export function DirectMessageRequestNotice({ chatId, accountId, revision }: { chatId: string; accountId: string; revision: number }) {
  const [state, setState] = useState<RequestState | null>(null)
  const [error, setError] = useState(''), [busy, setBusy] = useState(false)
  useEffect(() => {
    let active = true
    setError('')
    void fallbackApiJson<RequestState>(`/v1/data/messages/chats/${encodeURIComponent(chatId)}/request`, { expectedAccountId: accountId, cache: 'no-store' })
      .then(value => { if (active) setState(value) }).catch(() => { if (active) setState(null) })
    return () => { active = false }
  }, [accountId, chatId, revision])
  async function decide(action: 'accept' | 'reject') {
    setBusy(true); setError('')
    try {
      const value = await fallbackApiJson<RequestState>(`/v1/data/messages/chats/${encodeURIComponent(chatId)}/request`, { method: 'POST', expectedAccountId: accountId, body: JSON.stringify({ action }) })
      setState(value)
      window.dispatchEvent(new Event('novvin:inbox-changed'))
    } catch (caught) { setError(friendlyError(caught, 'No se pudo guardar tu decisión.')) }
    finally { setBusy(false) }
  }
  if (!state || state.status === 'open') return null
  return <aside className="chat-request-notice" aria-label="Solicitud de mensaje">
    <p>{state.status === 'new' ? 'Puedes enviar un primer mensaje y una solicitud de amistad. Para continuar, la otra persona debe aceptar o responder.' : state.status === 'pending_outgoing' ? 'Solicitud enviada. Podrás continuar cuando la otra persona acepte o responda.' : state.status === 'pending_incoming' ? 'Esta persona quiere conversar contigo. Al responder o aceptar, autorizas la conversación y aceptas su solicitud de amistad.' : state.status === 'blocked' ? 'No puedes enviar mensajes a esta persona.' : state.status === 'rejected' ? 'Esta solicitud de conversación fue rechazada.' : 'Esta conversación no está disponible.'}</p>
    {state.status === 'pending_incoming' ? <div className="row-actions"><button className="primary-btn" disabled={busy} onClick={() => void decide('accept')}>Aceptar</button><button className="ghost-btn" disabled={busy} onClick={() => void decide('reject')}>Rechazar</button></div> : null}
    {error ? <p role="alert">{error}</p> : null}
  </aside>
}
