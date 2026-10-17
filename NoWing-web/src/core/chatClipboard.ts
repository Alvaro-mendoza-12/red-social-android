/** Read only files actually delivered by the paste event. Never fetch pasted links. */
export function clipboardFilesFrom(data: DataTransfer | null): File[] {
  if (!data) return []
  const files = Array.from(data.files || []).filter(file => file.size > 0)
  if (files.length) return files
  return Array.from(data.items || []).filter(item => item.kind === 'file')
    .map(item => item.getAsFile()).filter((file): file is File => Boolean(file?.size))
}

export function isStickerImage(file: File): boolean {
  return /^image\/(png|jpeg|webp|gif)$/.test(file.type)
}

export function pastedAsSticker(file: File, stickerTrayOpen: boolean): boolean {
  // Browsers can normalize a copied sticker to PNG. In that case the preview
  // lets the person choose; a normal pasted photo must remain a photo.
  return isStickerImage(file) && (stickerTrayOpen || /^image\/(webp|gif)$/.test(file.type))
}

export function canPasteIntoChat(target: EventTarget | null): boolean {
  if (!(target instanceof Element)) return true
  if (target.closest('[role="dialog"], .image-editor, .media-viewer')) return false
  const field = target.closest('input, textarea, [contenteditable="true"]')
  return !field || field.classList.contains('chat-input-field') || Boolean(target.closest('.stickers-tray'))
}
