import { deleteFallbackChatMessage, deleteFallbackRoomMessage } from '../../core/fallbackData'
import { clearFallbackSession, persistFallbackSession } from '../../core/auth/fallbackAuth'
import { cancelPendingWebMessage } from '../../core/webOutbox'

jest.mock('../../core/runtimeBackend', () => ({ getRuntimeBackendUrl: () => 'https://api.example.invalid', refreshRuntimeBackend: jest.fn(), subscribeRuntimeBackend: jest.fn() }))
jest.mock('../../core/webOutbox', () => ({ cancelPendingWebMessage: jest.fn() }))
const token = 'test.' + btoa(JSON.stringify({ exp: 4102444800 })) + '.synthetic'
const session = { accessToken: token, user: { id: 'owner-a', email: 'test@example.invalid' }, expiresIn: 3600 }
const originalHeaders = globalThis.Headers, originalFetch = globalThis.fetch
const scopes = ['chat', 'group', 'community'] as const
function remove(scope: typeof scopes[number], forEveryone = true) {
  return scope === 'chat' ? deleteFallbackChatMessage('room/one', 'message/one', forEveryone) : deleteFallbackRoomMessage(scope, 'room/one', 'message/one', forEveryone)
}
beforeEach(() => {
  localStorage.clear(); clearFallbackSession()
  Object.assign(globalThis, { Headers: class { set() {} has() { return false } }, fetch: jest.fn().mockResolvedValue({ ok: true, status: 200, text: async () => '{"message":{"id":"message/one"}}' }) })
  jest.mocked(cancelPendingWebMessage).mockReset().mockResolvedValue(null)
  persistFallbackSession(session)
})
afterEach(() => { clearFallbackSession(); Object.assign(globalThis, { Headers: originalHeaders, fetch: originalFetch }) })

test.each(scopes)('%s delete cannot switch accounts after local pending lookup', async scope => {
  let finish!: (value: null) => void
  jest.mocked(cancelPendingWebMessage).mockImplementation(() => new Promise(resolve => { finish = resolve }))
  const pending = remove(scope)
  clearFallbackSession(); persistFallbackSession({ ...session, user: { ...session.user, id: 'owner-b' } })
  finish(null)
  await expect(pending).rejects.toThrow(/sesión|SESSION|ACCOUNT_CHANGED/)
  expect(fetch).not.toHaveBeenCalled()
})

test.each(scopes)('%s delete cannot use a renewed login while awaiting the token', async scope => {
  const pending = remove(scope)
  await Promise.resolve()
  clearFallbackSession(); persistFallbackSession(session)
  await expect(pending).rejects.toThrow(/sesión|SESSION|ACCOUNT_CHANGED/)
  expect(fetch).not.toHaveBeenCalled()
})

test.each(scopes)('%s delete cannot replay an old 401 under a new login', async scope => {
  let finish!: (response: Response) => void
  ;(fetch as jest.Mock).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const pending = remove(scope)
  await Promise.resolve(); await Promise.resolve()
  expect(fetch).toHaveBeenCalledTimes(1)
  clearFallbackSession(); persistFallbackSession(session)
  finish({ ok: false, status: 401, text: async () => '{"error":"INVALID_TOKEN"}' } as Response)
  await expect(pending).rejects.toThrow(/sesión|SESSION|ACCOUNT_CHANGED/)
  expect(fetch).toHaveBeenCalledTimes(1)
})

test.each(scopes)('%s local cancellation cannot return old content after identity renewal', async scope => {
  let finish!: (value: { id: string }) => void
  jest.mocked(cancelPendingWebMessage).mockImplementation(() => new Promise(resolve => { finish = resolve }))
  const pending = remove(scope)
  clearFallbackSession(); persistFallbackSession(session)
  finish({ id: 'old-local-message' })
  await expect(pending).rejects.toThrow(/sesión|SESSION|ACCOUNT_CHANGED/)
  expect(fetch).not.toHaveBeenCalled()
})

test.each(scopes)('%s current local cancellation needs no remote deletion', async scope => {
  jest.mocked(cancelPendingWebMessage).mockResolvedValue({ id: 'local-message' })
  await expect(remove(scope)).resolves.toEqual({ message: { id: 'local-message' } })
  expect(fetch).not.toHaveBeenCalled()
})

test.each(scopes.flatMap(scope => [true, false].map(forEveryone => [scope, forEveryone] as const)))('%s deletion preserves forEveryone=%s', async (scope, forEveryone) => {
  await expect(remove(scope, forEveryone)).resolves.toEqual({ message: { id: 'message/one' } })
  const collection = scope === 'chat' ? 'chats' : scope === 'group' ? 'groups' : 'communities'
  expect(fetch).toHaveBeenCalledWith(`https://api.example.invalid/v1/data/messages/${collection}/room%2Fone/messages/message%2Fone/delete`, expect.objectContaining({ method: 'POST', body: JSON.stringify({ forEveryone }) }))
})
