import { isSupabaseServiceRestrictionError } from './supabase'

export type GenericRow = Record<string, unknown>

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
