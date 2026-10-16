import type { User } from '@supabase/supabase-js'

// Access tokens belong to the session. A renewal should not restart every
// effect that watches the user. Keep all account/security/profile fields in
// the comparison; only the observational sign-in timestamp is irrelevant.
export function retainUnchangedAuthUser(previous: User | null, next: User | null): User | null {
  if (previous === next || !previous || !next) return next
  const snapshot = (user: User) => JSON.stringify({ ...user, last_sign_in_at: undefined })
  return snapshot(previous) === snapshot(next) ? previous : next
}
