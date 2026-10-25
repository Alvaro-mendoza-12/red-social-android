import { directConversationHasActivity } from '../../core/directConversationActivity'

test('opening a direct chat is not activity, even with generated dates', () => {
  expect(directConversationHasActivity({ created_at: '2026-10-23', last_message_at: '2026-10-23' })).toBe(false)
})
test('sent attachments, recalled messages and cleared histories are retained', () => {
  expect(directConversationHasActivity({ last_message_id: 'sent-photo' })).toBe(true)
  expect(directConversationHasActivity({}, true)).toBe(true)
  expect(directConversationHasActivity({ last_message: 'Hola' })).toBe(true)
})
