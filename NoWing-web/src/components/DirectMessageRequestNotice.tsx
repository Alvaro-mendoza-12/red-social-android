import { useCallback, useEffect, useRef, useState } from 'react'
import { fallbackApiJson } from '../core/auth/fallbackAuth'
import { apiErrorCode, friendlyError } from '../core/utils'

type RequestStatus = 'new' | 'open' | 'pending_incoming' | 'pending_outgoing' | 'rejected' | 'blocked' | 'unavailable'

const waitingForRequest = 'Podrás escribir de nuevo cuando esta persona acepte o responda.'
const writeRestrictions: Partial<Record<RequestStatus, string>> = {
  pending_outgoing: waitingForRequest,
  rejected: 'Esta persona rechazó la solicitud de mensaje.',
  blocked: 'No puedes enviar mensajes a esta persona.',
  unavailable: 'Esta conversación no está disponible.',
}
const notices = {
  new: 'Puedes enviar un solo mensaje. Espera a que esta persona acepte o responda para continuar.',
  pending_incoming: 'Acepta para conversar. Si respondes, también aceptarás la solicitud.',
}

export function directMessageWriteRestriction(status: RequestStatus | null) {
  return status ? writeRestrictions[status] : undefined
}

export function useDirectMessageRequest(chatId: string, accountId: string) {
  const identity = accountId && chatId ? JSON.stringify([accountId, chatId]) : ''
  const identityRef = useRef(identity)
  identityRef.current = identity
  const sequence = useRef(0)
  const [snapshot, setSnapshot] = useState<{ identity: string; status: RequestStatus | null }>()
  const [decision, setDecision] = useState<{ identity: string; busy: boolean; error?: string }>()
  const path = `/v1/data/messages/chats/${encodeURIComponent(chatId)}/request`

  const refresh = useCallback(async () => {
    if (!identity) return
    const ticket = ++sequence.current
    try {
      const result = await fallbackApiJson<{ status: RequestStatus }>(path, {
        expectedAccountId: accountId, cache: 'no-store',
      })
      if (ticket === sequence.current && identityRef.current === identity) {
        setSnapshot({ identity, status: result.status ?? null })
      }
    } catch {
      // Retain the last verified restriction when the connection drops.
      if (ticket === sequence.current && identityRef.current === identity) {
        setSnapshot(current => current?.identity === identity ? current : { identity, status: null })
      }
    }
  }, [accountId, identity, path])

  useEffect(() => {
    void refresh()
    return () => { sequence.current += 1 }
  }, [refresh])

  const status = snapshot?.identity === identity ? snapshot.status ?? null : null
  useEffect(() => {
    if (!identity) return
    const refreshVisible = () => { if (document.visibilityState !== 'hidden') void refresh() }
    window.addEventListener('focus', refreshVisible)
    window.addEventListener('novvin:inbox-changed', refreshVisible)
    const timer = status === 'pending_outgoing' ? window.setInterval(refreshVisible, 15000) : undefined
    return () => {
      window.removeEventListener('focus', refreshVisible)
      window.removeEventListener('novvin:inbox-changed', refreshVisible)
      window.clearInterval(timer)
    }
  }, [identity, refresh, status])

  const recordSendRejection = useCallback((error: unknown) => {
    if (!identity || identityRef.current !== identity) return
    const status = ({
      MESSAGE_REQUEST_PENDING: 'pending_outgoing',
      FRIEND_REQUEST_PENDING: 'pending_outgoing',
      MESSAGE_REQUEST_REJECTED: 'rejected',
      FRIEND_REQUEST_REJECTED: 'rejected',
      CHAT_BLOCKED_BY_RECIPIENT: 'blocked',
      CHAT_PARTICIPANT_REQUIRED: 'unavailable',
    } as Partial<Record<string, RequestStatus>>)[apiErrorCode(error)]
    if (!status) return
    sequence.current += 1
    setSnapshot({ identity, status })
  }, [identity])

  async function decide(action: 'accept' | 'reject') {
    if (!identity || decision?.identity === identity && decision.busy) return
    setDecision({ identity, busy: true })
    sequence.current += 1
    try {
      const result = await fallbackApiJson<{ status: RequestStatus }>(path, {
        method: 'POST', expectedAccountId: accountId, body: JSON.stringify({ action }),
      })
      if (identityRef.current !== identity) return
      sequence.current += 1
      setSnapshot({ identity, status: result.status ?? null })
      setDecision({ identity, busy: false })
      window.dispatchEvent(new Event('novvin:inbox-changed'))
    } catch (caught) {
      if (identityRef.current === identity) {
        setDecision({ identity, busy: false, error: friendlyError(caught, 'No se pudo guardar tu decisión.') })
      }
    }
  }

  return { status, checking: Boolean(identity && snapshot?.identity !== identity), refresh, decide, recordSendRejection,
    busy: decision?.identity === identity && decision.busy,
    error: decision?.identity === identity ? decision.error : undefined }
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
