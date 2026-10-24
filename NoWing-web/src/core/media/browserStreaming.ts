import { prepareStorageReadAccessToken, trustedStorageBackend } from './storageReadClient'
import { getStorageReadIdentity, onStorageReadInvalidated, requireStorageReadIdentity, type StorageReadIdentity } from './storageReadSession'
import { rewriteRuntimeStorageUrl } from '../runtimeBackend'

type Registered = { id: string; source: string; identity: StorageReadIdentity; downloadName?: string; registeredOn?: ServiceWorker; registration?: Promise<void> }
const records = new Map<string, Registered>()
let boot: Promise<ServiceWorkerRegistration> | undefined
const recoveries = new Set<() => void>()
export function subscribeBrowserStreamingRecovery(listener: () => void) {
  recoveries.add(listener)
  return () => { recoveries.delete(listener) }
}
function recover() { recoveries.forEach(listener => listener()) }
async function worker() {
  if (!('serviceWorker' in navigator)) throw new Error('MEDIA_STREAMING_UNAVAILABLE')
  if (!boot) boot = (async () => {
    return await navigator.serviceWorker.register('/novvin-media-worker.js', { scope: '/', updateViaCache: 'none' })
  })().catch(error => { boot = undefined; throw error })
  await boot
  if (!navigator.serviceWorker.controller) await new Promise<void>((resolve, reject) => {
    const changed = () => { if (navigator.serviceWorker.controller) { cleanup(); resolve() } }
    const cleanup = () => { window.clearTimeout(timer); navigator.serviceWorker.removeEventListener('controllerchange', changed) }
    const timer = window.setTimeout(() => { cleanup(); reject(new Error('MEDIA_STREAMING_UNAVAILABLE')) }, 5000)
    navigator.serviceWorker.addEventListener('controllerchange', changed)
    changed()
  })
  // An installed update replaces the controller while this page stays open.
  // Never retain a ServiceWorker instance from an earlier deployment.
  const target = navigator.serviceWorker.controller
  if (!target || target.state === 'redundant') { boot = undefined; throw new Error('MEDIA_STREAMING_UNAVAILABLE') }
  return target
}
async function register(record: Registered, retry = true): Promise<void> {
  const target = await worker(); requireStorageReadIdentity(record.identity)
  const token = await prepareStorageReadAccessToken(record.identity); requireStorageReadIdentity(record.identity)
  try { await new Promise<void>((resolve, reject) => {
    const channel = new MessageChannel()
    const cleanup = () => { window.clearTimeout(timer); channel.port1.close(); target.removeEventListener('statechange', changed) }
    const fail = () => { cleanup(); reject(new Error('MEDIA_STREAMING_UNAVAILABLE')) }
    const changed = () => { if (target.state === 'redundant') fail() }
    const timer = window.setTimeout(fail, 5000)
    target.addEventListener('statechange', changed)
    channel.port1.onmessage = event => { cleanup(); if (event.data?.ok) resolve(); else reject(new Error('MEDIA_STREAMING_UNAVAILABLE')) }
    try { target.postMessage({ type: 'NOVVIN_MEDIA_REGISTER', id: record.id, source: record.source, base: trustedStorageBackend(record.source), token, generation: record.identity.generation, downloadName: record.downloadName }, [channel.port2]) } catch { fail() }
  }) } catch (error) {
    requireStorageReadIdentity(record.identity)
    if (retry && target !== navigator.serviceWorker.controller) return register(record, false)
    throw error
  }
  requireStorageReadIdentity(record.identity)
  record.registeredOn = target
}
export async function browserStreamingSource(raw: string, identity = getStorageReadIdentity(), options: { downloadName?: string } = {}) {
  const source = rewriteRuntimeStorageUrl(raw)
  if (!trustedStorageBackend(source)) {
    try { const url = new URL(source, location.origin); return ['https:', 'blob:'].includes(url.protocol) || (import.meta.env.DEV && url.origin === location.origin) ? source : '' } catch { return '' }
  }
  requireStorageReadIdentity(identity)
  // eslint-disable-next-line no-control-regex -- attachment names must not contain control characters
  const downloadName = options.downloadName?.replace(/[\u0000-\u001f\u007f/\\]/g,'_').slice(0,180)
  const key = `${identity.generation}/${source}/${downloadName || ''}`
  let record = records.get(key)
  if (!record) { record = { id: crypto.randomUUID(), source, identity, downloadName }; records.set(key, record) }
  // Avatars are mounted again when changing inbox tabs. Reuse their registered
  // handle and share concurrent registrations instead of asking for credentials
  // and a worker round trip on every render.
  if (!record.registeredOn || record.registeredOn !== navigator.serviceWorker?.controller) {
    if (!record.registration) {
      const pending = register(record)
      record.registration = pending
      void pending.finally(() => { if (record!.registration === pending) record!.registration = undefined }).catch(() => {})
    }
    await record.registration
  }
  requireStorageReadIdentity(identity)
  return `/__novvin_media/${record.id}`
}
/** A remount can render an already authorized handle on its first paint. */
export function cachedBrowserStreamingSource(raw: string, identity = getStorageReadIdentity()): string | undefined {
  if (!identity.accountId || identity !== getStorageReadIdentity()) return undefined
  const source = rewriteRuntimeStorageUrl(raw)
  const record = records.get(`${identity.generation}/${source}/`)
  const target = navigator.serviceWorker?.controller
  return record?.registeredOn && record.registeredOn === target && target?.state !== 'redundant'
    ? `/__novvin_media/${record.id}` : undefined
}
export function warmBrowserStreaming() { void worker().catch(() => {}) }
onStorageReadInvalidated(() => {
  records.clear()
  navigator.serviceWorker?.controller?.postMessage({ type: 'NOVVIN_MEDIA_CLEAR' })
  document.querySelectorAll<HTMLMediaElement>('video[data-novvin-media],audio[data-novvin-media]').forEach(element => { element.pause(); element.removeAttribute('src'); element.load() })
  document.querySelectorAll<HTMLImageElement>('img[data-novvin-media]').forEach(element => element.removeAttribute('src'))
})
if (typeof window !== 'undefined') {
  window.addEventListener('online', recover)
  document.addEventListener('visibilitychange', () => { if (!document.hidden) recover() })
}
if (typeof navigator !== 'undefined' && 'serviceWorker' in navigator) {
 navigator.serviceWorker.addEventListener('controllerchange', recover)
 navigator.serviceWorker.addEventListener('message', event => {
  if (event.data?.type !== 'NOVVIN_MEDIA_REFRESH') return
  const record = [...records.values()].find(value => value.id === event.data.id)
  if (record && record.identity === getStorageReadIdentity()) void register(record).catch(() => {})
})
}
