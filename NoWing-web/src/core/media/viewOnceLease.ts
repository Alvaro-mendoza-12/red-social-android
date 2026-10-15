import { isTrustedStorageReadUrl, STORAGE_PREVIEW_MAX_BYTES, STORAGE_SHORT_MEDIA_MAX_BYTES } from './boundedStorageRead'
import type { StorageReadIdentity } from './storageReadSession'

export type ViewOnceKind = 'image' | 'video'
export type ViewOnceLease = Readonly<{ url: string; backend: string; kind: ViewOnceKind; bytes: number; identity: StorageReadIdentity; expiresAt: number }>

export function viewOnceLimit(kind: ViewOnceKind): number { return kind === 'image' ? STORAGE_PREVIEW_MAX_BYTES : STORAGE_SHORT_MEDIA_MAX_BYTES }

/** Only a current, user-bound storage lease can enter the viewer. No URL fallback. */
export function validateViewOnceLease(raw: unknown, backend: string, identity: StorageReadIdentity, kind: ViewOnceKind, now = Date.now()): ViewOnceLease {
  if (Array.isArray(raw) && raw.length !== 1) throw new Error('VIEW_ONCE_UNAVAILABLE')
  const row = (Array.isArray(raw) ? raw[0] : raw) as Record<string, unknown> | null
  if (row?.already_consumed === true) throw new Error('VIEW_ONCE_CONSUMED')
  if (!row || typeof row !== 'object' || row.allowed !== true || row.already_consumed !== false) throw new Error('VIEW_ONCE_UNAVAILABLE')
  const url = typeof row.media_url === 'string' ? row.media_url : ''
  if (!isTrustedStorageReadUrl(url, backend)) throw new Error('VIEW_ONCE_UNTRUSTED_LEASE')
  const parsed = new URL(url)
  if (parsed.hash || [...parsed.searchParams.keys()].length !== 1 || !/^[A-Za-z0-9_-]{43}$/.test(parsed.searchParams.get('lease') ?? '')) throw new Error('VIEW_ONCE_INVALID_LEASE')
  const expiresAt = typeof row.lease_expires_at === 'string' ? Date.parse(row.lease_expires_at) : NaN
  if (!Number.isFinite(expiresAt) || expiresAt <= now || expiresAt > now + 6 * 60_000) throw new Error('VIEW_ONCE_EXPIRED')
  const bytes = typeof row.media_size_bytes === 'number' ? row.media_size_bytes : NaN
  if (!Number.isSafeInteger(bytes) || bytes < 1 || bytes > viewOnceLimit(kind) || row.content_type !== `view_once_${kind}`) throw new Error('VIEW_ONCE_UNSUPPORTED_MEDIA')
  return Object.freeze({ url: parsed.href, backend, kind, bytes, identity, expiresAt })
}
