export type RecoverySessionPayload = {
  accessToken: string
  refreshToken: string
}

function valueOf(record: Record<string, unknown>, keys: string[]): string {
  for (const key of keys) {
    const value = record[key]
    if (typeof value === 'string' && value.trim()) {
      return value.trim()
    }
  }
  return ''
}

function payloadFromRecord(record: Record<string, unknown>): RecoverySessionPayload | null {
  const accessToken = valueOf(record, ['access_token', 'accessToken'])
  const refreshToken = valueOf(record, ['refresh_token', 'refreshToken'])
  if (!accessToken || !refreshToken) return null
  return { accessToken, refreshToken }
}

function payloadFromParams(params: URLSearchParams): RecoverySessionPayload | null {
  const accessToken = params.get('access_token')?.trim() ?? ''
  const refreshToken = params.get('refresh_token')?.trim() ?? ''
  if (!accessToken || !refreshToken) return null
  return { accessToken, refreshToken }
}

export function parseRecoverySessionPayload(rawInput: string): RecoverySessionPayload | null {
  const raw = rawInput.trim()
  if (!raw) return null

  if (raw.startsWith('{')) {
    try {
      const parsed = JSON.parse(raw) as Record<string, unknown>
      return payloadFromRecord(parsed)
    } catch {
      // Continue with other parsers.
    }
  }

  const hashIndex = raw.indexOf('#')
  if (hashIndex >= 0) {
    const payload = payloadFromParams(new URLSearchParams(raw.slice(hashIndex + 1)))
    if (payload) return payload
  }

  const queryIndex = raw.indexOf('?')
  if (queryIndex >= 0) {
    const payload = payloadFromParams(new URLSearchParams(raw.slice(queryIndex + 1)))
    if (payload) return payload
  }

  if (raw.includes('access_token=') || raw.includes('refresh_token=')) {
    const payload = payloadFromParams(new URLSearchParams(raw))
    if (payload) return payload
  }

  const linePairs = raw
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .reduce<Record<string, string>>((accumulator, line) => {
      const separator = line.includes(':') ? ':' : line.includes('=') ? '=' : ''
      if (!separator) return accumulator
      const [key, ...rest] = line.split(separator)
      accumulator[key.trim()] = rest.join(separator).trim()
      return accumulator
    }, {})

  return payloadFromRecord(linePairs)
}

export function maskSensitiveToken(value: string): string {
  const trimmed = value.trim()
  if (trimmed.length <= 18) return trimmed
  return `${trimmed.slice(0, 10)}…${trimmed.slice(-8)}`
}
