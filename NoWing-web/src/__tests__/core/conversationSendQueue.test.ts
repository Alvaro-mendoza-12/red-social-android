import { ConversationSendQueue } from '../../core/conversationSendQueue'

function gate() { let finish!: () => void; const promise = new Promise<void>(done => { finish = done }); return { promise, finish } }

test('a slow upload does not prevent a new conversation from sending', async () => {
  const queue = new ConversationSendQueue(), slow = gate(), sent: string[] = []
  const upload = queue.schedule('large-file', () => slow.promise)
  await queue.schedule('other-chat', async () => { sent.push('text') })
  expect(sent).toEqual(['text']); expect(queue.has('large-file')).toBe(true)
  slow.finish(); await upload
})

test('only one drain can run for a conversation', async () => {
  const queue = new ConversationSendQueue(), slow = gate(), duplicate = jest.fn()
  const first = queue.schedule('chat', () => slow.promise)
  expect(queue.schedule('chat', duplicate)).toBe(first)
  slow.finish(); await first; expect(duplicate).not.toHaveBeenCalled()
})

test('concurrency is bounded and waiting rooms resume after failure', async () => {
  const queue = new ConversationSendQueue(2), first = gate(), second = gate(), third = jest.fn(async () => {})
  const a = queue.schedule('a', async () => { await first.promise; throw new Error('offline') })
  const failure = expect(a).rejects.toThrow('offline')
  const b = queue.schedule('b', () => second.promise), c = queue.schedule('c', third)
  await Promise.resolve(); expect(third).not.toHaveBeenCalled()
  first.finish(); await failure; await c; expect(third).toHaveBeenCalledTimes(1)
  second.finish(); await b
})
