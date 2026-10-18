const MAX_INLINE_CLIPBOARD_IMAGE_BYTES = 20 * 1024 * 1024
const SUPPORTED_IMAGE_MIMES = new Set(['image/png', 'image/jpeg', 'image/webp', 'image/gif'])

function normalizeImageFile(file: File): File | null {
  const extensionMime = /\.webp$/i.test(file.name) ? 'image/webp'
    : /\.gif$/i.test(file.name) ? 'image/gif'
      : /\.jpe?g$/i.test(file.name) ? 'image/jpeg'
        : /\.png$/i.test(file.name) ? 'image/png' : ''
  const type = SUPPORTED_IMAGE_MIMES.has(file.type.toLowerCase()) ? file.type.toLowerCase() : extensionMime
  if (!type) return null
  return type === file.type ? file : new File([file], file.name || `sticker.${type.split('/')[1]}`, { type, lastModified: file.lastModified })
}

function fileFromDataUrl(value: string, index: number): File | null {
  const match = value.match(/^data:(image\/(?:png|jpeg|webp|gif));base64,([a-z\d+/=\s]+)$/i)
  if (!match || !SUPPORTED_IMAGE_MIMES.has(match[1].toLowerCase())) return null
  const encoded = match[2].replace(/\s/g, '')
  if (encoded.length > Math.ceil(MAX_INLINE_CLIPBOARD_IMAGE_BYTES * 4 / 3)) return null
  try {
    const binary = atob(encoded)
    if (!binary.length || binary.length > MAX_INLINE_CLIPBOARD_IMAGE_BYTES) return null
    const bytes = Uint8Array.from(binary, char => char.charCodeAt(0))
    const mime = match[1].toLowerCase()
    return new File([bytes], `clipboard-image-${index + 1}.${mime.split('/')[1] === 'jpeg' ? 'jpg' : mime.split('/')[1]}`, { type: mime })
  } catch {
    return null
  }
}

function inlineImagesFromHtml(html: string): File[] {
  // Clipboard HTML is untrusted. Only decode embedded image data; never fetch
  // an external URL or create a live document from the markup.
  const result: File[] = []
  const imageSource = /<img\b[^>]*\bsrc\s*=\s*(["'])(data:image\/(?:png|jpeg|webp|gif);base64,[a-z\d+/=\s]+)\1/gi
  for (const match of html.matchAll(imageSource)) {
    const file = fileFromDataUrl(match[2], result.length)
    if (file) result.push(file)
  }
  return result
}

function uniqueFiles(files: File[]): File[] {
  const seen = new Set<string>()
  return files.filter(file => {
    if (!file.size) return false
    const key = `${file.name}\u0000${file.size}\u0000${file.type}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

/** Read only image bytes supplied by the paste event. Never fetch pasted links. */
export function clipboardFilesFrom(data: DataTransfer | null): File[] {
  if (!data) return []
  const files = Array.from(data.files || [])
  for (const item of Array.from(data.items || [])) {
    if (item.kind === 'file') {
      const file = item.getAsFile()
      if (file) files.push(file)
    }
  }
  if (files.length) return uniqueFiles(files.map(file => normalizeImageFile(file) ?? file))

  const inline: File[] = []
  for (const mime of SUPPORTED_IMAGE_MIMES) {
    const payload = data.getData?.(mime) || ''
    if (payload.startsWith('data:image/')) {
      const file = fileFromDataUrl(payload, inline.length)
      if (file) inline.push(file)
    }
  }
  const html = data.getData?.('text/html') || ''
  inline.push(...inlineImagesFromHtml(html))
  return uniqueFiles(inline)
}

export function clipboardMayContainImage(data: DataTransfer | null): boolean {
  if (!data) return false
  const types = Array.from(data.types || []).map(type => type.toLowerCase())
  if (types.some(type => type === 'files' || type.startsWith('image/') || type.includes('sticker'))) return true
  return types.includes('text/html') && /<img\b/i.test(data.getData?.('text/html') || '')
}

/**
 * Some desktop apps expose clipboard images only through the async Clipboard
 * API. Try it only after a user paste and only when the paste advertised image
 * or HTML data, so ordinary text paste never reads the system clipboard.
 */
export async function readClipboardImages(data: DataTransfer | null, clipboard: Pick<Clipboard, 'read'> | undefined): Promise<File[]> {
  const direct = clipboardFilesFrom(data)
  if (direct.length || !clipboardMayContainImage(data) || !clipboard?.read) return direct
  try {
    const items = await clipboard.read()
    const files: File[] = []
    for (const item of items) {
      for (const type of item.types) {
        if (SUPPORTED_IMAGE_MIMES.has(type.toLowerCase())) {
          const blob = await item.getType(type)
          if (blob.size && blob.size <= MAX_INLINE_CLIPBOARD_IMAGE_BYTES) {
            const extension = type.toLowerCase().split('/')[1].replace('jpeg', 'jpg')
            files.push(new File([blob], `clipboard-image.${extension}`, { type: type.toLowerCase() }))
          }
        } else if (type.toLowerCase() === 'text/html') {
          files.push(...inlineImagesFromHtml(await (await item.getType(type)).text()))
        }
      }
    }
    return uniqueFiles(files)
  } catch {
    return []
  }
}

export function isStickerImage(file: File): boolean {
  return Boolean(normalizeImageFile(file))
}

export function pastedAsSticker(file: File, stickerTrayOpen: boolean): boolean {
  const normalized = normalizeImageFile(file)
  return Boolean(normalized && (stickerTrayOpen || /^image\/(webp|gif)$/.test(normalized.type) || /(?:sticker|wasticker|pegatina)/i.test(file.name)))
}

/** Preserve a sticker hint before the browser strips the original WebP format. */
export function clipboardHasStickerHint(data: DataTransfer | null): boolean {
  if (!data) return false
  if (Array.from(data.types || []).some(type => /sticker/i.test(type))) return true
  const html = data.getData?.('text/html') || ''
  return /<img\b[^>]*(?:alt|title|aria-label|data-testid|class)\s*=\s*["'][^"']*(?:sticker|pegatina)/i.test(html) ||
    /<img\b[^>]*src\s*=\s*["'][^"']*(?:\.webp(?:[?"']|$)|sticker)/i.test(html)
}

/**
 * Desktop clipboard images can lose both their WebP MIME type and alpha channel.
 * A copied small square PNG (the WhatsApp sticker canvas) is still a sticker.
 * Larger photos/screenshots keep the attachment preview instead of auto-sending.
 */
export async function isCopiedStickerImage(file: File, explicitHint = false): Promise<boolean> {
  const normalized = normalizeImageFile(file)
  if (!normalized) return false
  if (explicitHint || pastedAsSticker(normalized, false)) return true
  if (normalized.type !== 'image/png') return false
  let closeImage: (() => void) | undefined
  try {
    let bitmap: ImageBitmap | HTMLImageElement
    if (typeof createImageBitmap === 'function') {
      const decoded = await createImageBitmap(normalized)
      bitmap = decoded
      closeImage = () => decoded.close()
    } else {
      const objectUrl = URL.createObjectURL(normalized)
      closeImage = () => URL.revokeObjectURL(objectUrl)
      bitmap = await new Promise<HTMLImageElement>((resolve, reject) => {
        const img = new Image()
        img.onload = () => resolve(img)
        img.onerror = reject
        img.src = objectUrl
      })
    }
    try {
      const width = bitmap instanceof HTMLImageElement ? bitmap.naturalWidth : bitmap.width
      const height = bitmap instanceof HTMLImageElement ? bitmap.naturalHeight : bitmap.height
      if (!width || !height || width > 512 || height > 512) return false
      if (width === height && width >= 96) return true
      const canvas = document.createElement('canvas')
      canvas.width = 64
      canvas.height = 64
      const context = canvas.getContext('2d', { willReadFrequently: true })
      if (!context) return false
      context.drawImage(bitmap, 0, 0, 64, 64)
      const pixels = context.getImageData(0, 0, 64, 64).data
      let transparent = 0
      for (let index = 3; index < pixels.length; index += 4) if (pixels[index] < 245) transparent += 1
      return transparent >= 20
    } finally {
      closeImage?.()
      closeImage = undefined
    }
  } catch {
    return false
  } finally {
    closeImage?.()
  }
}

export function canPasteIntoChat(target: EventTarget | null): boolean {
  if (!(target instanceof Element)) return true
  if (target.closest('[role="dialog"], .image-editor, .media-viewer')) return false
  const field = target.closest('input, textarea, [contenteditable="true"]')
  return !field || field.classList.contains('chat-input-field') || Boolean(target.closest('.stickers-tray'))
}
