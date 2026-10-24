import { readBoundedStorageBlob, type BoundedStorageReadOptions } from './boundedStorageRead'
import { onStorageReadInvalidated, requireStorageReadIdentity } from './storageReadSession'

const MAX_BYTES = 32 * 1024 * 1024
const MAX_ENTRIES = 96
const MAX_AGE = 30 * 60 * 1000
const entries = new Map<string, { blob: Blob; expires: number; url?: string }>()
let bytes = 0

function remove(key: string) {
  const entry = entries.get(key)
  if (entry) { bytes -= entry.blob.size; if (entry.url) URL.revokeObjectURL(entry.url) }
  entries.delete(key)
}

onStorageReadInvalidated(() => { for (const key of entries.keys()) remove(key) })

export function cachedStorageImagePreview(url: string, identity: BoundedStorageReadOptions['identity'], maxBytes: number) {
  requireStorageReadIdentity(identity)
  const key = `${identity.generation}/${maxBytes}/${url}`
  const entry = entries.get(key)
  if (!entry || entry.expires <= Date.now()) return undefined
  if (!entry.url) entry.url = URL.createObjectURL(entry.blob)
  return { source: url, identity, url: entry.url, mime: entry.blob.type, size: entry.blob.size }
}

/** Session memory only: a chat remount must not download its images again. */
export async function readCachedStorageImage(options: BoundedStorageReadOptions, refresh = false): Promise<Blob> {
  requireStorageReadIdentity(options.identity)
  if (options.signal.aborted) throw new DOMException('Media read cancelled', 'AbortError')
  const key = `${options.identity.generation}/${options.maxBytes}/${options.url}`
  const cached = entries.get(key)
  if (cached && !refresh && cached.expires > Date.now()) {
    entries.delete(key); entries.set(key, cached)
    return cached.blob
  }
  remove(key)
  const blob = await readBoundedStorageBlob(options)
  requireStorageReadIdentity(options.identity)
  if (options.signal.aborted) throw new DOMException('Media read cancelled', 'AbortError')
  // Keep animated image bytes intact. Never retain documents or oversized reads.
  if (/^image\/(png|apng|jpeg|webp|gif|avif|bmp)$/.test(blob.type) && blob.size <= options.maxBytes && blob.size <= MAX_BYTES) {
    remove(key)
    while (entries.size && (entries.size >= MAX_ENTRIES || bytes + blob.size > MAX_BYTES)) remove(entries.keys().next().value!)
    entries.set(key, { blob, expires: Date.now() + MAX_AGE }); bytes += blob.size
  }
  return blob
}
