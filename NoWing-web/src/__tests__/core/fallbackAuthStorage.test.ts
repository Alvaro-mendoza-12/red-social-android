import {
  accessTokenExpiration,
  toPersistedFallbackSession,
} from '../../core/auth/fallbackSessionPersistence'

function unsignedJwt(expiresAtEpochSeconds: number): string {
  const encode = (value: object) => window.btoa(JSON.stringify(value))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')
  return `${encode({ alg: 'none' })}.${encode({ exp: expiresAtEpochSeconds })}.signature`
}

describe('fallback web session storage', () => {
  test('creates persistent metadata without copying the short-lived bearer', () => {
    const accessToken = unsignedJwt(Math.floor(Date.now() / 1000) + 10 * 60)
    const persisted = toPersistedFallbackSession({
      tokenType: 'Bearer',
      expiresIn: '15m',
      user: { id: 'local-user-id', email: 'user@example.test', displayName: 'User' },
      savedAt: new Date().toISOString(),
    })

    const serialized = JSON.stringify(persisted)
    expect(serialized).not.toContain(accessToken)
    expect(serialized).not.toContain('accessToken')
    expect(persisted).toMatchObject({
      schemaVersion: 2,
      user: { id: 'local-user-id', email: 'user@example.test' },
    })
  })

  test('reads the JWT expiry without persisting or trusting the token', () => {
    const expiresAt = Math.floor(Date.now() / 1000) + 30
    expect(accessTokenExpiration(unsignedJwt(expiresAt))).toBe(expiresAt * 1000)
    expect(accessTokenExpiration('not-a-jwt')).toBeNull()
  })
})
