import type { User } from '@supabase/supabase-js'
import { retainUnchangedAuthUser } from '../../core/auth/stableAuthUser'
const account = { id: 'account-a', email: 'qa@invalid', role: 'authenticated', user_metadata: { display_name: 'QA' }, app_metadata: { provider: 'local-fallback' }, last_sign_in_at: '2026-10-15T00:00:00Z' } as unknown as User

test('retains the user reference for token-only and sign-in timestamp renewal', () => {
  expect(retainUnchangedAuthUser(account, { ...account, user_metadata: { ...account.user_metadata }, last_sign_in_at: '2026-10-16T00:00:00Z' })).toBe(account)
})
test('propagates logout and account switching', () => {
  expect(retainUnchangedAuthUser(account, null)).toBeNull()
  expect(retainUnchangedAuthUser(null, account)).toBe(account)
  const next = { ...account, id: 'account-b' }
  expect(retainUnchangedAuthUser(account, next)).toBe(next)
})
test('propagates profile and security metadata changes for the same account', () => {
  for (const next of [
    { ...account, email: 'changed@invalid' },
    { ...account, role: 'changed' },
    { ...account, user_metadata: { ...account.user_metadata, password_change_required: true } },
    { ...account, user_metadata: { ...account.user_metadata, avatar_url: '/new-photo' } },
    { ...account, app_metadata: { ...account.app_metadata, provider: 'supabase' } },
  ]) expect(retainUnchangedAuthUser(account, next)).toBe(next)
})
