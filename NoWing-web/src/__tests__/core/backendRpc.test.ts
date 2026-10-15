import { backendRpc } from '../../core/backendRpc'
import { fallbackApiJson, readFallbackSession } from '../../core/auth/fallbackAuth'
import { supabase } from '../../core/supabase'

let mockPrimary = true
jest.mock('../../core/auth/fallbackAuth', () => ({
  get isFallbackAuthConfigured() { return mockPrimary },
  fallbackApiJson: jest.fn(), readFallbackSession: jest.fn(),
}))
jest.mock('../../core/supabase', () => ({ supabase: { rpc: jest.fn() } }))
const api = fallbackApiJson as jest.Mock
const session = readFallbackSession as jest.Mock
beforeEach(() => {
  mockPrimary = true
  api.mockReset().mockResolvedValue([{ id: 'poll-state' }])
  session.mockReset().mockReturnValue({ user: { id: 'owner' } })
  ;(supabase!.rpc as jest.Mock).mockReset().mockResolvedValue({ data: [], error: null })
})

test.each(['secure_create_chat_poll_message', 'secure_create_group_poll_message', 'secure_create_community_poll_message', 'secure_get_poll_state', 'secure_vote_poll_option'])('routes %s through the primary authenticated server', async name => {
  const result = await backendRpc(name, { p_poll_id: 'poll' })
  expect(api).toHaveBeenCalledWith('/rest/v1/rpc/' + name, { method: 'POST', body: '{"p_poll_id":"poll"}', expectedAccountId: 'owner' })
  expect(result).toEqual({ data: [{ id: 'poll-state' }], error: null })
  expect(supabase!.rpc).not.toHaveBeenCalled()
})

test('never falls back to another auth system after the primary rejects an operation', async () => {
  api.mockRejectedValue(new Error('Rejected'))
  expect((await backendRpc('secure_get_poll_state', {})).error).toBeInstanceOf(Error)
  expect(supabase!.rpc).not.toHaveBeenCalled()
})

test('rejects an absent account and invalid operation names before sending', async () => {
  session.mockReturnValue(null)
  expect((await backendRpc('secure_get_poll_state', {})).error).toBeInstanceOf(Error)
  expect((await backendRpc('../invalid', {})).error).toBeInstanceOf(Error)
  expect(api).not.toHaveBeenCalled()
})

test('retains the supported legacy transport when the primary is not configured', async () => {
  mockPrimary = false
  await backendRpc('secure_get_poll_state', { p_poll_id: 'poll' })
  expect(supabase!.rpc).toHaveBeenCalledWith('secure_get_poll_state', { p_poll_id: 'poll' })
  expect(api).not.toHaveBeenCalled()
})
