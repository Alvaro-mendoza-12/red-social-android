import { isFallbackAuthConfigured } from './auth/fallbackAuth'
import { isSupabaseConfigured } from './supabase'

/** True cuando la web debe usar el servidor Ubuntu como backend principal. */
export const isUbuntuPrimary = isFallbackAuthConfigured && !isSupabaseConfigured

/** True si la sesión activa o la config apuntan a Ubuntu. */
export function shouldUseUbuntuBackend(isFallbackSession: boolean): boolean {
  return isFallbackSession || isUbuntuPrimary
}
