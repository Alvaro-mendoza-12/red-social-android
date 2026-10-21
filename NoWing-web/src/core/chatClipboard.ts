const MAX_INLINE_CLIPBOARD_IMAGE_BYTES = 20 * 1024 * 1024
const SUPPORTED_IMAGE_MIMES = new Set(['image/png', 'image/jpeg', 'image/webp', 'image/gif'])
const originalImage = (file: File) => /^image\/(gif|webp)$/.test(file.type)
const clipboardMime = (type: string) => type.toLowerCase().replace(/^web /, '')

function normalizeImageFile(file: File): File | null {
  const extensionMime = /\.webp$/i.test(file.name) ? 'image/webp'
    : /\.gif$/i.test(file.name) ? 'image/gif'
      : /\.jpe?g$/i.test(file.name) ? 'image/jpeg'
        : /\.png$/i.test(file.name) ? 'image/png' : ''
  const type = SUPPORTED_IMAGE_MIMES.has(file.type.toLowerCase()) ? file.type.toLowerCase() : extensionMime
  if (!type) return null
  return type === file.type ? file : new File([file], file.name || `sticker.${type.split('/')[1]}`, { type, lastModified: file.lastModified })
}

/** Desktop clipboard files sometimes have neither an extension nor a useful MIME. */
export async function normalizeClipboardFiles(files: File[]): Promise<File[]> {
  return Promise.all(files.map(async file => {
    try {
      const bytes = new Uint8Array(await file.slice(0, 16).arrayBuffer())
      const ascii = (start: number, end: number) => String.fromCharCode(...bytes.slice(start, end))
      const mime = ascii(0, 4) === 'RIFF' && ascii(8, 12) === 'WEBP' ? 'image/webp'
        : bytes[0] === 137 && ascii(1, 4) === 'PNG' && bytes[4] === 13 && bytes[5] === 10 ? 'image/png'
          : ascii(0, 6) === 'GIF87a' || ascii(0, 6) === 'GIF89a' ? 'image/gif'
            : bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255 ? 'image/jpeg' : ''
      if (!mime) return normalizeImageFile(file) ?? file
      const extension = mime.split('/')[1].replace('jpeg', 'jpg')
      const name = /\.(?:png|jpe?g|webp|gif)$/i.test(file.name) ? file.name : `${file.name || 'clipboard-image'}.${extension}`
      return new File([file], name, { type: mime, lastModified: file.lastModified })
    } catch { return normalizeImageFile(file) ?? file }
  }))
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

  return inlineClipboardFiles(data)
}

function inlineClipboardFiles(data: DataTransfer): File[] {
  const inline: File[] = []
  for (const type of new Set([...SUPPORTED_IMAGE_MIMES, ...Array.from(data.types || [])])) {
    if (!SUPPORTED_IMAGE_MIMES.has(clipboardMime(type))) continue
    const payload = data.getData?.(type) || ''
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
  if (types.some(type => type === 'files' || clipboardMime(type).startsWith('image/') || type.includes('sticker'))) return true
  return types.includes('text/html') && /<img\b/i.test(data.getData?.('text/html') || '')
}

/**
 * Capture event data before awaiting anything: browsers can invalidate the
 * DataTransfer after the paste handler. Async formats are alternate versions
 * of this paste only if their item contains the same bytes or HTML. Never
 * replace the current paste with an unrelated, later system clipboard image.
 */
export async function readClipboardImages(data: DataTransfer | null, clipboard: Pick<Clipboard, 'read'> | undefined): Promise<File[]> {
  const direct = clipboardFilesFrom(data)
  const html = data?.getData?.('text/html') || ''
  const stickerHint = clipboardHasStickerHint(data)
  const singleImage = direct.length === 0 || direct.length === 1 && Boolean(normalizeImageFile(direct[0]))
  // Files and rich HTML can represent the same image in different formats.
  // Keep an embedded original instead of its single-frame clipboard preview.
  const inline = data && singleImage && !direct.some(originalImage)
    ? inlineClipboardFiles(data).filter(originalImage) : []
  if (inline.length) return normalizeClipboardFiles(inline)
  const mayRead = clipboardMayContainImage(data) && singleImage && !direct.some(originalImage) &&
    (Boolean(html.match(/<img\b/i)) || stickerHint || Array.from(data?.types || []).some(type => /^web image\//i.test(type)))
  // Start while user activation is still available; never re-read DataTransfer
  // after normalization/permissions have yielded to another browser event.
  let pending: Promise<ClipboardItem[] | null> | undefined
  if (mayRead && clipboard?.read && (direct.length || html)) {
    try { pending = clipboard.read().catch(() => null) } catch { /* unavailable */ }
  }
  const normalized = await normalizeClipboardFiles(direct)
  if (normalized.some(originalImage) || !pending) return normalized
  for (const item of await pending || []) {
    if (!await matchesPasteItem(item, normalized, html)) continue
    const types = [...item.types].filter(type => SUPPORTED_IMAGE_MIMES.has(clipboardMime(type)))
      .sort((a, b) => Number(/^image\/(gif|webp)$/.test(clipboardMime(b))) - Number(/^image\/(gif|webp)$/.test(clipboardMime(a))))
    for (const type of types) {
      const mime = clipboardMime(type)
      // A PNG-only read adds nothing to the event's preview.
      if (normalized.length && !/^image\/(gif|webp)$/.test(mime)) continue
      try {
          const blob = await item.getType(type)
          if (blob.size && blob.size <= MAX_INLINE_CLIPBOARD_IMAGE_BYTES) {
            const extension = mime.split('/')[1].replace('jpeg', 'jpg')
            return normalizeClipboardFiles([new File([blob], `clipboard-image.${extension}`, { type: mime })])
          }
      } catch { /* one unsupported representation must not lose the paste */ }
    }
  }
  return normalized
}

async function matchesPasteItem(item: ClipboardItem, files: File[], html: string): Promise<boolean> {
  if (html && item.types.includes('text/html')) {
    try {
      const content = await item.getType('text/html')
      if (content.size <= MAX_INLINE_CLIPBOARD_IMAGE_BYTES && await content.text() === html) return true
    } catch { /* try comparing the image instead */ }
  }
  for (const file of files) {
    if (!SUPPORTED_IMAGE_MIMES.has(file.type) || file.size > MAX_INLINE_CLIPBOARD_IMAGE_BYTES) continue
    const type = item.types.find(type => clipboardMime(type) === file.type)
    if (!type) continue
    try {
      const blob = await item.getType(type)
      if (blob.size !== file.size) continue
      const [left, right] = await Promise.all([file.arrayBuffer(), blob.arrayBuffer()])
      const bytes = new Uint8Array(right)
      if (new Uint8Array(left).every((value, index) => value === bytes[index])) return true
    } catch { /* unsupported/read permission revoked */ }
  }
  return false
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
