export type StorageReadIdentity = Readonly<{
  accountId: string
  provider: 'fallback' | 'supabase' | ''
  generation: number
}>

let current: StorageReadIdentity = Object.freeze({ accountId: '', provider: '', generation: 0 })
const listeners = new Set<() => void>()
const invalidators = new Set<() => void>()

export function getStorageReadIdentity(): StorageReadIdentity { return current }

export function subscribeStorageReadIdentity(listener: () => void): () => void {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}

export function onStorageReadInvalidated(invalidate: () => void): () => void {
  invalidators.add(invalidate)
  return () => { invalidators.delete(invalidate) }
}

function replaceIdentity(accountId: string, provider: StorageReadIdentity['provider']) {
  current = Object.freeze({ accountId, provider, generation: current.generation + 1 })
  // Invalidate synchronously, before a React update or an asynchronous logout.
  for (const invalidate of [...invalidators]) { try { invalidate() } catch { /* Finish the other cleanups. */ } }
  for (const listener of [...listeners]) { try { listener() } catch { /* Finish the other notifications. */ } }
}

export function updateStorageReadIdentity(accountId: string | null | undefined, provider: StorageReadIdentity['provider']) {
  const candidate = String(accountId ?? '').trim()
  const valid = (provider === 'fallback' || provider === 'supabase') && candidate.length > 0 && candidate.length <= 256 && !/[\u0000-\u0020\u007f]/.test(candidate)
  const nextId = valid ? candidate : ''
  const nextProvider = valid ? provider : ''
  if (nextId === current.accountId && nextProvider === current.provider) return
  replaceIdentity(nextId, nextProvider)
}

export function invalidateStorageReadIdentity(provider?: StorageReadIdentity['provider']) {
  if (provider && current.provider !== provider) return
  replaceIdentity('', '')
}

export function invalidateStorageReadBackend() { replaceIdentity(current.accountId, current.provider) }

export function requireStorageReadIdentity(expected: StorageReadIdentity) {
  if (!expected.accountId || expected !== current) throw new Error('MEDIA_SESSION_CHANGED')
}
