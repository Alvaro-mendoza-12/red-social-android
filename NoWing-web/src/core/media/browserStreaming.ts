import { prepareStorageReadAccessToken, trustedStorageBackend } from './storageReadClient'
import { getStorageReadIdentity, onStorageReadInvalidated, requireStorageReadIdentity, type StorageReadIdentity } from './storageReadSession'
import { rewriteRuntimeStorageUrl } from '../runtimeBackend'

type Registered = { id: string; source: string; identity: StorageReadIdentity }
const records = new Map<string, Registered>()
let boot: Promise<ServiceWorker> | undefined
async function worker() {
  if (!('serviceWorker' in navigator)) throw new Error('MEDIA_STREAMING_UNAVAILABLE')
  if (!boot) boot = (async () => {
    await navigator.serviceWorker.register('/novvin-media-worker.js', { scope: '/' })
    const registration = await navigator.serviceWorker.ready
    if (!navigator.serviceWorker.controller) await new Promise<void>((resolve, reject) => {
      const timer = window.setTimeout(() => reject(new Error('MEDIA_STREAMING_UNAVAILABLE')), 5000)
      navigator.serviceWorker.addEventListener('controllerchange', () => { window.clearTimeout(timer); resolve() }, { once: true })
    })
    return navigator.serviceWorker.controller || registration.active!
  })().catch(error => { boot = undefined; throw error })
  return boot
}
async function register(record: Registered) {
  const target = await worker(); requireStorageReadIdentity(record.identity)
  const token = await prepareStorageReadAccessToken(record.identity); requireStorageReadIdentity(record.identity)
  await new Promise<void>((resolve, reject) => {
    const channel = new MessageChannel()
    const timer = window.setTimeout(() => { channel.port1.close(); reject(new Error('MEDIA_STREAMING_UNAVAILABLE')) }, 5000)
    channel.port1.onmessage = event => { window.clearTimeout(timer); channel.port1.close(); event.data?.ok ? resolve() : reject(new Error('MEDIA_STREAMING_UNAVAILABLE')) }
    target.postMessage({ type: 'NOVVIN_MEDIA_REGISTER', id: record.id, source: record.source, base: trustedStorageBackend(record.source), token, generation: record.identity.generation }, [channel.port2])
  })
  requireStorageReadIdentity(record.identity)
}
export async function browserStreamingSource(raw: string, identity = getStorageReadIdentity()) {
  const source = rewriteRuntimeStorageUrl(raw)
  if (!trustedStorageBackend(source)) {
    try { const url = new URL(source, location.origin); return ['https:', 'blob:'].includes(url.protocol) || (import.meta.env.DEV && url.origin === location.origin) ? source : '' } catch { return '' }
  }
  requireStorageReadIdentity(identity)
  const key = `${identity.generation}/${source}`
  let record = records.get(key)
  if (!record) { record = { id: crypto.randomUUID(), source, identity }; records.set(key, record) }
  await register(record)
  return `/__novvin_media/${record.id}`
}
export function warmBrowserStreaming() { void worker().catch(() => {}) }
onStorageReadInvalidated(() => {
  records.clear()
  navigator.serviceWorker?.controller?.postMessage({ type: 'NOVVIN_MEDIA_CLEAR' })
  document.querySelectorAll<HTMLMediaElement>('video[data-novvin-media],audio[data-novvin-media]').forEach(element => { element.pause(); element.removeAttribute('src'); element.load() })
  document.querySelectorAll<HTMLImageElement>('img[data-novvin-media]').forEach(element => element.removeAttribute('src'))
})
if (typeof navigator !== 'undefined' && 'serviceWorker' in navigator) navigator.serviceWorker.addEventListener('message', event => {
  if (event.data?.type !== 'NOVVIN_MEDIA_REFRESH') return
  const record = [...records.values()].find(value => value.id === event.data.id)
  if (record && record.identity === getStorageReadIdentity()) void register(record).catch(() => {})
})
