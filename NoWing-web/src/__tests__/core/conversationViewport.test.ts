import { captureConversationViewportAnchor, restoreConversationViewportAnchor } from '../../core/conversationViewport'

test('captures and restores the same visible message after older rows are prepended', () => {
  const thread = document.createElement('section')
  const message = document.createElement('article')
  thread.append(message)
  jest.spyOn(thread, 'getBoundingClientRect').mockReturnValue({ top: 0, bottom: 500 } as DOMRect)
  jest.spyOn(message, 'getBoundingClientRect').mockReturnValue({ top: 120, bottom: 180 } as DOMRect)
  message.id = 'message-visible'

  const anchor = captureConversationViewportAnchor(thread)
  expect(anchor).toEqual({ messageId: 'message-visible', top: 120 })
  jest.spyOn(message, 'getBoundingClientRect').mockReturnValue({ top: 245, bottom: 305 } as DOMRect)
  restoreConversationViewportAnchor(thread, anchor)

  expect(thread.scrollTop).toBe(125)
})
