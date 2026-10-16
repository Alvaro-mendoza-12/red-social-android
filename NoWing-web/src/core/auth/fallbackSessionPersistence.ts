export type PersistedFallbackSession<TUser> = {
  schemaVersion: 2
  tokenType: string
  expiresIn: string | number
  user: TUser
  savedAt: string
}

export function fallbackAccountMatches(user: { id: string; supabaseUserId?: string | null } | undefined, expected: string): boolean {
  const clean = expected.trim().toLowerCase()
  return Boolean(clean && user && [user.id, user.supabaseUserId].some(id => id?.trim().toLowerCase() === clean))
}

export function accessTokenExpiration(token: string): number | null {
  try {
    const encodedPayload = token.split('.')[1]
    if (!encodedPayload) return null
    const normalized = encodedPayload.replace(/-/g, '+').replace(/_/g, '/')
    const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, '=')
    const payload = JSON.parse(atob(padded)) as { exp?: unknown }
    const expiresAt = Number(payload.exp)
    return Number.isFinite(expiresAt) && expiresAt > 0 ? expiresAt * 1000 : null
  } catch {
    return null
  }
}

export function toPersistedFallbackSession<TUser>(session: {
  tokenType: string
  expiresIn: string | number
  user: TUser
  savedAt: string
}): PersistedFallbackSession<TUser> {
  return {
    schemaVersion: 2,
    tokenType: session.tokenType,
    expiresIn: session.expiresIn,
    user: session.user,
    savedAt: session.savedAt,
  }
}
