import { markAllFallbackNotificationsRead, markFallbackNotificationRead } from '../../core/fallbackData'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'

jest.mock('../../core/auth/fallbackAuth', () => ({ fallbackApiJson: jest.fn(), readFallbackSession: () => ({ user: { id: 'current-owner' } }) }))
jest.mock('../../core/webOutbox', () => ({ outboxOwner: () => 'current-owner' }))

beforeEach(() => jest.mocked(fallbackApiJson).mockReset())

test('a delayed mark-all remains bound to its original account', async () => {
  jest.mocked(fallbackApiJson).mockRejectedValueOnce(new Error('OUTBOX_ACCOUNT_CHANGED'))
  const events = jest.fn(); window.addEventListener('novvin:notifications-updated', events)
  try {
    await expect(markAllFallbackNotificationsRead('old-owner')).rejects.toThrow('OUTBOX_ACCOUNT_CHANGED')
    expect(fallbackApiJson).toHaveBeenCalledWith('/v1/data/notifications/read-all', expect.objectContaining({ expectedAccountId: 'old-owner', method: 'POST' }))
    expect(events).not.toHaveBeenCalled()
  } finally { window.removeEventListener('novvin:notifications-updated', events) }
})

test('individual and default-account mutations carry their owner through token refresh', async () => {
  jest.mocked(fallbackApiJson).mockResolvedValue({ ok: true })
  await markFallbackNotificationRead('notice', 'old-owner')
  expect(fallbackApiJson).toHaveBeenLastCalledWith('/v1/data/notifications/notice/read', expect.objectContaining({ expectedAccountId: 'old-owner' }))
  await markAllFallbackNotificationsRead()
  expect(fallbackApiJson).toHaveBeenLastCalledWith('/v1/data/notifications/read-all', expect.objectContaining({ expectedAccountId: 'current-owner' }))
})
