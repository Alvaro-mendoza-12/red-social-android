import { clearFallbackSession, fallbackApiJson, persistFallbackSession } from '../../core/auth/fallbackAuth'
import { getStorageReadIdentity } from '../../core/media/storageReadSession'
jest.mock('../../core/runtimeBackend', () => ({ getRuntimeBackendUrl: () => 'https://api.example.invalid', refreshRuntimeBackend: jest.fn(), subscribeRuntimeBackend: jest.fn() }))
const token = 'test.' + btoa(JSON.stringify({ exp: 4102444800 })) + '.synthetic'
const session = { accessToken: token, user: { id: 'owner-a', email: 'test@example.invalid' }, expiresIn: 3600 }
const originalHeaders = globalThis.Headers, originalFetch = globalThis.fetch
beforeEach(() => {
  localStorage.clear(); clearFallbackSession()
  Object.assign(globalThis, { Headers: class { set() {} has() { return false } }, fetch: jest.fn().mockResolvedValue({ ok: true, status: 200, text: async () => '{}' }) })
  persistFallbackSession(session)
})
afterEach(() => { clearFallbackSession(); Object.assign(globalThis, { Headers: originalHeaders, fetch: originalFetch }) })
test.each(['/v1/storage/upload', '/v1/data/moments'])('does not send %s if the same account reauthenticates while awaiting its access token', async path => {
  const expectedIdentity = getStorageReadIdentity()
  const pending = fallbackApiJson(path, { method: 'POST', body: '{}', expectedAccountId: 'owner-a', expectedIdentity })
  clearFallbackSession(); persistFallbackSession(session)
  await expect(pending).rejects.toThrow(/sesión|SESSION/)
  expect(fetch).not.toHaveBeenCalled()
})
test('a current identity succeeds without sending the internal fence to fetch', async () => {
  await fallbackApiJson('/v1/data/moments', { method: 'POST', body: '{}', expectedAccountId: 'owner-a', expectedIdentity: getStorageReadIdentity() })
  expect(fetch).toHaveBeenCalledTimes(1)
  const options = (fetch as jest.Mock).mock.calls[0][1]
  expect(options).not.toHaveProperty('expectedIdentity'); expect(options).not.toHaveProperty('expectedAccountId')
})

test('a late expired-token response cannot refresh or clear a newer session', async () => {
  let finish!: (response: Response) => void
  ;(fetch as jest.Mock).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const pending = fallbackApiJson('/v1/data/moments', { method: 'POST', body: '{}', expectedAccountId: 'owner-a', expectedIdentity: getStorageReadIdentity() })
  await Promise.resolve(); expect(fetch).toHaveBeenCalledTimes(1)
  clearFallbackSession(); persistFallbackSession(session)
  const newerIdentity = getStorageReadIdentity()
  finish({ ok: false, status: 401, text: async () => JSON.stringify({ error: 'INVALID_TOKEN' }) } as Response)
  await expect(pending).rejects.toThrow(/sesión|SESSION/)
  expect(fetch).toHaveBeenCalledTimes(1)
  expect(getStorageReadIdentity()).toBe(newerIdentity)
})
