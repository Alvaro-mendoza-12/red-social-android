import { textOf, type GenericRow } from './utils'

export function previewFromMessage(row: GenericRow): string {
  if (row.deleted_for_all === true || row.deletedForAll === true) return 'Mensaje eliminado'
  const type = textOf(row, ['content_type', 'contentType'], '').trim().toLowerCase()
  if (type === 'birthday_card') return '🎂 Tarjeta de cumpleaños'
  if (type === 'contact') return 'Contacto'
  if (type === 'sticker') return 'Sticker'
  if (type === 'question') {
    try {
      const question = JSON.parse(String(row.content || ''))
      return typeof question.prompt === 'string' && question.prompt.trim()
        ? `Pregunta: ${question.prompt.trim().slice(0, 160)}` : 'Pregunta de la comunidad'
    } catch { return 'Pregunta de la comunidad' }
  }
  const content = textOf(row, ['content'], '').trim()
  if (content) return content
  if (type === 'image') return 'Imagen'
  if (type === 'video') return 'Video'
  if (type === 'audio') return 'Audio'
  if (type === 'poll') return 'Encuesta'
  if (type.startsWith('view_once')) return 'View once'
  return 'Sin actividad reciente'
}

export type InboxPreviewFields = {
  subtitle: string
  kind?: string
  lastMessageId?: string
  lastMessageSenderId?: string
  lastMessageContentType?: string
  lastMessageIsOwn?: boolean
}

/** Format at render time so reloading or persisting the inbox never repeats the prefix. */
export function inboxConversationPreview(item: InboxPreviewFields, accountId?: string | null): string {
  if (item.kind === 'voice' || item.subtitle === 'Sin mensajes') return item.subtitle
  const preview = item.subtitle !== 'Mensaje eliminado' && item.lastMessageContentType?.trim().toLowerCase() === 'sticker'
    ? 'Sticker' : item.subtitle
  const own = typeof item.lastMessageIsOwn === 'boolean' ? item.lastMessageIsOwn
    : Boolean(accountId?.trim() && item.lastMessageSenderId?.trim().toLowerCase() === accountId.trim().toLowerCase())
  return own && preview ? `Tú: ${preview}` : preview
}
