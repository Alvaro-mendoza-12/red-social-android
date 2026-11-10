import { isolatedBackendRequestCredentials } from '../publicBackendTransport'
import { getRuntimeBackendUrl, initializeRuntimeBackend } from '../runtimeBackend'
import { getStorageReadIdentity, invalidateStorageReadIdentity, onStorageReadInvalidated, requireStorageReadIdentity, updateStorageReadIdentity, type StorageReadIdentity } from '../media/storageReadSession'
import type { GenericRow } from '../utils'

export type GuestKind = 'videos' | 'posts' | 'moments' | 'profiles'
type GuestSession = { id: string; accessToken: string; expiresAt: number; backend: string }
let active: GuestSession | null = null
let pending: Promise<GuestSession> | undefined
let epoch = 0

function assertNoAccount() {
  const provider = getStorageReadIdentity().provider
  if (provider === 'fallback' || provider === 'supabase') throw new Error('GUEST_ACCOUNT_CHANGED')
}

async function request(path: string, token: string, signal?: AbortSignal, method = 'GET') {
  const base = getRuntimeBackendUrl()
  if (!base) throw new Error('GUEST_UNAVAILABLE')
  const controller = new AbortController()
  const abort = () => controller.abort()
  signal?.addEventListener('abort', abort, { once: true })
  if (signal?.aborted) abort()
  const timer = setTimeout(abort, 15_000)
  try {
    const response = await fetch(`${base}${path}`, {
      method, credentials: isolatedBackendRequestCredentials(`${base}${path}`), redirect: 'error', cache: 'no-store',
      headers: { Accept: 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      signal: controller.signal,
    })
    if (!response.ok) throw new Error(`GUEST_READ_${response.status}`)
    return await response.json() as Record<string, unknown>
  } finally {
    clearTimeout(timer)
    signal?.removeEventListener('abort', abort)
  }
}

export async function startGuestBrowsing(): Promise<void> {
  assertNoAccount()
  if (active && active.backend === getRuntimeBackendUrl() && active.expiresAt > Date.now() + 60_000) {
    updateStorageReadIdentity(active.id, 'guest')
    return
  }
  if (!pending) {
    const capturedEpoch = epoch, backend = getRuntimeBackendUrl()
    const operation = (async () => {
      // Direct entry can render before discovery restores or replaces the trusted origin.
      await initializeRuntimeBackend()
      assertNoAccount()
      if (epoch !== capturedEpoch) throw new Error('GUEST_ACCOUNT_CHANGED')
      const payload = await request('/v1/guest/session', '', undefined, 'POST')
      assertNoAccount()
      if (epoch !== capturedEpoch) throw new Error('GUEST_ACCOUNT_CHANGED')
      if (payload.role !== 'guest' || !/^guest_[0-9a-f-]{36}$/i.test(String(payload.id)) ||
          !/^[A-Za-z0-9._~-]{20,8192}$/.test(String(payload.accessToken)) || !(Number(payload.expiresIn) > 0)) throw new Error('GUEST_SESSION_INVALID')
      const session = { id: String(payload.id), accessToken: String(payload.accessToken), backend: getRuntimeBackendUrl() || backend,
        expiresAt: Date.now() + Math.min(Number(payload.expiresIn), 30 * 24 * 60 * 60) * 1000 }
      active = session
      updateStorageReadIdentity(session.id, 'guest')
      return session
    })()
    pending = operation
    void operation.finally(() => { if (pending === operation) pending = undefined }).catch(() => {})
  }
  await pending
}

export function guestStorageToken(identity: StorageReadIdentity): string {
  requireStorageReadIdentity(identity)
  if (identity.provider !== 'guest' || active?.id !== identity.accountId ||
      active.backend !== getRuntimeBackendUrl() || active.expiresAt <= Date.now()) throw new Error('MEDIA_SESSION_CHANGED')
  return active.accessToken
}

export function stopGuestBrowsing() {
  active = null
  epoch += 1
  pending = undefined
  invalidateStorageReadIdentity('guest')
}

onStorageReadInvalidated(() => {
  const identity = getStorageReadIdentity()
  // Signed discovery can switch the same service between its trusted public
  // endpoints. Re-register media there without ending the guest identity.
  if (identity.provider === 'guest' && active?.id === identity.accountId) {
    active.backend = getRuntimeBackendUrl()
    return
  }
  if (active || identity.provider === 'fallback' || identity.provider === 'supabase') {
    active = null
    epoch += 1
    pending = undefined
  }
})

export async function loadGuestContent(kind: GuestKind, id?: string, signal?: AbortSignal): Promise<GenericRow[]> {
  if (!['videos', 'posts', 'moments', 'profiles'].includes(kind) ||
      (id && !/^[0-9a-f-]{36}$/i.test(id)) || (kind === 'profiles' && !id)) throw new Error('GUEST_CONTENT_INVALID')
  for (let attempt = 0; attempt < 2; attempt += 1) {
    await startGuestBrowsing()
    if (signal?.aborted) throw new DOMException('Cancelled', 'AbortError')
    const identity = getStorageReadIdentity(), token = guestStorageToken(identity)
    try {
      const payload = await request(`/v1/guest/${kind}${id ? `/${id}` : ''}`, token, signal)
      requireStorageReadIdentity(identity)
      const rows = id ? [kind === 'profiles' ? payload.profile : payload.content] : payload.items
      return Array.isArray(rows) ? rows.filter(row => row && typeof row === 'object') as GenericRow[] : []
    } catch (error) {
      if (attempt === 0 && error instanceof Error && error.message === 'GUEST_READ_401' && identity === getStorageReadIdentity()) stopGuestBrowsing()
      else throw error
    }
  }
  throw new Error('GUEST_SESSION_INVALID')
}
