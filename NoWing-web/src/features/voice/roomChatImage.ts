export const maxRoomImageData = 132000
export const roomChatTtlMs = 90 * 60 * 1000
export function boundedRoomChat<T extends { id: string; text: string; imageData?: string; userId?: string; expiresAt?: number }>(messages: T[], now = Date.now()): T[] {
  const byId = new Map<string,T>()
  for (const item of messages) if (!item.expiresAt || item.expiresAt > now) byId.set(`${item.userId || ''}:${item.id}`, item)
  const unique = [...byId.values()].slice(-100)
  const photos = new Set(unique.filter(item => item.imageData).slice(-10).map(item => item.id))
  return unique.map(item => item.imageData && !photos.has(item.id) ? { ...item, imageData: undefined, text: 'Foto anterior de esta sesión' } : item)
}
export function validRoomImage(value: unknown): value is string {
  return typeof value === 'string' && value.length <= maxRoomImageData && /^data:image\/jpeg;base64,[A-Za-z0-9+/]+={0,2}$/.test(value)
}
export function firstRoomClipboardImage(items: ArrayLike<Pick<DataTransferItem, 'kind' | 'type' | 'getAsFile'>>) {
  for (const item of Array.from(items)) {
    if (item.kind === 'file' && /^image\/(jpeg|png|webp)$/.test(item.type)) {
      const file = item.getAsFile()
      if (file) return file
    }
  }
  return null
}
/** Small session-only images travel to joined room members; no public storage URL is created. */
export async function prepareRoomImage(file: File): Promise<string> {
  if (!/^image\/(jpeg|png|webp)$/.test(file.type) || file.size > 12 * 1024 * 1024) throw new Error('Elige una foto JPG, PNG o WebP de hasta 12 MB.')
  const image = await createImageBitmap(file)
  try {
    const scale = Math.min(1, 720 / Math.max(image.width, image.height))
    const canvas = document.createElement('canvas'); canvas.width = Math.max(1, Math.round(image.width * scale)); canvas.height = Math.max(1, Math.round(image.height * scale))
    const context = canvas.getContext('2d'); if (!context) throw new Error('No se pudo preparar la foto.')
    context.fillStyle = '#fff'; context.fillRect(0,0,canvas.width,canvas.height); context.drawImage(image,0,0,canvas.width,canvas.height)
    for (const quality of [.8,.65,.5,.35,.2]) { const data = canvas.toDataURL('image/jpeg', quality); if (validRoomImage(data)) return data }
    throw new Error('La foto tiene demasiado detalle. Elige una imagen más pequeña.')
  } finally { image.close() }
}
