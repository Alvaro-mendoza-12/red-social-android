import { rewriteRuntimeStorageUrl } from '../runtimeBackend'
import { readBoundedStorageBlob, STORAGE_PREVIEW_MAX_BYTES } from './boundedStorageRead'
import { prepareStorageReadAccessToken, trustedStorageBackend } from './storageReadClient'
import { getStorageReadIdentity, requireStorageReadIdentity } from './storageReadSession'

/** Copy the picture's bytes, rather than its caption or a private URL. */
export async function copyChatMessage(content: string, mediaUrl: string, contentType: string) {
  if (!mediaUrl || !['sticker', 'image', 'gif'].includes(contentType)) {
    await navigator.clipboard.writeText(content)
    return
  }
  if (!navigator.clipboard?.write || typeof ClipboardItem === 'undefined') throw new Error('IMAGE_CLIPBOARD_UNAVAILABLE')
  const source = rewriteRuntimeStorageUrl(mediaUrl)
  const identity = getStorageReadIdentity()
  const backend = trustedStorageBackend(source)
  if (!backend) throw new Error('MEDIA_UNTRUSTED_ORIGIN')
  const png = (async () => {
    const blob = await readBoundedStorageBlob({
      url: source, backendBase: backend, identity, signal: new AbortController().signal,
      maxBytes: STORAGE_PREVIEW_MAX_BYTES, getAccessToken: () => prepareStorageReadAccessToken(identity),
    })
    requireStorageReadIdentity(identity)
    const bitmap = await createImageBitmap(blob)
    try {
      const limit = contentType === 'sticker' ? 512 : 4096
      const ratio = Math.min(1, limit / Math.max(bitmap.width, bitmap.height))
      const canvas = document.createElement('canvas')
      canvas.width = Math.max(1, Math.round(bitmap.width * ratio))
      canvas.height = Math.max(1, Math.round(bitmap.height * ratio))
      const context = canvas.getContext('2d')
      if (!context) throw new Error('IMAGE_CLIPBOARD_UNAVAILABLE')
      context.drawImage(bitmap, 0, 0, canvas.width, canvas.height)
      const result = await new Promise<Blob>((resolve, reject) => canvas.toBlob(value => value ? resolve(value) : reject(new Error('IMAGE_CLIPBOARD_UNAVAILABLE')), 'image/png'))
      requireStorageReadIdentity(identity)
      return result
    } finally { bitmap.close() }
  })()
  // Start the clipboard write during the click's user activation, including Safari.
  await navigator.clipboard.write([new ClipboardItem({ 'image/png': png })])
}
