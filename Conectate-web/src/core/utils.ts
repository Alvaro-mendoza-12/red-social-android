import { isSupabaseServiceRestrictionError } from './supabase'

export type GenericRow = Record<string, unknown>

function looksLikeWebAccessBridgeIssue(message: string): boolean {
  return (
    message.includes('web-login-pairing') ||
    message.includes('pairing') ||
    message.includes('qr') ||
    message.includes('edge function')
  )
}

function looksLikeTechnicalBackendMessage(message: string): boolean {
  return (
    message.includes('42p') ||
    message.includes('pgrst') ||
    message.includes('relation') ||
    message.includes('schema cache') ||
    message.includes('could not find the table') ||
    message.includes('json object requested') ||
    message.includes('unexpected token') ||
    message.includes('<!doctype') ||
    message.includes('http request failed') ||
    message.includes('http 4') ||
    message.includes('http 5') ||
    message.includes('nullpointerexception') ||
    message.includes('illegalstateexception') ||
    message.includes('stack trace')
  )
}

export function textOf(row: GenericRow, keys: string[], fallback = ''): string {
  for (const key of keys) {
    const value = row[key]
    if (typeof value === 'string' && value.trim().length > 0) {
      return value.trim()
    }
  }
  return fallback
}

export function numberOf(row: GenericRow, keys: string[], fallback = 0): number {
  for (const key of keys) {
    const value = row[key]
    if (typeof value === 'number' && Number.isFinite(value)) return value
    if (typeof value === 'string') {
      const parsed = Number(value)
      if (Number.isFinite(parsed)) return parsed
    }
  }
  return fallback
}

export function stringArrayOf(row: GenericRow, keys: string[]): string[] {
  for (const key of keys) {
    const value = row[key]
    if (Array.isArray(value)) {
      return value
        .map((entry) => String(entry).trim())
        .filter((entry) => entry.length > 0)
    }
  }
  return []
}

export function formatDateTime(value: string): string {
  if (!value) return 'Sin fecha'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return 'Sin fecha'
  return parsed.toLocaleString()
}

export function friendlyError(error: unknown, fallback = 'Ocurrió un error inesperado'): string {
  const message = error instanceof Error ? error.message.trim() : String(error ?? '').trim()
  const normalized = message.toLowerCase()

  if (isSupabaseServiceRestrictionError(error)) {
    return 'El acceso está temporalmente no disponible. Intenta más tarde mientras se restablece el servicio.'
  }

  if (normalized.includes('invalid login credentials')) {
    return 'El correo o la contraseña no coinciden con una cuenta válida.'
  }

  if (normalized.includes('email not confirmed')) {
    return 'Tu correo todavía no fue confirmado. Revisa tu bandeja antes de continuar.'
  }

  if (normalized.includes('network request failed') || normalized.includes('failed to fetch')) {
    return 'No pudimos conectarnos en este momento. Revisa tu conexión e inténtalo otra vez.'
  }

  if (normalized.includes('too many requests') || normalized.includes('rate limit')) {
    return 'Hiciste demasiados intentos seguidos. Espera un momento antes de volver a intentarlo.'
  }

  if (looksLikeWebAccessBridgeIssue(normalized)) {
    return 'La conexión entre la app y la web no está disponible en este momento. Inténtalo más tarde.'
  }

  if (
    normalized.includes('function') ||
    normalized.includes('rpc') ||
    normalized.includes('storage') ||
    looksLikeTechnicalBackendMessage(normalized)
  ) {
    return 'El servicio no está disponible en este momento. Inténtalo nuevamente más tarde.'
  }

  if (message) return message
  return fallback
}

export function isMissingTableError(error: unknown): boolean {
  const message =
    error instanceof Error ? error.message.toLowerCase() : String(error ?? '').toLowerCase()
  return (
    message.includes('does not exist') ||
    message.includes('relation') ||
    message.includes('42p01') ||
    message.includes('could not find the table')
  )
}
