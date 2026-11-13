export type ChatAttachmentKind = 'image' | 'video' | 'audio' | 'pdf' | 'file'

/** Keep captions, but a filename already printed on its card must not become a web link. */
export function shouldRenderMessageText(message: { content: string; contentType: string; mediaUrl: string }) {
  const caption = message.content.trim()
  if (!caption || message.contentType === 'sticker') return false
  if (!message.mediaUrl || ['location', 'contact'].includes(message.contentType)) return true
  let filename = message.mediaUrl.split(/[?#]/)[0].split('/').at(-1) || ''
  try { filename = decodeURIComponent(filename) } catch { /* Some historical filenames contain a literal %. */ }
  filename = filename.replace(/^\d+_\d+_/, '')
  if (caption.toLowerCase() === filename.toLowerCase()) return false
  return !/\.(avif|gif|jpe?g|m4a|m4v|mov|mp3|mp4|oga|ogg|pdf|png|webm|webp|wav|docx?|xlsx?|pptx?|zip)$/i.test(caption)
}
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
