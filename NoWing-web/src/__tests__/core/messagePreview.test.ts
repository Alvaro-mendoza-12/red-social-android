import { inboxConversationPreview, previewFromMessage } from '../../core/messagePreview'

test('a sticker title is never presented as a text message', () => {
  expect(previewFromMessage({ content_type: 'sticker', content: 'Nombre del sticker' })).toBe('Sticker')
  expect(inboxConversationPreview({ subtitle: 'Nombre del sticker', lastMessageContentType: 'sticker' })).toBe('Sticker')
})

test('the last outgoing message carries the same author prefix as mobile', () => {
  expect(inboxConversationPreview({ subtitle: 'Sticker', lastMessageSenderId: ' OWNER ', lastMessageContentType: 'sticker' }, 'owner')).toBe('Tú: Sticker')
  expect(inboxConversationPreview({ subtitle: 'Hola', lastMessageSenderId: 'peer' }, 'owner')).toBe('Hola')
})

test('the server can identify an owned legacy alias without exposing the alias set', () => {
  expect(inboxConversationPreview({ subtitle: 'Hola', lastMessageSenderId: 'legacy-id', lastMessageIsOwn: true }, 'local-id')).toBe('Tú: Hola')
  expect(inboxConversationPreview({ subtitle: 'Hola', lastMessageSenderId: 'local-id', lastMessageIsOwn: false }, 'local-id')).toBe('Hola')
})

test('formatting leaves source data untouched and does not accumulate prefixes', () => {
  const row = { subtitle: 'Hola', lastMessageSenderId: 'owner' }
  expect(inboxConversationPreview(row, 'owner')).toBe('Tú: Hola')
  expect(inboxConversationPreview(row, 'owner')).toBe('Tú: Hola')
  expect(row.subtitle).toBe('Hola')
  expect(inboxConversationPreview(row, 'other-account')).toBe('Hola')
})

test('an empty or voice conversation cannot inherit a sender prefix', () => {
  expect(inboxConversationPreview({ subtitle: 'Sin mensajes', lastMessageSenderId: 'owner', lastMessageContentType: 'sticker' }, 'owner')).toBe('Sin mensajes')
  expect(inboxConversationPreview({ subtitle: 'Sala disponible', kind: 'voice', lastMessageIsOwn: true }, 'owner')).toBe('Sala disponible')
})

test('ordinary text, captions and a recalled sticker keep their correct semantics', () => {
  expect(previewFromMessage({ content_type: 'text', content: 'Mensaje normal' })).toBe('Mensaje normal')
  expect(previewFromMessage({ content_type: 'image', content: 'Foto con contexto' })).toBe('Foto con contexto')
  expect(previewFromMessage({ content_type: 'sticker', content: 'Nombre privado', deleted_for_all: true })).toBe('Mensaje eliminado')
  expect(inboxConversationPreview({ subtitle: 'Mensaje eliminado', lastMessageContentType: 'sticker', lastMessageIsOwn: true })).toBe('Tú: Mensaje eliminado')
})
