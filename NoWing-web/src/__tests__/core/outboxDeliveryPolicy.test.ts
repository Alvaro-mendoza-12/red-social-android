import { outboxDeliveryLane } from '../../core/outboxDeliveryPolicy'
import { ConversationSendQueue } from '../../core/conversationSendQueue'

test('text and catalog stickers share the ready lane; each upload has its own lane', () => {
  expect(outboxDeliveryLane({ id: 'text', payload: {} })).toBe('ready')
  expect(outboxDeliveryLane({ id: 'sticker', payload: { mediaUrl: 'https://media.invalid/sticker.webp' } })).toBe('ready')
  expect(outboxDeliveryLane({ id: 'zip', payload: { mediaUrl: 'novvin-pending-file:one' } })).toBe('upload/zip')
  expect(outboxDeliveryLane({ id: 'image', payload: { mediaUrl: 'novvin-pending-file:two' } })).toBe('upload/image')
})

test('a ZIP being scanned cannot consume the ready-message queue in its own chat', async () => {
  const transfers = new ConversationSendQueue(2), ready = new ConversationSendQueue(3)
  let finish!: () => void
  const pending = new Promise<void>(resolve => { finish = resolve })
  const uploaded = transfers.schedule('same-chat/upload/zip', () => pending)
  const sent: string[] = []
  await ready.schedule('same-chat/ready', async () => { sent.push('sticker', 'text') })
  expect(sent).toEqual(['sticker', 'text'])
  expect(transfers.has('same-chat/upload/zip')).toBe(true)
  finish(); await uploaded
})
