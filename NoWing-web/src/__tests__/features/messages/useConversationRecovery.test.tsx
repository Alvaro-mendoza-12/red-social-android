import { act, renderHook } from '@testing-library/react'
import { useConversationRecovery } from '../../../features/messages/useConversationRecovery'
let mockConnected = true
jest.mock('../../../core/ubuntuRealtime', () => ({ ubuntuRealtimeConnected: () => mockConnected }))
beforeEach(() => { jest.useFakeTimers(); mockConnected = true })
afterEach(() => { jest.clearAllTimers(); jest.useRealTimers() })

test('reconciles once after a burst of recovery events and does not poll healthy sockets', async () => {
  const synchronize = jest.fn(async () => {})
  const { unmount } = renderHook(() => useConversationRecovery(true, synchronize))
  await act(async () => {
    window.dispatchEvent(new Event('novvin:realtime-ready'))
    window.dispatchEvent(new Event('focus'))
    window.dispatchEvent(new Event('online'))
    jest.advanceTimersByTime(100)
  })
  expect(synchronize).toHaveBeenCalledTimes(1)
  await act(async () => { jest.advanceTimersByTime(30000) })
  expect(synchronize).toHaveBeenCalledTimes(1)
  mockConnected = false
  await act(async () => { jest.advanceTimersByTime(15000) })
  expect(synchronize).toHaveBeenCalledTimes(2)
  unmount()
})

test('uses the latest conversation callback and removes all recovery work on unmount', async () => {
  const first = jest.fn(async () => {}), next = jest.fn(async () => {})
  const { rerender, unmount } = renderHook(({ synchronize }) => useConversationRecovery(true, synchronize), { initialProps: { synchronize: first } })
  rerender({ synchronize: next })
  await act(async () => { window.dispatchEvent(new Event('focus')); jest.advanceTimersByTime(100) })
  expect(first).not.toHaveBeenCalled(); expect(next).toHaveBeenCalledTimes(1)
  unmount()
  await act(async () => { window.dispatchEvent(new Event('online')); jest.advanceTimersByTime(30000) })
  expect(next).toHaveBeenCalledTimes(1)
})
