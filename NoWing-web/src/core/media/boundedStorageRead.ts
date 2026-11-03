import {
  onStorageReadInvalidated, requireStorageReadIdentity,
  type StorageReadIdentity,
} from './storageReadSession'
import { publicBackendTransportUrl } from '../publicBackendTransport'

export const STORAGE_PREVIEW_MAX_BYTES = 16 * 1024 * 1024
export const STORAGE_SHORT_MEDIA_MAX_BYTES = 32 * 1024 * 1024
const maxTransferMillis = 30_000
const maxCredentialMillis = 8_000
const maxActiveReads = 4
const maxQueuedReads = 24
let activeReads = 0
const waiting: Array<{ grant: () => void; signal: AbortSignal; priority: boolean }> = []

export function isTrustedStorageReadUrl(value: string, backendBase: string): boolean {
  try {
    // Inspect raw segments as URL parsing normalizes literal dot segments.
    if ([value, backendBase].some(text => Array.from(text).some(char => char === '\\' || char.charCodeAt(0) <= 32 || char.charCodeAt(0) === 127))) return false
    const rawPath = /^https:\/\/[^/?#]+([^?#]*)/i.exec(value)?.[1] ?? ''
    const target = new URL(value)
    const backend = new URL(backendBase)
    if (target.protocol !== 'https:' || backend.protocol !== 'https:' || target.origin !== backend.origin) return false
    if (target.username || target.password || backend.username || backend.password || backend.search || backend.hash) return false
    const prefix = `${backend.pathname.replace(/\/$/, '')}/storage/v1/object/`
    if (!rawPath.startsWith(prefix) || !target.pathname.startsWith(prefix)) return false
    const parts = rawPath.slice(prefix.length).split('/')
    if (parts[0] === 'public' || parts[0] === 'sign') parts.shift()
    if (parts.length < 2 || parts.some((part) => {
      const decoded = decodeURIComponent(part)
      return !decoded || decoded === '.' || decoded === '..' || Array.from(decoded).some(char => char === '/' || char === '\\' || char.charCodeAt(0) < 32 || char.charCodeAt(0) === 127)
    })) return false
    return true
  } catch { return false }
}

export function storageReadEdgeUrl(source: string, backendBase: string, config: {
  backendOrigin: string
  edgeOrigin: string
  pageOrigin?: string
} = {
  backendOrigin: String(import.meta.env.VITE_FALLBACK_API_URL ?? ''),
  edgeOrigin: String(import.meta.env.VITE_MEDIA_EDGE_ORIGIN ?? ''),
}): string | undefined {
  const pinned = publicBackendTransportUrl(config.backendOrigin.trim(), config.pageOrigin)
  const configured = config.edgeOrigin.trim()
  if (!configured || !isTrustedStorageReadUrl(source, backendBase) || !isTrustedStorageReadUrl(source, pinned)) return
  try {
    const url = new URL(source), edge = new URL(configured)
    if (edge.protocol !== 'https:' || edge.port || edge.username || edge.password || edge.pathname !== '/' ||
        edge.search || edge.hash || edge.origin === url.origin || url.hash ||
        !url.pathname.startsWith('/storage/v1/object/') || url.pathname.startsWith('/storage/v1/object/sign/') ||
        [...url.searchParams.keys()].some(key => /^width$/i.test(key) || /lease|token|authorization/i.test(key)) ||
        url.pathname.split('/').some(part => decodeURIComponent(part).includes('%'))) return
    edge.pathname = url.pathname
    edge.search = url.search
    return edge.href
  } catch { return }
}

function abortError(): Error { return new DOMException('Media read cancelled', 'AbortError') }

function releaseRead() {
  activeReads -= 1
  while (waiting.length > 0) {
    const next = waiting.shift()!
    if (!next.signal.aborted) { next.grant(); break }
  }
}

async function acquireRead(signal: AbortSignal, priority: boolean): Promise<() => void> {
  if (signal.aborted) throw abortError()
  if (activeReads < maxActiveReads) { activeReads += 1; return releaseRead }
  if (waiting.length >= maxQueuedReads) throw new Error('MEDIA_READ_QUEUE_FULL')
  await new Promise<void>((resolve, reject) => {
    const entry = { signal, priority, grant: () => { signal.removeEventListener('abort', abort); activeReads += 1; resolve() } }
    const abort = () => {
      const index = waiting.indexOf(entry)
      if (index >= 0) waiting.splice(index, 1)
      reject(abortError())
    }
    const before = priority ? waiting.findIndex(item => !item.priority) : -1
    if (before === -1) waiting.push(entry)
    else waiting.splice(before, 0, entry)
    signal.addEventListener('abort', abort, { once: true })
    if (signal.aborted) abort()
  })
  if (signal.aborted) { releaseRead(); throw abortError() }
  return releaseRead
}

async function untilAbort<T>(promise: Promise<T>, signal: AbortSignal): Promise<T> {
  if (signal.aborted) throw abortError()
  return new Promise<T>((resolve, reject) => {
    const abort = () => reject(abortError())
    signal.addEventListener('abort', abort, { once: true })
    promise.then(resolve, reject).finally(() => signal.removeEventListener('abort', abort))
    if (signal.aborted) abort()
  })
}

export type BoundedStorageReadOptions = {
  url: string
  backendBase: string
  identity: StorageReadIdentity
  getAccessToken: () => Promise<string>
  signal: AbortSignal
  maxBytes: number
  priority?: boolean
  fetcher?: typeof fetch
}

export async function readBoundedStorageBlob(options: BoundedStorageReadOptions): Promise<Blob> {
  if (!isTrustedStorageReadUrl(options.url, options.backendBase)) throw new Error('MEDIA_UNTRUSTED_ORIGIN')
  if (!Number.isSafeInteger(options.maxBytes) || options.maxBytes < 1 || options.maxBytes > STORAGE_SHORT_MEDIA_MAX_BYTES) {
    throw new Error('MEDIA_INVALID_LIMIT')
  }
  requireStorageReadIdentity(options.identity)
  const controller = new AbortController()
  const abort = () => controller.abort()
  options.signal.addEventListener('abort', abort, { once: true })
  if (options.signal.aborted) abort()
  const removeInvalidator = onStorageReadInvalidated(abort)
  const timer = setTimeout(abort, maxTransferMillis)
  let release: (() => void) | undefined
  let reader: ReadableStreamDefaultReader<Uint8Array> | undefined
  let credentialTimer: ReturnType<typeof setTimeout> | undefined
  try {
    release = await acquireRead(controller.signal, options.priority === true)
    credentialTimer = setTimeout(abort, maxCredentialMillis)
    const token = (await untilAbort(options.getAccessToken(), controller.signal)).trim()
    clearTimeout(credentialTimer)
    requireStorageReadIdentity(options.identity)
    if (!token || token.length > 8192 || !/^[A-Za-z0-9._~-]+$/.test(token)) throw new Error('MEDIA_SESSION_REQUIRED')
    const headers = new Headers({ Authorization: `Bearer ${token}`, 'Cache-Control': 'no-store' })
    const hostname = new URL(options.url).hostname
    if (/\.(ngrok-free\.dev|ngrok-free\.app|ngrok\.io)$/.test(hostname)) headers.set('ngrok-skip-browser-warning', 'true')
    const fetcher = options.fetcher ?? fetch
    const init: RequestInit = {
      method: 'GET', headers, cache: 'no-store', redirect: 'error', credentials: 'omit',
      referrerPolicy: 'no-referrer', signal: controller.signal,
    }
    const edge = options.identity.provider !== 'supabase' ? storageReadEdgeUrl(options.url, options.backendBase) : undefined
    let response: Response | undefined
    if (edge) {
      try { response = await untilAbort(fetcher(edge, init), controller.signal) } catch (error) {
        if (controller.signal.aborted) throw error
      }
      if (response && [429, 500, 502, 503, 504].includes(response.status)) {
        void response.body?.cancel().catch(() => {})
        response = undefined
      }
    }
    const fromEdge = response !== undefined
    if (!response) {
      requireStorageReadIdentity(options.identity)
      response = await untilAbort(fetcher(options.url, init), controller.signal)
    }
    requireStorageReadIdentity(options.identity)
    if (!response.ok || response.status !== 200 || response.redirected || response.type === 'opaque') {
      void response.body?.cancel().catch(() => {})
      throw new Error(`MEDIA_READ_${response.status}`)
    }
    if (response.url && (fromEdge ? response.url !== edge : !isTrustedStorageReadUrl(response.url, options.backendBase))) {
      void response.body?.cancel().catch(() => {})
      throw new Error('MEDIA_REDIRECT_DENIED')
    }
    const declared = response.headers.get('Content-Length')
    if (declared && (!/^\d+$/.test(declared) || Number(declared) > options.maxBytes)) {
      void response.body?.cancel().catch(() => {})
      throw new Error('MEDIA_TOO_LARGE')
    }
    if (!response.body?.getReader) throw new Error('MEDIA_STREAM_UNAVAILABLE')
    reader = response.body.getReader()
    const chunks: ArrayBuffer[] = []
    let total = 0
    for (;;) {
      requireStorageReadIdentity(options.identity)
      const result = await untilAbort(reader.read(), controller.signal)
      requireStorageReadIdentity(options.identity)
      if (result.done) break
      if (!result.value) continue
      total += result.value.byteLength
      if (total > options.maxBytes) throw new Error('MEDIA_TOO_LARGE')
      // Retain a bounded copy; a transport may reuse its input buffer.
      chunks.push(Uint8Array.from(result.value).buffer)
    }
    requireStorageReadIdentity(options.identity)
    if (total === 0) throw new Error('MEDIA_EMPTY')
    const mime = (response.headers.get('Content-Type') ?? 'application/octet-stream').split(';')[0].trim().toLowerCase()
    return new Blob(chunks, { type: mime })
  } finally {
    if (reader) { void reader.cancel().catch(() => {}); reader.releaseLock() }
    clearTimeout(timer)
    if (credentialTimer) clearTimeout(credentialTimer)
    removeInvalidator()
    options.signal.removeEventListener('abort', abort)
    release?.()
  }
}
