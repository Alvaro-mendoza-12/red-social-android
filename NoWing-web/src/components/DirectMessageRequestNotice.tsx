import type { useDirectMessageRequest } from './useDirectMessageRequest'

const notices = {
  new: 'Puedes enviar un solo mensaje. Espera a que esta persona acepte o responda para continuar.',
  pending_incoming: 'Acepta para conversar. Si respondes, también aceptarás la solicitud.',
}

export function DirectMessageRequestNotice({ request }: { request: ReturnType<typeof useDirectMessageRequest> }) {
  const { status, busy, error, decide } = request
  if (status !== 'new' && status !== 'pending_incoming') return null
  const text = notices[status]
  return <aside className="chat-request-notice" aria-label="Solicitud de mensaje">
    <p>{text}</p>
    {status === 'pending_incoming' ? <div className="row-actions">
      <button type="button" className="primary-btn" disabled={busy} onClick={() => void decide('accept')}>Aceptar</button>
      <button type="button" className="ghost-btn" disabled={busy} onClick={() => void decide('reject')}>Rechazar</button>
    </div> : null}
    {error ? <p role="alert">{error}</p> : null}
  </aside>
}
