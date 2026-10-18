import { promotionLinkTarget } from '../../core/promotionDestination'
test('older message actions open the inbox using a current app route', () => {
  for (const value of ['messages', '/messages', '/app/messages', null, undefined]) expect(promotionLinkTarget(value)).toBe('/app/messages')
  expect(promotionLinkTarget('/app/messages/chat-id')).toBe('/app/messages/chat-id')
})
test('keeps HTTPS websites and rejects scripts or unknown app routes', () => {
  expect(promotionLinkTarget('https://example.com/product')).toBe('https://example.com/product')
  for (const value of ['javascript:alert(1)', '/app/not-a-route', 'https://user:secret@example.com']) expect(promotionLinkTarget(value)).toBe('/app/messages')
})
