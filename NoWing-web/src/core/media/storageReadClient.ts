import { ensureFallbackAccessToken, fallbackSessionToUser, readFallbackSession } from '../auth/fallbackAuth'
import { getRuntimeBackendUrl, subscribeRuntimeBackend } from '../runtimeBackend'
import { supabase } from '../supabase'
import { getStorageReadIdentity, invalidateStorageReadBackend, requireStorageReadIdentity, type StorageReadIdentity } from './storageReadSession'
import { isTrustedStorageReadUrl } from './boundedStorageRead'

// Trusted discovery already verifies signed candidates before publishing this event.
subscribeRuntimeBackend(() => invalidateStorageReadBackend())

export function trustedStorageBackend(url: string): string {
  const runtime = getRuntimeBackendUrl()
  if (runtime && isTrustedStorageReadUrl(url, runtime)) return runtime
  const configuredSupabase = String(import.meta.env.VITE_SUPABASE_URL ?? '').trim()
  if (supabase && configuredSupabase && isTrustedStorageReadUrl(url, configuredSupabase)) return configuredSupabase
  return ''
}

export async function prepareStorageReadAccessToken(identity: StorageReadIdentity): Promise<string> {
  requireStorageReadIdentity(identity)
  if (identity.provider === 'fallback') {
    const token = await ensureFallbackAccessToken()
    requireStorageReadIdentity(identity)
    if (fallbackSessionToUser(readFallbackSession())?.id !== identity.accountId) throw new Error('MEDIA_SESSION_CHANGED')
    return token
  }
  if (!supabase || identity.provider !== 'supabase') throw new Error('MEDIA_SESSION_REQUIRED')
  const { data, error } = await supabase.auth.getSession()
  requireStorageReadIdentity(identity)
  if (error || data.session?.user.id !== identity.accountId) throw new Error('MEDIA_SESSION_CHANGED')
  return data.session.access_token
}

export function captureStorageReadIdentity() { return getStorageReadIdentity() }
