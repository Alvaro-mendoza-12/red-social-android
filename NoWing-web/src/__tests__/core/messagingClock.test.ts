import { messagingNowIso, recordMessagingClock } from '../../core/messagingClock'

const databaseTime = '2026-10-24T00:23:40.000Z'
afterEach(() => jest.restoreAllMocks())
test('a PC that is 140 seconds slow uses database time for its optimistic message', () => {
  jest.spyOn(Date, 'now').mockReturnValue(Date.parse(databaseTime) - 140000)
  jest.spyOn(performance, 'now').mockReturnValue(1100)
  recordMessagingClock('https://trusted-clock.test', databaseTime, 900, 1000)
  expect(messagingNowIso('https://trusted-clock.test')).toBe('2026-10-24T00:23:40.150Z')
})
test('fixing the OS clock does not apply the measured offset twice', () => {
  const time = jest.spyOn(performance, 'now').mockReturnValue(2000)
  const local = jest.spyOn(Date, 'now').mockReturnValue(Date.parse(databaseTime) - 140000)
  recordMessagingClock('https://fixed-clock.test', databaseTime, 1900, 2000)
  local.mockReturnValue(Date.parse(databaseTime) + 1000)
  time.mockReturnValue(3000)
  expect(messagingNowIso('https://fixed-clock.test')).toBe('2026-10-24T00:23:41.050Z')
})
test('unknown, ambiguous, slow or other-backend samples cannot change the message clock', () => {
  jest.spyOn(Date, 'now').mockReturnValue(Date.parse(databaseTime))
  jest.spyOn(performance, 'now').mockReturnValue(1000)
  recordMessagingClock('https://ambiguous.test', '2026-10-24T00:30:00', 0, 1000)
  recordMessagingClock('https://slow.test', '2026-10-24T00:30:00Z', 0, 6000)
  expect(messagingNowIso('https://ambiguous.test')).toBe(databaseTime)
  expect(messagingNowIso('https://slow.test')).toBe(databaseTime)
  expect(messagingNowIso('https://other.test')).toBe(databaseTime)
})
