export function MessageTimestamp({ createdAt, mine, status = 'sent', edited = false, readTitle, onRetry }: {
  createdAt: string; mine: boolean; status?: string; edited?: boolean; readTitle?: string; onRetry?: () => void
}) {
  const label = status === 'pending' ? 'Pendiente' : status === 'failed' ? 'Error al enviar' : status === 'read' ? 'Leído' : status === 'delivered' ? 'Entregado' : 'Enviado'
  return <small className="message-meta">
    <time dateTime={createdAt}>{Number.isFinite(Date.parse(createdAt)) ? new Date(createdAt).toLocaleTimeString('es', { hour: '2-digit', minute: '2-digit' }) : ''}</time>
    {edited ? ' · editado' : null}
    {mine ? <span className={`message-status ${status}`} aria-label={label} title={readTitle}>
      {status === 'pending' ? '◷' : status === 'failed' && onRetry ? <button type="button" className="mini-action-btn" onClick={onRetry}>Reintentar</button> : status === 'failed' ? '!' : status === 'sent' ? '✓' : '✓✓'}
    </span> : null}
  </small>
}
