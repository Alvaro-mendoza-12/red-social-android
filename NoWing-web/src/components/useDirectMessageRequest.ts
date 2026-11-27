import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
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

export function directMessageWriteRestriction(status: RequestStatus | null) {
  return status ? writeRestrictions[status] : undefined
}

export function useDirectMessageRequest(chatId: string, accountId: string) {
  const identity = accountId && chatId ? JSON.stringify([accountId, chatId]) : ''
  const scope = useMemo(() => ({ identity }), [identity])
  const currentScope = useRef<typeof scope | null>(null)
  const sequence = useRef(0)
  const [snapshot, setSnapshot] = useState<{ scope: typeof scope; status: RequestStatus | null }>()
  const [decision, setDecision] = useState<{ scope: typeof scope; busy: boolean; error?: string }>()

  useLayoutEffect(() => {
    currentScope.current = scope
    return () => { currentScope.current = null }
  }, [scope])
  const path = `/v1/data/messages/chats/${encodeURIComponent(chatId)}/request`

  const refresh = useCallback(async () => {
    if (!identity || currentScope.current !== scope) return
    const ticket = ++sequence.current
    try {
      const result = await fallbackApiJson<{ status: RequestStatus }>(path, {
        expectedAccountId: accountId, cache: 'no-store',
      })
      if (ticket === sequence.current && currentScope.current === scope) {
        setSnapshot({ scope, status: result.status ?? null })
      }
    } catch {
      // Retain the last verified restriction when the connection drops.
      if (ticket === sequence.current && currentScope.current === scope) {
        setSnapshot(current => current?.scope === scope ? current : { scope, status: null })
      }
    }
  }, [accountId, identity, path, scope])

  useEffect(() => {
    void Promise.resolve().then(refresh)
    return () => { sequence.current += 1 }
  }, [refresh])

  const status = snapshot?.scope === scope ? snapshot.status ?? null : null
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
    if (!identity || currentScope.current !== scope) return
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
    setSnapshot({ scope, status })
  }, [identity, scope])

  async function decide(action: 'accept' | 'reject') {
    if (!identity || currentScope.current !== scope || (decision?.scope === scope && decision.busy)) return
    setDecision({ scope, busy: true })
    sequence.current += 1
    try {
      const result = await fallbackApiJson<{ status: RequestStatus }>(path, {
        method: 'POST', expectedAccountId: accountId, body: JSON.stringify({ action }),
      })
      if (currentScope.current !== scope) return
      sequence.current += 1
      setSnapshot({ scope, status: result.status ?? null })
      setDecision({ scope, busy: false })
      window.dispatchEvent(new Event('novvin:inbox-changed'))
    } catch (caught) {
      if (currentScope.current === scope) {
        setDecision({ scope, busy: false, error: friendlyError(caught, 'No se pudo guardar tu decisión.') })
      }
    }
  }

  return { status, checking: Boolean(identity && snapshot?.scope !== scope), refresh, decide, recordSendRejection,
    busy: decision?.scope === scope && decision.busy,
    error: decision?.scope === scope ? decision.error : undefined }
}
