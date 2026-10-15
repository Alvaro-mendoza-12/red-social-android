import { getRuntimeBackendUrl } from '../../core/runtimeBackend'

export type CallLeaveAction = 'end' | 'cancel' | 'decline' | 'busy' | 'missed' | 'collision'
export type CallPermission = { callId: string; state: 'ringing' | 'active'; expiresAt: string }

export class CallControlError extends Error {
  readonly code: string
  readonly status: number

  constructor(code: string, status = 0) {
    super(callControlMessage(code))
    this.name = 'CallControlError'
    this.code = code
    this.status = status
  }
}

function callControlMessage(code: string): string {
  if (code === 'CALL_ACCOUNT_BUSY') return 'Ya hay otra llamada activa en tu cuenta.'
  if (code === 'CALL_PARTICIPANT_LIMIT_REACHED') return 'La llamada ya está llena.'
  if (code === 'CALL_REQUEST_INVALID') return 'Revisa la conversación y los participantes de la llamada.'
  if (code === 'AUTH_REQUIRED') return 'Inicia sesión para usar llamadas.'
  if (code === 'CALL_ACCESS_UNAVAILABLE') return 'No se pudo confirmar la llamada. Intenta nuevamente.'
  return 'Esta llamada ya no está disponible.'
}

/** Uses the same authenticated runtime API as signaling, without reading stored credentials. */
export async function postCallData<T>(path: string, body: unknown, accessToken: string, keepalive = false): Promise<T> {
  const token = accessToken.trim()
  if (!token) throw new CallControlError('AUTH_REQUIRED', 401)
  const base = getRuntimeBackendUrl().trim().replace(/\/+$/, '')
  if (!base) throw new CallControlError('CALL_ACCESS_UNAVAILABLE')
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 5000)
  try {
    const response = await fetch(`${base}${path}`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
        Accept: 'application/json',
        'ngrok-skip-browser-warning': 'true',
      },
      body: JSON.stringify(body),
      signal: controller.signal,
      keepalive,
    })
    const payload = await response.json().catch(() => ({})) as Record<string, unknown>
    if (!response.ok) throw new CallControlError(String(payload.error || 'CALL_ACCESS_UNAVAILABLE'), response.status)
    return payload as T
  } catch (error) {
    if (error instanceof CallControlError) throw error
    throw new CallControlError('CALL_ACCESS_UNAVAILABLE')
  } finally {
    clearTimeout(timeout)
  }
}

export class CallControlClient {
  private readonly accessToken: string

  constructor(accessToken: string) {
    this.accessToken = accessToken
  }

  prepare(callId: string, contextId: string, inviteeIds: string[]): Promise<CallPermission> {
    return postCallData('/v1/data/calls/prepare', { callId, contextType: 'dm', contextId, inviteeIds }, this.accessToken)
  }

  accept(callId: string): Promise<CallPermission> {
    return postCallData(`/v1/data/calls/${encodeURIComponent(callId)}/accept`, {}, this.accessToken)
  }

  leave(callId: string, action: CallLeaveAction): Promise<{ left: boolean }> {
    return postCallData(`/v1/data/calls/${encodeURIComponent(callId)}/leave`, { action }, this.accessToken, true)
  }

  async directContext(participantId: string): Promise<string> {
    const payload = await postCallData<{ chat?: { id?: string } }>('/v1/data/messages/chats', { participantId }, this.accessToken)
    const contextId = String(payload.chat?.id ?? '').trim()
    if (!contextId) throw new CallControlError('CALL_REQUEST_INVALID', 400)
    return contextId
  }
}
