import { enqueueDeferredMutation, flushDeferredMutations, getDeferredMutationCount } from '../../core/reliability'

function deferred() {
  let resolve!: () => void
  const promise = new Promise<void>(done => { resolve = done })
  return { promise, resolve }
}

describe('deferred actions during synchronization', () => {
  beforeEach(() => window.localStorage.clear())

  test('an action queued while the request is pending survives completion', async () => {
    enqueueDeferredMutation('chat_mark_read', { chatId: 'first' }, 'owner')
    const gate = deferred(), started = deferred()
    const flush = flushDeferredMutations(async () => { started.resolve(); await gate.promise }, 'owner')
    await started.promise
    enqueueDeferredMutation('chat_mark_read', { chatId: 'new' }, 'owner')
    gate.resolve()
    expect(await flush).toEqual({ processed: 1, failed: 0, remaining: 1 })
    const sent: unknown[] = []
    await flushDeferredMutations(async item => { sent.push(item.payload) }, 'owner')
    expect(sent).toEqual([{ chatId: 'new' }])
  })

  test('overlapping refreshes share one execution for the same normalized owner', async () => {
    enqueueDeferredMutation('notification_mark_all', {}, 'owner')
    const gate = deferred(), started = deferred()
    const execute = jest.fn(async () => { started.resolve(); await gate.promise })
    const first = flushDeferredMutations(execute, ' Owner ')
    await started.promise
    const second = flushDeferredMutations(execute, 'owner')
    gate.resolve()
    await Promise.all([first, second])
    expect(execute).toHaveBeenCalledTimes(1)
    expect(getDeferredMutationCount('owner')).toBe(0)
  })

  test('a failed action retains its retry and actions added during that request', async () => {
    enqueueDeferredMutation('chat_mark_read', { chatId: 'retry' }, 'owner')
    const result = await flushDeferredMutations(async () => {
      enqueueDeferredMutation('notification_mark_read', { notificationId: 'new' }, 'owner')
      throw new Error('network unavailable')
    }, 'owner')
    expect(result).toEqual({ processed: 0, failed: 1, remaining: 2 })
    const sent: unknown[] = [], attempts: number[] = []
    await flushDeferredMutations(async item => { sent.push(item.payload); attempts.push(item.attempts) }, 'owner')
    expect(sent).toEqual([{ chatId: 'retry' }, { notificationId: 'new' }])
    expect(attempts).toEqual([1, 0])
  })

  test('a suspended account does not execute the next action or spend its retry', async () => {
    enqueueDeferredMutation('chat_mark_read', { chatId: 'first' }, 'owner')
    enqueueDeferredMutation('chat_mark_read', { chatId: 'second' }, 'owner')
    let active = true
    const execute = jest.fn(async () => { active = false })
    const result = await flushDeferredMutations(execute, 'owner', 50, () => active)
    expect(result).toEqual({ processed: 1, failed: 0, remaining: 1 })
    expect(execute).toHaveBeenCalledTimes(1)
    await flushDeferredMutations(async item => { expect(item.attempts).toBe(0) }, 'owner')
  })

  test('different accounts can finish independently without sharing a request', async () => {
    enqueueDeferredMutation('notification_mark_all', {}, 'a')
    enqueueDeferredMutation('notification_mark_all', {}, 'b')
    const gate = deferred(), started = deferred()
    const first = flushDeferredMutations(async () => { started.resolve(); await gate.promise }, 'a')
    await started.promise
    expect(await flushDeferredMutations(async () => {}, 'b')).toEqual({ processed: 1, failed: 0, remaining: 0 })
    expect(getDeferredMutationCount('a')).toBe(1)
    gate.resolve(); await first
  })

  test('a rejected request after leaving the account remains unattempted', async () => {
    enqueueDeferredMutation('notification_mark_all', {}, 'owner')
    let active = true
    expect(await flushDeferredMutations(async () => {
      active = false; throw new Error('OUTBOX_ACCOUNT_CHANGED')
    }, 'owner', 50, () => active)).toEqual({ processed: 0, failed: 0, remaining: 1 })
    await flushDeferredMutations(async item => { expect(item.attempts).toBe(0) }, 'owner')
  })

  test('the per-run limit preserves both untouched and newly enqueued actions', async () => {
    enqueueDeferredMutation('chat_mark_read', { chatId: 'first' }, 'owner')
    enqueueDeferredMutation('chat_mark_read', { chatId: 'untouched' }, 'owner')
    expect(await flushDeferredMutations(async () => {
      enqueueDeferredMutation('chat_mark_read', { chatId: 'new' }, 'owner')
    }, 'owner', 1)).toEqual({ processed: 1, failed: 0, remaining: 2 })
  })

  test('a tab with the owner lock busy leaves the queue for its holder', async () => {
    const original = Object.getOwnPropertyDescriptor(navigator, 'locks')
    const request = jest.fn(async (_key, _options, run) => run(null))
    Object.defineProperty(navigator, 'locks', { configurable: true, value: { request } })
    try {
      enqueueDeferredMutation('notification_mark_all', {}, 'owner')
      const execute = jest.fn()
      expect(await flushDeferredMutations(execute, 'owner')).toEqual({ processed: 0, failed: 0, remaining: 1 })
      expect(execute).not.toHaveBeenCalled()
      expect(request).toHaveBeenCalledWith('conectate-web-deferred-mutations-v2:owner', { ifAvailable: true }, expect.any(Function))
    } finally {
      if (original) Object.defineProperty(navigator, 'locks', original)
      else Reflect.deleteProperty(navigator, 'locks')
    }
  })
})
