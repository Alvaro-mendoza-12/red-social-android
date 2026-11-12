import { invalidateStorageReadBackend, getStorageReadIdentity } from '../../core/media/storageReadSession'
import { backendRpc } from '../../core/backendRpc'
import { clearFallbackSession, persistFallbackSession } from '../../core/auth/fallbackAuth'
jest.mock('../../core/runtimeBackend', () => ({ getRuntimeBackendUrl: () => 'https://api.example.invalid', refreshRuntimeBackend: jest.fn(), subscribeRuntimeBackend: jest.fn() }))
jest.mock('../../core/supabase', () => ({ supabase: null }))
const token = 'test.' + btoa(JSON.stringify({ exp: 4102444800 })) + '.synthetic'
const session = { accessToken: token, user: { id: 'owner-a', email: 'test@example.invalid' }, expiresIn: 3600 }
const originalHeaders = globalThis.Headers, originalFetch = globalThis.fetch
beforeEach(() => {
  localStorage.clear(); clearFallbackSession()
  Object.assign(globalThis, { Headers: class { set() {} has() { return false } }, fetch: jest.fn().mockResolvedValue({ ok: true, status: 200, text: async () => '{}' }) })
  persistFallbackSession(session)
})
afterEach(() => { clearFallbackSession(); Object.assign(globalThis, { Headers: originalHeaders, fetch: originalFetch }) })
test('RPC must stop after login renewal while awaiting token', async () => {
  const pending = backendRpc('secure_vote_poll_option', { p_poll_id: 'poll-a', p_option_id: 'one', p_selected: true })
  clearFallbackSession(); persistFallbackSession(session)
  expect((await pending).error).toBeInstanceOf(Error)
  expect(fetch).not.toHaveBeenCalled()
})
test.each([200, 401])('RPC must reject late status %s after login renewal without replay', async status => {
  let finish!: (response: Response) => void
  ;(fetch as jest.Mock).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const pending = backendRpc('secure_get_poll_state', { p_poll_id: 'poll-a' })
  await Promise.resolve()
  expect(fetch).toHaveBeenCalledTimes(1)
  clearFallbackSession(); persistFallbackSession(session)
  finish({ ok: status === 200, status, text: async () => status === 200 ? '{}' : '{"error":"INVALID_TOKEN"}' } as Response)
  expect((await pending).error).toBeInstanceOf(Error)
  expect(fetch).toHaveBeenCalledTimes(1)
})


test('RPC stops when the backend identity changes during token lookup', async () => {
  const pending = backendRpc('secure_vote_poll_option', { p_poll_id: 'poll-a', p_option_id: 'one', p_selected: true })
  invalidateStorageReadBackend()
  expect((await pending).error).toBeInstanceOf(Error)
  expect(fetch).not.toHaveBeenCalled()
})

test.each([undefined, 'canonical-owner'])('current RPC succeeds with canonical profile %s', async supabaseUserId => {
  clearFallbackSession(); persistFallbackSession({ ...session, user: { ...session.user, supabaseUserId } })
  expect(getStorageReadIdentity().accountId).toBe(supabaseUserId || 'owner-a')
  await expect(backendRpc('secure_get_poll_state', { p_poll_id: 'poll-a' })).resolves.toEqual({ data: {}, error: null })
  expect(fetch).toHaveBeenCalledTimes(1)
  expect(fetch).toHaveBeenCalledWith('https://api.example.invalid/rest/v1/rpc/secure_get_poll_state', expect.objectContaining({ method: 'POST', body: '{"p_poll_id":"poll-a"}' }))
})
