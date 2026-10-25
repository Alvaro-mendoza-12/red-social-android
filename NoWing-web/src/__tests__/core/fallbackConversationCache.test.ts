import { loadFallbackChat, loadFallbackRoomChat, loadFallbackHistoryPage, fallbackHistoryAllows } from '../../core/fallbackData'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import { cachedConversation, cacheConversation, pendingConversationMessages } from '../../core/webOutbox'
jest.mock('../../core/auth/fallbackAuth', () => ({ fallbackApiJson: jest.fn() }))
jest.mock('../../core/webOutbox', () => ({
  outboxOwner: () => 'account-a', cachedConversation: jest.fn(), cacheConversation: jest.fn(),
  pendingConversationMessages: jest.fn(), isTransientOutboxError: (error: Error) => error.message === 'offline',
}))
jest.mock('../../core/inboxActivity', () => ({ notifyInboxMessage: jest.fn() }))
const api = fallbackApiJson as jest.MockedFunction<typeof fallbackApiJson>
const cached = cachedConversation as jest.MockedFunction<typeof cachedConversation>
const pending = pendingConversationMessages as jest.MockedFunction<typeof pendingConversationMessages>
beforeEach(() => { jest.clearAllMocks(); pending.mockResolvedValue([]); (cacheConversation as jest.Mock).mockResolvedValue(undefined) })

test('shows an account-owned cache while the network is still pending and reconciles the response', async () => {
  let finish!: (value: unknown) => void
  api.mockImplementation(() => new Promise(resolve => { finish = resolve }))
  cached.mockResolvedValue({ title: 'Cached', messages: [{ id: 'saved', content: 'old' }] })
  pending.mockResolvedValue([{ id: 'queued', content: 'pending' }])
  const onCached = jest.fn(), request = loadFallbackChat('room-id', onCached)
  await Promise.resolve(); await Promise.resolve(); await Promise.resolve()
  expect(api).toHaveBeenCalledWith('/v1/data/messages/chats/room-id', { expectedAccountId: 'account-a' })
  expect(onCached).toHaveBeenCalledWith(expect.objectContaining({ title: 'Cached', messages: [{ id: 'saved', content: 'old' }, { id: 'queued', content: 'pending' }] }))
  finish({ title: 'Fresh', messages: [{ id: 'saved', content: 'new' }] })
  await expect(request).resolves.toEqual(expect.objectContaining({ title: 'Fresh', messages: [{ id: 'saved', content: 'new' }, { id: 'queued', content: 'pending' }] }))
  expect(cacheConversation).toHaveBeenCalledWith('chat', 'room-id', expect.objectContaining({ title: 'Fresh' }), 'account-a')
})

test('a broken cache does not stop a fresh authorized room read', async () => {
  cached.mockRejectedValue(new Error('IDB_UNAVAILABLE'))
  api.mockResolvedValue({ title: 'Group', messages: [] })
  const onCached = jest.fn()
  await expect(loadFallbackRoomChat('group', 'room-id', onCached)).resolves.toEqual({ title: 'Group', messages: [] })
  expect(onCached).not.toHaveBeenCalled()
})

test('authorization failure is propagated even when the cache can render immediately', async () => {
  cached.mockResolvedValue({ title: 'Cached', messages: [] })
  api.mockRejectedValue(Object.assign(new Error('FORBIDDEN'), { status: 403 }))
  await expect(loadFallbackChat('room-id', jest.fn())).rejects.toThrow('FORBIDDEN')
})

test('recent-page reads use a bounded endpoint while legacy callers retain full-history reads', async () => {
  api.mockResolvedValue({ title: 'Chat', messages: [], historyPage: { hasMore: true, nextCursor: 'cursor' } })
  await loadFallbackChat('room-id', undefined, 80)
  await loadFallbackRoomChat('community', 'room-id', undefined, 80)
  expect(api).toHaveBeenCalledWith('/v1/data/messages/chats/room-id?limit=80', { expectedAccountId: 'account-a' })
  expect(api).toHaveBeenCalledWith('/v1/data/messages/communities/room-id?limit=80', { expectedAccountId: 'account-a' })
})

test('an IndexedDB quota error cannot turn a successful network response into a failed chat', async () => {
  api.mockResolvedValue({ title: 'Fresh', messages: [] })
  ;(cacheConversation as jest.Mock).mockRejectedValue(new Error('QUOTA_EXCEEDED'))
  await expect(loadFallbackChat('room-id')).resolves.toEqual(expect.objectContaining({ title: 'Fresh' }))
})

test('an older page uses only its account-owned cached page when offline, never on authorization denial', async () => {
  const value = { title: 'Saved page', messages: [{ id: 'older' }], historyPage: { hasMore: false, nextCursor: null } }
  cached.mockResolvedValue(value)
  api.mockRejectedValue(new Error('offline'))
  await expect(loadFallbackHistoryPage('group', 'room-id', 'cursor')).resolves.toEqual(value)
  expect(cached).toHaveBeenCalledWith('group', 'room-id/history/cursor', 'account-a')
  api.mockRejectedValue(Object.assign(new Error('FORBIDDEN'), { status: 403 }))
  await expect(loadFallbackHistoryPage('group', 'room-id', 'cursor')).rejects.toThrow('FORBIDDEN')
})

test('retained pages are revalidated against clear and membership boundaries', () => {
  const base = { title: '', messages: [], otherParticipant: null, settings: { isMuted: false, isPinned: false, isArchived: false, isFavorite: false, translateEnabled: false, targetLang: 'es' } }
  expect(fallbackHistoryAllows({ ...base, chat: { cleared_at: '2026-10-23T12:00:00Z' } }, '2026-10-22T00:00:00Z')).toBe(false)
  const response = { ...base, historyAccess: { isMember: false, canReadHistory: true, retainedRanges: [{ from: null, until: '2026-10-23T12:00:00Z' }] } }
  expect(fallbackHistoryAllows(response, '2026-10-22T00:00:00Z')).toBe(true)
  expect(fallbackHistoryAllows(response, '2026-10-24T00:00:00Z')).toBe(false)
})
