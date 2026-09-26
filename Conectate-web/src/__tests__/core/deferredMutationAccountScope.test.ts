import {
  enqueueDeferredMutation,
  flushDeferredMutations,
  getDeferredMutationCount,
} from '../../core/reliability'

describe('deferred mutation account isolation', () => {
  beforeEach(() => window.localStorage.clear())

  test('keeps offline queues isolated between signed-in accounts', async () => {
    enqueueDeferredMutation('chat_mark_read', { chatId: 'chat-a' }, 'user-a')
    enqueueDeferredMutation('chat_mark_read', { chatId: 'chat-b' }, 'user-b')

    expect(getDeferredMutationCount('user-a')).toBe(1)
    expect(getDeferredMutationCount('user-b')).toBe(1)

    const processed: string[] = []
    await flushDeferredMutations(async (mutation) => {
      processed.push(String((mutation.payload as { chatId?: string }).chatId))
    }, 'user-a')

    expect(processed).toEqual(['chat-a'])
    expect(getDeferredMutationCount('user-a')).toBe(0)
    expect(getDeferredMutationCount('user-b')).toBe(1)
  })

  test('drops the legacy ownerless queue instead of replaying it as another user', () => {
    window.localStorage.setItem(
      'conectate-web-deferred-mutations-v1',
      JSON.stringify([{ id: 'unsafe-legacy-entry' }]),
    )
    expect(getDeferredMutationCount('user-a')).toBe(0)
    expect(window.localStorage.getItem('conectate-web-deferred-mutations-v1')).toBeNull()
  })
})
