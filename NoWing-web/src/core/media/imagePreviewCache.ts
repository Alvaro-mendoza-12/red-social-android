import { readBoundedStorageBlob, type BoundedStorageReadOptions } from './boundedStorageRead'
import { onStorageReadInvalidated, requireStorageReadIdentity } from './storageReadSession'

const MAX_BYTES = 32 * 1024 * 1024
const MAX_ENTRIES = 96
const MAX_AGE = 30 * 60 * 1000
const entries = new Map<string, { blob: Blob; expires: number; url?: string }>()
type PendingRead = { controller: AbortController; promise: Promise<Blob>; consumers: Set<symbol> }
const pendingReads = new Map<string, PendingRead>()
let bytes = 0

function remove(key: string) {
  const entry = entries.get(key)
  if (entry) { bytes -= entry.blob.size; if (entry.url) URL.revokeObjectURL(entry.url) }
  entries.delete(key)
}

onStorageReadInvalidated(() => {
  for (const pending of pendingReads.values()) pending.controller.abort()
  pendingReads.clear()
  for (const key of entries.keys()) remove(key)
})

export function cachedStorageImagePreview(url: string, identity: BoundedStorageReadOptions['identity'], maxBytes: number, avatarVideo = false) {
  requireStorageReadIdentity(identity)
  const key = `${avatarVideo ? 'avatar-video/' : ''}${identity.generation}/${maxBytes}/${url}`
  const entry = entries.get(key)
  if (!entry || entry.expires <= Date.now()) return undefined
  if (!entry.url) entry.url = URL.createObjectURL(entry.blob)
  return { source: url, identity, url: entry.url, mime: entry.blob.type, size: entry.blob.size }
}

/** Session memory only: a chat remount must not download its images again. */
export async function readCachedStorageImage(options: BoundedStorageReadOptions, refresh = false): Promise<Blob> {
  return readCachedPreview(options, refresh, false)
}

export async function readCachedAvatarVideo(options: BoundedStorageReadOptions): Promise<Blob> {
  return readCachedPreview(options, false, true)
}

async function readCachedPreview(options: BoundedStorageReadOptions, refresh: boolean, avatarVideo: boolean): Promise<Blob> {
  requireStorageReadIdentity(options.identity)
  if (options.signal.aborted) throw new DOMException('Media read cancelled', 'AbortError')
  const key = `${avatarVideo ? 'avatar-video/' : ''}${options.identity.generation}/${options.maxBytes}/${options.url}`
  const cached = entries.get(key)
  if (cached && !refresh && cached.expires > Date.now()) {
    entries.delete(key); entries.set(key, cached)
    return cached.blob
  }
  let pending = pendingReads.get(key)
  if (!pending || pending.controller.signal.aborted) {
    const controller = new AbortController()
    const promise = readBoundedStorageBlob({ ...options, signal: controller.signal }).then(blob => {
      requireStorageReadIdentity(options.identity)
      if (controller.signal.aborted) throw new DOMException('Media read cancelled', 'AbortError')
      const supported = avatarVideo ? /^video\/(mp4|webm|quicktime|3gpp)$/.test(blob.type) : /^image\/(png|apng|jpeg|webp|gif|avif|bmp)$/.test(blob.type)
      if (supported && blob.size <= options.maxBytes && blob.size <= MAX_BYTES) {
        remove(key)
        while (entries.size && (entries.size >= MAX_ENTRIES || bytes + blob.size > MAX_BYTES)) remove(entries.keys().next().value!)
        entries.set(key, { blob, expires: Date.now() + MAX_AGE }); bytes += blob.size
      } else remove(key)
      return blob
    })
    pending = { controller, promise, consumers: new Set() }
    const current = pending
    pendingReads.set(key, current)
    void promise.finally(() => { if (pendingReads.get(key) === current) pendingReads.delete(key) }).catch(() => {})
  }
  // The same picture can be visible in a bubble, reply and album at once.
  // Share one authorized transfer; unmounting one consumer must not abort the others.
  const read = pending
  const consumer = Symbol()
  read.consumers.add(consumer)
  return new Promise<Blob>((resolve, reject) => {
    let settled = false
    const finish = () => {
      settled = true
      options.signal.removeEventListener('abort', abort)
      read.consumers.delete(consumer)
    }
    const abort = () => {
      if (settled) return
      finish()
      if (!read.consumers.size) read.controller.abort()
      reject(new DOMException('Media read cancelled', 'AbortError'))
    }
    options.signal.addEventListener('abort', abort, { once: true })
    read.promise.then(blob => {
      if (settled) return
      finish()
      try { requireStorageReadIdentity(options.identity); resolve(blob) } catch (error) { reject(error) }
    }, error => { if (!settled) { finish(); reject(error) } })
    if (options.signal.aborted) abort()
  })
}
