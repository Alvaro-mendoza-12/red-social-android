import { inboxRefreshScheduler } from '../../core/inboxRefreshScheduler'

beforeEach(() => jest.useFakeTimers())
afterEach(() => jest.useRealTimers())
const settle = async () => { await Promise.resolve(); await Promise.resolve(); await Promise.resolve() }

test('continuous activity cannot postpone discovery and in-flight reads do not overlap', async () => {
  let finish!: () => void
  const refresh = jest.fn(() => new Promise<void>(resolve => { finish = resolve }))
  const scheduler = inboxRefreshScheduler(refresh, jest.fn())
  for (let i = 0; i < 12; i++) { scheduler.request(); jest.advanceTimersByTime(10) }
  expect(refresh).toHaveBeenCalledTimes(1)
  scheduler.request(); scheduler.request(); jest.advanceTimersByTime(1000)
  expect(refresh).toHaveBeenCalledTimes(1)
  finish(); await settle(); jest.advanceTimersByTime(120)
  expect(refresh).toHaveBeenCalledTimes(2)
  scheduler.stop(); finish(); await settle()
})

test('stopping an old account cancels its pending retry', async () => {
  let finish!: () => void
  const refresh = jest.fn(() => new Promise<void>(resolve => { finish = resolve }))
  const scheduler = inboxRefreshScheduler(refresh, jest.fn())
  scheduler.request(); jest.advanceTimersByTime(120); scheduler.request()
  scheduler.stop(); finish(); await settle(); jest.advanceTimersByTime(1000)
  expect(refresh).toHaveBeenCalledTimes(1)
})

test('a failed read can recover on subsequent activity', async () => {
  const error = new Error('synthetic offline')
  const refresh = jest.fn().mockRejectedValueOnce(error).mockResolvedValue(undefined)
  const onError = jest.fn(), scheduler = inboxRefreshScheduler(refresh, onError)
  scheduler.request(); jest.advanceTimersByTime(120); await settle()
  expect(onError).toHaveBeenCalledWith(error)
  scheduler.request(); jest.advanceTimersByTime(120); await settle()
  expect(refresh).toHaveBeenCalledTimes(2)
  scheduler.stop()
})
