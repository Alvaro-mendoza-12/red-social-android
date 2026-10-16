export type ChatAttachmentKind = 'image' | 'video' | 'audio' | 'pdf' | 'file'
export function attachmentKind(contentType: string, url: string): ChatAttachmentKind {
  const value = contentType.toLowerCase()
  if (value === 'sticker' || value === 'image' || value.startsWith('image/')) return 'image'
  if (value === 'audio' || value.startsWith('audio/')) return 'audio'
  if (value === 'video' || value.startsWith('video/')) return 'video'
  if (value.includes('pdf')) return 'pdf'
  const path = url.split(/[?#]/)[0].toLowerCase()
  if (/\.(png|jpg|jpeg|webp|gif|avif)$/.test(path)) return 'image'
  if (/\.(mp4|webm|mov|m4v)$/.test(path)) return 'video'
  if (/\.(mp3|m4a|aac|ogg|oga|wav)$/.test(path)) return 'audio'
  if (path.endsWith('.pdf')) return 'pdf'
  return 'file'
}
