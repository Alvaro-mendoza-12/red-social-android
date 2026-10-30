import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import { saveFallbackChatPreferences, markFallbackNotificationRead, updateFallbackLiveState } from '../../core/fallbackData'

jest.mock('../../core/auth/fallbackAuth', () => ({ fallbackApiJson: jest.fn(), readFallbackSession: () => ({ user: { id: 'owner' } }) }))
const api = fallbackApiJson as jest.Mock
beforeEach(() => api.mockReset().mockResolvedValue({ ok: true }))

test('persists only requested chat preferences and announces the successful change', async () => {
  const listener = jest.fn()
  window.addEventListener('novvin:conversation-preferences-changed', listener)
  try {
    await saveFallbackChatPreferences('owned-chat', { isPinned: true })
    expect(api).toHaveBeenCalledWith('/v1/data/messages/chats/owned-chat/preferences', { method: 'PATCH', body: '{"isPinned":true}' })
    expect(listener).toHaveBeenCalledTimes(1)
  } finally { window.removeEventListener('novvin:conversation-preferences-changed', listener) }
})

test('does not pretend that a rejected pin was saved', async () => {
  api.mockRejectedValueOnce(new Error('offline'))
  const listener = jest.fn()
  window.addEventListener('novvin:conversation-preferences-changed', listener)
  try {
    await expect(saveFallbackChatPreferences('chat', { isPinned: true })).rejects.toThrow('offline')
    expect(listener).not.toHaveBeenCalled()
  } finally { window.removeEventListener('novvin:conversation-preferences-changed', listener) }
})

test('refreshes notification badges after the server confirms reading', async () => {
  const listener = jest.fn()
  window.addEventListener('novvin:notifications-updated', listener)
  try {
    await markFallbackNotificationRead('notice')
    expect(api).toHaveBeenCalledWith('/v1/data/notifications/notice/read', { method: 'PATCH', expectedAccountId: 'owner', body: '{}' })
    expect(listener).toHaveBeenCalledTimes(1)
  } finally { window.removeEventListener('novvin:notifications-updated', listener) }
})

test('persists screen source so Android can use its complete aspect ratio', async () => {
  await updateFallbackLiveState('live', { source: 'screen' })
  expect(api).toHaveBeenCalledWith('/v1/data/live/live/state', { method: 'PATCH', body: '{"source":"screen"}' })
})
