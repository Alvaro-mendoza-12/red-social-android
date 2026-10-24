import { shouldReplyToMessageDoubleClick } from '../../core/chatMessageGestures'

test('double-clicking message content enters reply mode', () => {
  const message = document.createElement('article')
  const text = document.createElement('p')
  message.append(text)
  expect(shouldReplyToMessageDoubleClick(text)).toBe(true)
})

test('controls and selection mode do not trigger a reply', () => {
  const button = document.createElement('button')
  const label = document.createElement('span')
  button.append(label)
  expect(shouldReplyToMessageDoubleClick(label)).toBe(false)
  expect(shouldReplyToMessageDoubleClick(document.createElement('p'), true)).toBe(false)
})
