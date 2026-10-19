import { getOrCreateDirectChat } from '../../../features/messages/directChat'

const mockFrom = jest.fn()
jest.mock('../../../core/supabase', () => ({ supabase: { from: (...args: unknown[]) => mockFrom(...args) } }))
jest.mock('../../../core/auth/fallbackAuth', () => ({ readFallbackSession: () => null }))
jest.mock('../../../core/fallbackData', () => ({ createFallbackDirectChat: jest.fn(), sendFallbackChatMessage: jest.fn() }))

test('opening an existing direct chat never rewrites either participant read time or join date', async () => {
  const query = { select: jest.fn(), eq: jest.fn(), contains: jest.fn(), order: jest.fn(), limit: jest.fn(), upsert: jest.fn() }
  query.select.mockReturnValue(query)
  query.eq.mockReturnValue(query)
  query.contains.mockReturnValue(query)
  query.order.mockReturnValue(query)
  query.limit.mockResolvedValue({ data: [{ id: 'existing-chat' }], error: null })
  mockFrom.mockReset().mockReturnValue(query)

  await expect(getOrCreateDirectChat('viewer', 'peer')).resolves.toBe('existing-chat')
  expect(mockFrom).toHaveBeenCalledTimes(1)
  expect(mockFrom).toHaveBeenCalledWith('chats')
  expect(query.upsert).not.toHaveBeenCalled()
})
