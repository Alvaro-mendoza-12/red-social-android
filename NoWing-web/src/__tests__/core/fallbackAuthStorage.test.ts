import {
  accessTokenExpiration,
  fallbackAccountMatches,
  sameFallbackAccount,
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
  test('same-account metadata refresh can retain the bearer without mixing canonical identities', () => {
    const current = {id:'local-owner',supabaseUserId:'canonical-owner'}
    expect(sameFallbackAccount(current,{...current})).toBe(true)
    expect(sameFallbackAccount(current,{id:' LOCAL-OWNER ',supabaseUserId:'CANONICAL-OWNER'})).toBe(true)
    expect(sameFallbackAccount(current,{id:'another-owner',supabaseUserId:'canonical-owner'})).toBe(false)
    expect(sameFallbackAccount(current,{id:'local-owner',supabaseUserId:'another-profile'})).toBe(false)
    expect(sameFallbackAccount(current,null)).toBe(false)
    expect(sameFallbackAccount(null,current)).toBe(false)
    expect(sameFallbackAccount({id:'owner'},{id:'owner',supabaseUserId:null})).toBe(true)
  })
  test('a migrated account can use its canonical profile ID while another account is rejected', () => {
    const user={id:'local-id',supabaseUserId:'profile-id'}
    expect(fallbackAccountMatches(user,'profile-id')).toBe(true)
    expect(fallbackAccountMatches(user,'LOCAL-ID')).toBe(true)
    expect(fallbackAccountMatches(user,'other-account')).toBe(false)
    expect(fallbackAccountMatches(undefined,'profile-id')).toBe(false)
    expect(fallbackAccountMatches(user,'')).toBe(false)
  })
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
