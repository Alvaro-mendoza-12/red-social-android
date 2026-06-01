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
  if (error instanceof Error && error.message.trim()) return error.message.trim()
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
