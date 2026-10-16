import { fallbackApiJson, fallbackSessionToUser, readFallbackSession } from './auth/fallbackAuth'
import type { GenericRow } from './utils'

type Scope = 'chat' | 'group' | 'community'
type Payload = { id?: string; content: string; contentType?: string; mediaUrl?: string | null; [key: string]: unknown }
type Pending = { id: string; owner: string; scope: Scope; room: string; payload: Payload; createdAt: string; senderId: string; state: 'pending' | 'failed'; attempts: number; retryAt: number }
type Attachment = { id: string; owner: string; bucket: string; blob: Blob; name: string; path: string; createdAt: number; uploadedUrl?: string }
type Cached = { key: string; owner: string; value: Record<string, unknown> }
const pendingPrefix = 'novvin-pending-file:'
let opening: Promise<IDBDatabase> | undefined
let flushing = false
const urls = new Map<string, string>()

export function outboxOwner() { return readFallbackSession()?.user.id || '' }
function requireOwner(owner: string) { if (!owner || owner !== outboxOwner()) throw new Error('OUTBOX_ACCOUNT_CHANGED') }
function database() {
  if (!opening) opening = new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open('novvin-web-messaging-v1', 1)
    request.onupgradeneeded = () => {
      for (const name of ['messages', 'files', 'conversations']) request.result.createObjectStore(name, { keyPath: name === 'conversations' ? 'key' : 'id' })
    }
    request.onsuccess = () => { request.result.onversionchange = () => { request.result.close(); opening = undefined }; resolve(request.result) }
    request.onerror = () => { opening = undefined; reject(new Error('No se pudo guardar el mensaje en este navegador.')) }
  })
  return opening
}
async function operation<T>(store: string, write: boolean, fn: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const db = await database()
  return new Promise<T>((resolve, reject) => {
    const transaction = db.transaction(store, write ? 'readwrite' : 'readonly')
    const request = fn(transaction.objectStore(store)); let value: T
    request.onsuccess = () => { value = request.result }
    transaction.oncomplete = () => resolve(value)
    transaction.onerror = transaction.onabort = () => reject(new Error('No se pudo guardar el mensaje en este navegador.'))
  })
}
const all = <T,>(store: string) => operation<T[]>(store, false, s => s.getAll())
const put = (store: string, value: object) => operation(store, true, s => s.put(value))
const remove = (store: string, id: string) => operation(store, true, s => s.delete(id))
function changed(owner: string) { window.dispatchEvent(new CustomEvent('novvin:outbox-changed', { detail: { owner } })) }
export function isTransientOutboxError(error: unknown) {
  const status = Number((error as { status?: number })?.status)
  if (status) return status === 408 || status === 429 || status >= 500
  return /conectar|timeout|network|fetch|offline|abort/i.test(String((error as Error)?.message || error))
}
async function attachmentUrl(owner: string, id: string) {
  const file = await operation<Attachment | undefined>('files', false, s => s.get(id))
  requireOwner(owner)
  if (!file || file.owner !== owner) throw new Error('No se encontró el archivo pendiente.')
  const key = `${owner}/${id}`
  if (!urls.has(key)) urls.set(key, URL.createObjectURL(file.blob))
  return urls.get(key)!
}
async function optimistic(item: Pending): Promise<GenericRow> {
  const media = String(item.payload.mediaUrl || '')
  return { ...item.payload, id: item.id, content_type: item.payload.contentType || 'text',
    media_url: media.startsWith(pendingPrefix) ? await attachmentUrl(item.owner, media.slice(pendingPrefix.length)) : media,
    sender_id: item.senderId, created_at: item.createdAt, status: item.state,
    ...(item.scope === 'chat' ? { chat_id: item.room } : { [`${item.scope}_id`]: item.room }) }
}
export async function stageOfflineChatAttachment(bucket: string, file: File, path?: string) {
  const owner = outboxOwner(); requireOwner(owner)
  const id = crypto.randomUUID()
  await put('files', { id, owner, bucket, blob: file, name: file.name, path: path || `${owner}/${id}/${file.name}`, createdAt: Date.now() } satisfies Attachment)
  requireOwner(owner)
  return { id, bucket, path: path || '', publicUrl: pendingPrefix + id, url: pendingPrefix + id, contentType: file.type, size: file.size, ownerId: owner, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() }
}
export async function pendingConversationMessages(scope: Scope, room: string, owner = outboxOwner()) {
  requireOwner(owner)
  const items = (await all<Pending>('messages')).filter(item => item.owner === owner && item.scope === scope && item.room === room)
  return Promise.all(items.sort((a, b) => a.createdAt.localeCompare(b.createdAt)).map(optimistic))
}
export async function retryPendingWebMessage(id: string) {
  const owner = outboxOwner(); requireOwner(owner)
  const item = await operation<Pending | undefined>('messages', false, s => s.get(id))
  requireOwner(owner)
  if (!item || item.owner !== owner) return
  item.state = 'pending'; item.retryAt = 0
  await put('messages', item); changed(owner)
  void flushWebOutbox().catch(() => {})
}
export async function cancelPendingWebMessage(scope: Scope, room: string, id: string): Promise<GenericRow | null> {
  const owner = outboxOwner(); requireOwner(owner)
  const cancel = async () => {
    const item = await operation<Pending | undefined>('messages', false, s => s.get(id))
    requireOwner(owner)
    if (!item || item.owner !== owner || item.scope !== scope || item.room !== room) return null
    // Without Web Locks an outstanding send must finish before deciding if it is local.
    if (!navigator.locks && flushing) throw new Error('Espera a que termine el envío para borrar este mensaje.')
    await remove('messages', id)
    const media = String(item.payload.mediaUrl || '')
    if (media.startsWith(pendingPrefix)) await remove('files', media.slice(pendingPrefix.length))
    const message = { ...item.payload, id, sender_id: item.senderId, content: '', content_type: 'text', media_url: null, deleted_for_all: true, created_at: item.createdAt, status: 'cancelled' }
    const cached = await cachedConversation(scope, room, owner)
    if (cached) await cacheConversation(scope, room, { ...cached, messages: ((cached.messages || []) as GenericRow[]).filter(row => row.id !== id) }, owner)
    changed(owner); return message
  }
  return navigator.locks ? navigator.locks.request(`novvin-outbox/${owner}`, cancel) : cancel()
}
export async function cacheConversation(scope: Scope, room: string, value: Record<string, unknown>, owner: string) {
  requireOwner(owner)
  await put('conversations', { key: `${owner}/${scope}/${room}`, owner, value } satisfies Cached)
}
export async function cachedConversation(scope: Scope, room: string, owner = outboxOwner()) {
  const cached = await operation<Cached | undefined>('conversations', false, s => s.get(`${owner}/${scope}/${room}`))
  requireOwner(owner)
  return cached?.owner === owner ? cached.value : null
}
async function deliver(item: Pending) {
  requireOwner(item.owner)
  const payload = { ...item.payload, id: item.id }
  const media = String(payload.mediaUrl || '')
  if (media.startsWith(pendingPrefix)) {
    const file = await operation<Attachment | undefined>('files', false, s => s.get(media.slice(pendingPrefix.length)))
    requireOwner(item.owner)
    if (!file || file.owner !== item.owner) throw new Error('No se encontró el archivo pendiente.')
    if (!file.uploadedUrl) {
      // Existing queued Windows archives can predate the MIME normalization in the picker.
      const blob = /\.zip$/i.test(file.name) && file.blob.type !== 'application/zip' ? new Blob([file.blob], { type: 'application/zip' }) : file.blob
      const body = new FormData(); body.set('bucket', file.bucket); body.set('path', file.path); body.set('file', blob, file.name)
      // Upload time includes the server's antivirus scan, as well as transferring bytes.
      const result = await fallbackApiJson<{ object: { publicUrl: string } }>('/v1/storage/upload', { method: 'POST', body, expectedAccountId: item.owner, signal: AbortSignal.timeout(300000) })
      requireOwner(item.owner); file.uploadedUrl = result.object.publicUrl; await put('files', file)
    }
    payload.mediaUrl = file.uploadedUrl
  }
  const collection = item.scope === 'chat' ? 'chats' : item.scope === 'group' ? 'groups' : 'communities'
  const result = await fallbackApiJson<{ message: GenericRow }>(`/v1/data/messages/${collection}/${encodeURIComponent(item.room)}/messages`, {
    method: 'POST', body: JSON.stringify(payload), expectedAccountId: item.owner, signal: AbortSignal.timeout(25000),
  })
  requireOwner(item.owner)
  const cached = await cachedConversation(item.scope, item.room, item.owner)
  if (cached) await cacheConversation(item.scope, item.room, { ...cached, messages: [...((cached.messages || []) as GenericRow[]).filter(row => row.id !== item.id), result.message] }, item.owner)
  await remove('messages', item.id)
  if (media.startsWith(pendingPrefix)) await remove('files', media.slice(pendingPrefix.length))
  changed(item.owner)
  return result
}
export async function sendDurableWebMessage(scope: Scope, room: string, payload: Payload) {
  const owner = outboxOwner(); requireOwner(owner)
  const senderId = fallbackSessionToUser(readFallbackSession())?.id || owner
  const item: Pending = { id: payload.id || crypto.randomUUID(), owner, senderId, scope, room, payload, createdAt: new Date().toISOString(), state: 'pending', attempts: 0, retryAt: 0 }
  await put('messages', item); requireOwner(owner); changed(owner)
  // Flush through the same durable path for both connected and disconnected sends.
  const message = await optimistic(item)
  if (navigator.onLine) window.setTimeout(() => { void flushWebOutbox().catch(() => {}) }, 0)
  return { message }
}
export async function flushWebOutbox() {
  if (flushing || !navigator.onLine || !outboxOwner()) return
  const owner = outboxOwner()
  const run = async () => {
    flushing = true
    try {
      const items = (await all<Pending>('messages')).filter(item => item.owner === owner && item.state === 'pending').sort((a, b) => a.createdAt.localeCompare(b.createdAt))
      for (const item of items) {
        requireOwner(owner)
        if (item.retryAt > Date.now()) break
        try { await deliver(item) }
        catch (error) {
          requireOwner(owner)
          item.attempts += 1; item.state = isTransientOutboxError(error) ? 'pending' : 'failed'
          item.retryAt = Date.now() + Math.min(60000, 1000 * 2 ** Math.min(item.attempts, 6))
          await put('messages', item); changed(owner)
          if (item.state === 'pending') break
        }
      }
    } finally { flushing = false }
  }
  if (navigator.locks) await navigator.locks.request(`novvin-outbox/${owner}`, { ifAvailable: true }, lock => lock ? run() : undefined)
  else await run()
}
export function revokeOutboxPreviews() { for (const url of urls.values()) URL.revokeObjectURL(url); urls.clear() }
