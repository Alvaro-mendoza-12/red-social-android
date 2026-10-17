import {getStorageReadIdentity} from './media/storageReadSession'
import {registerOutboxPreview,clearOutboxPreviewRegistry} from './media/outboxPreviewRegistry'
import { fallbackApiJson, fallbackSessionToUser, readFallbackSession } from './auth/fallbackAuth'
import { ConversationSendQueue } from './conversationSendQueue'
import { outboxFailureMessage } from './outboxFailureMessage'
import { MAX_MESSAGE_UPLOAD_BYTES } from './attachmentPreparation'
import {setOutboxProgress,clearOutboxProgress} from './outboxProgress'
import { outboxDeliveryLane } from './outboxDeliveryPolicy'
import type { GenericRow } from './utils'

type Scope = 'chat' | 'group' | 'community'
type Payload = { id?: string; content: string; contentType?: string; mediaUrl?: string | null; [key: string]: unknown }
type Pending = { id: string; owner: string; scope: Scope; room: string; payload: Payload; createdAt: string; senderId: string; state: 'pending' | 'failed'; attempts: number; retryAt: number; failureMessage?: string }
export type ImportedSticker = { id: string; title: string; isAnimated: boolean }
type Attachment = { id: string; owner: string; bucket: string; blob: Blob; name: string; path: string; createdAt: number; uploadedUrl?: string; favoriteSticker?: ImportedSticker; favoriteSaved?: boolean }
type Cached = { key: string; owner: string; value: Record<string, unknown> }
const pendingPrefix = 'novvin-pending-file:'
let opening: Promise<IDBDatabase> | undefined
const sendQueue = new ConversationSendQueue(3)
const fileQueue = new ConversationSendQueue(2)
const roomKey = (owner: string, scope: Scope, room: string) => [owner, scope, room].join('/')
const roomLock = (owner: string, scope: Scope, room: string) => 'novvin-outbox-room/' + roomKey(owner, scope, room)
const urls = new Map<string, string>()

async function withConversationLock<T>(owner: string, scope: Scope, room: string, ifAvailable: boolean, run: () => Promise<T>) {
  if (!navigator.locks) return run()
  // Shared owner lock remains compatible with tabs running the previous version,
  // whose outbox holds this owner lock exclusively for the entire flush.
  return navigator.locks.request(`novvin-outbox/${owner}`, { mode: 'shared', ifAvailable }, ownerLock => ownerLock
    ? navigator.locks.request(roomLock(owner, scope, room), { ifAvailable }, lock => lock ? run() : undefined)
    : undefined)
}

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
  const identity=getStorageReadIdentity()
  if(identity.accountId===fallbackSessionToUser(readFallbackSession())?.id)registerOutboxPreview(urls.get(key)!,identity)
  return urls.get(key)!
}
async function optimistic(item: Pending): Promise<GenericRow> {
  const media = String(item.payload.mediaUrl || '')
  return { ...item.payload, id: item.id, content_type: item.payload.contentType || 'text',
    media_url: media.startsWith(pendingPrefix) ? await attachmentUrl(item.owner, media.slice(pendingPrefix.length)) : media,
    sender_id: item.senderId, created_at: item.createdAt, status: item.state, delivery_error: item.failureMessage || '',
    ...(item.scope === 'chat' ? { chat_id: item.room } : { [`${item.scope}_id`]: item.room }) }
}
export async function stageOfflineChatAttachment(bucket: string, file: File, path?: string, favoriteSticker?: ImportedSticker) {
  const owner = outboxOwner(); requireOwner(owner)
  if (file.size > MAX_MESSAGE_UPLOAD_BYTES) throw new Error('El archivo supera los 500 MB permitidos por mensaje.')
  const id = crypto.randomUUID()
  await put('files', { id, owner, bucket, blob: file, name: file.name, path: path || `${owner}/${id}/${file.name}`, createdAt: Date.now(), ...(favoriteSticker ? { favoriteSticker } : {}) } satisfies Attachment)
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
  item.state = 'pending'; item.retryAt = 0; item.failureMessage = undefined
  await put('messages', item); changed(owner)
  void flushWebOutbox().catch(() => {})
}
export async function cancelPendingWebMessage(scope: Scope, room: string, id: string): Promise<GenericRow | null> {
  const owner = outboxOwner(); requireOwner(owner)
  const previous = await operation<Pending | undefined>('messages', false, s => s.get(id))
  requireOwner(owner)
  if(!previous||previous.owner!==owner||previous.scope!==scope||previous.room!==room)return null
  const cancel = async () => {
    const item = await operation<Pending | undefined>('messages', false, s => s.get(id))
    requireOwner(owner)
    if (!item || item.owner !== owner || item.scope !== scope || item.room !== room) return null
    // Without Web Locks an outstanding send must finish before deciding if it is local.
    const key=roomKey(owner,scope,room)+'/'+outboxDeliveryLane(item)
    if (!navigator.locks && (sendQueue.has(key)||fileQueue.has(key))) throw new Error('Espera a que termine el envío para borrar este mensaje.')
    await remove('messages', id)
    const media = String(item.payload.mediaUrl || '')
    if (media.startsWith(pendingPrefix)) await remove('files', media.slice(pendingPrefix.length))
    const message = { ...item.payload, id, sender_id: item.senderId, content: '', content_type: 'text', media_url: null, deleted_for_all: true, created_at: item.createdAt, status: 'cancelled' }
    const cached = await cachedConversation(scope, room, owner)
    if (cached) await cacheConversation(scope, room, { ...cached, messages: ((cached.messages || []) as GenericRow[]).filter(row => row.id !== id) }, owner)
    changed(owner); return message
  }
  const cancelled=await withConversationLock(owner, scope, room+'/'+outboxDeliveryLane(previous), true, cancel)
  if(cancelled===undefined)throw new Error('Espera a que termine el envío para borrar este mensaje.')
  return cancelled
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
      setOutboxProgress({owner:item.owner,id:item.id,phase:'uploading',loaded:0,total:file.blob.size})
      // Existing queued Windows archives can predate the MIME normalization in the picker.
      const blob = /\.zip$/i.test(file.name) && file.blob.type !== 'application/zip' ? new Blob([file.blob], { type: 'application/zip' }) : file.blob
      const body = new FormData(); body.set('bucket', file.bucket); body.set('path', file.path); body.set('file', blob, file.name)
      // Upload time includes the server's antivirus scan, as well as transferring bytes.
      const result = await fallbackApiJson<{ object: { publicUrl: string } }>('/v1/storage/upload', { method: 'POST', body, expectedAccountId: item.owner, signal: AbortSignal.timeout(1200000),onUploadProgress:value=>setOutboxProgress({owner:item.owner,id:item.id,...value}) })
      requireOwner(item.owner); file.uploadedUrl = result.object.publicUrl; await put('files', file)
    }
    if (file.favoriteSticker && !file.favoriteSaved) {
      const sticker = file.favoriteSticker
      await fallbackApiJson('/rest/v1/rpc/secure_toggle_sticker_favorite', {
        method: 'POST', expectedAccountId: item.owner, signal: AbortSignal.timeout(25000),
        body: JSON.stringify({ p_sticker_id: sticker.id, p_favorite: true, p_media_url: file.uploadedUrl, p_title: sticker.title, p_is_animated: sticker.isAnimated }),
      })
      requireOwner(item.owner); file.favoriteSaved = true; await put('files', file)
    }
    payload.mediaUrl = file.uploadedUrl
  }
  setOutboxProgress({owner:item.owner,id:item.id,phase:'sending'})
  const collection = item.scope === 'chat' ? 'chats' : item.scope === 'group' ? 'groups' : 'communities'
  const result = await fallbackApiJson<{ message: GenericRow }>(`/v1/data/messages/${collection}/${encodeURIComponent(item.room)}/messages`, {
    method: 'POST', body: JSON.stringify(payload), expectedAccountId: item.owner, signal: AbortSignal.timeout(25000),
  })
  requireOwner(item.owner)
  const cached = await cachedConversation(item.scope, item.room, item.owner)
  if (cached) await cacheConversation(item.scope, item.room, { ...cached, messages: [...((cached.messages || []) as GenericRow[]).filter(row => row.id !== item.id), result.message] }, item.owner)
  await remove('messages', item.id)
  clearOutboxProgress(item.owner,item.id)
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
  if (!navigator.onLine || !outboxOwner()) return
  const owner = outboxOwner()
  const items = (await all<Pending>('messages')).filter(item => item.owner === owner && item.state === 'pending').sort((a, b) => a.createdAt.localeCompare(b.createdAt))
  requireOwner(owner)
  const rooms = new Map<string, Pending>()
  for (const item of items) {const key=roomKey(owner,item.scope,item.room)+'/'+outboxDeliveryLane(item);if(!rooms.has(key))rooms.set(key,item)}
  await Promise.allSettled(Array.from(rooms, ([key, room]) => (outboxDeliveryLane(room)==='ready'?sendQueue:fileQueue).schedule(key, async () => {
    const drain = async () => {
      // Re-read under the cross-tab lock. Ready messages stay ordered within
      // their lane; independent file transfers may complete in any order.
      while (navigator.onLine) {
        requireOwner(owner)
        const item = (await all<Pending>('messages')).filter(entry => entry.owner === owner && entry.scope === room.scope && entry.room === room.room && entry.state === 'pending' && outboxDeliveryLane(entry)===outboxDeliveryLane(room)).sort((a, b) => a.createdAt.localeCompare(b.createdAt))[0]
        if (!item || item.retryAt > Date.now()) return
        try { await deliver(item) }
        catch (error) {
          requireOwner(owner)
          clearOutboxProgress(owner,item.id)
          item.attempts += 1; item.state = isTransientOutboxError(error) ? 'pending' : 'failed'
          item.failureMessage = item.state==='failed' ? outboxFailureMessage(error) : undefined
          item.retryAt = Date.now() + Math.min(60000, 1000 * 2 ** Math.min(item.attempts, 6))
          await put('messages', item); changed(owner)
          if (item.state === 'pending') return
        }
      }
    }
    await withConversationLock(owner, room.scope, room.room+'/'+outboxDeliveryLane(room), true, drain)
  })))
}
export function revokeOutboxPreviews(owner = outboxOwner()) { clearOutboxProgress(owner);clearOutboxPreviewRegistry(); for (const url of urls.values()) URL.revokeObjectURL(url); urls.clear() }
