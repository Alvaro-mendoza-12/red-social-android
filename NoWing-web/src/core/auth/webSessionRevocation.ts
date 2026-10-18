/** Token claims are used only to route a server event to the current device. */
export function webSessionWasRevoked(payload: Record<string, unknown>, accessToken: string): boolean {
  if (payload.event !== 'web_sessions_revoked' || !Array.isArray(payload.sessionIds)) return false
  try {
    const encoded = accessToken.split('.')[1]?.replace(/-/g, '+').replace(/_/g, '/') || ''
    const claims = JSON.parse(atob(encoded.padEnd(Math.ceil(encoded.length / 4) * 4, '='))) as { sid?: unknown }
    return typeof claims.sid === 'string' && claims.sid.length > 0 && payload.sessionIds.includes(claims.sid)
  } catch { return false }
}
