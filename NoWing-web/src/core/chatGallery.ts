import type { ViewerItem } from '../components/MediaViewer'
export function chatGallery(messages: readonly { id: string; contentType: string; content: string; mediaUrl: string | null; deletedForAll: boolean; deletedBy: string[] }[], viewerId: string): Array<ViewerItem & { id: string }> {
  return messages.filter(message => message.mediaUrl && !message.deletedForAll && !message.deletedBy.includes(viewerId) && (message.contentType === 'image' || message.contentType.startsWith('image/') || message.contentType === 'video' || message.contentType.startsWith('video/'))).map(message => ({ id: message.id, url: message.mediaUrl!, type: message.contentType.startsWith('video') ? 'video' : 'image', title: message.content || 'Foto del chat' }))
}
