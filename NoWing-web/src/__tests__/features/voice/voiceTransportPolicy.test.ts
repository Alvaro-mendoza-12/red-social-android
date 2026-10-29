import { voiceMembershipRecovery, voiceTransportOptions } from '../../../features/voice/voiceTransportPolicy'

beforeEach(() => jest.useFakeTimers())
afterEach(() => jest.useRealTimers())

test('HTTP admission starts before optional websocket upgrade', () => {
  expect(voiceTransportOptions.transports).toEqual(['polling', 'websocket'])
  expect(voiceTransportOptions.tryAllTransports).toBe(true)
})

test('one outstanding membership request retries only after its deadline', () => {
  const retry = jest.fn(), connect = jest.fn()
  const recovery = voiceMembershipRecovery({ connected: true, connect } as never, retry, () => true)
  expect(recovery.begin()).toBe(true)
  expect(recovery.begin()).toBe(false)
  jest.advanceTimersByTime(9999); expect(retry).not.toHaveBeenCalled()
  jest.advanceTimersByTime(1); expect(retry).toHaveBeenCalledTimes(1)
  expect(recovery.begin()).toBe(true)
  recovery.complete()
  jest.advanceTimersByTime(10000); expect(retry).toHaveBeenCalledTimes(1)
})

test('old room and cleanup cancel all membership timers', () => {
  let current = true
  const retry = jest.fn()
  const recovery = voiceMembershipRecovery({ connected: true, connect: jest.fn() } as never, retry, () => current)
  recovery.begin(); current = false; jest.runOnlyPendingTimers()
  expect(retry).not.toHaveBeenCalled()
  current = true; recovery.begin(); recovery.complete(); jest.runOnlyPendingTimers()
  expect(retry).not.toHaveBeenCalled()
})

test('server-initiated disconnect reconnects only a current room', () => {
  let current = true
  const connect = jest.fn(), recovery = voiceMembershipRecovery({ connected: true, connect } as never, jest.fn(), () => current)
  recovery.reconnect('io server disconnect'); expect(connect).toHaveBeenCalledTimes(1)
  recovery.reconnect('transport close'); expect(connect).toHaveBeenCalledTimes(1)
  current = false; recovery.reconnect('io server disconnect'); expect(connect).toHaveBeenCalledTimes(1)
})
