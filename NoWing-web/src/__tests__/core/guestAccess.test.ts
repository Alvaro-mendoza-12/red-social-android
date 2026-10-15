/** @jest-environment node */
import { guestStorageToken, loadGuestContent, startGuestBrowsing, stopGuestBrowsing } from '../../core/auth/guestAccess'
import { getStorageReadIdentity, invalidateStorageReadBackend, updateStorageReadIdentity } from '../../core/media/storageReadSession'

let mockBackend = 'https://backend.test'
jest.mock('../../core/runtimeBackend', () => ({ getRuntimeBackendUrl: () => mockBackend }))
const id = 'guest_00000000-0000-4000-8000-000000000001', content = '00000000-0000-4000-8000-000000000002'
const token = 'synthetic.guest.signature', fetcher = jest.fn()
const session = () => ({ role: 'guest', id, accessToken: token, expiresIn: 3600 })
const response = (body: unknown, status = 200) => ({ ok: status < 400, status, json: async () => body })
beforeEach(() => { stopGuestBrowsing(); updateStorageReadIdentity('', ''); mockBackend = 'https://backend.test'; fetcher.mockReset(); global.fetch = fetcher })
afterEach(() => { stopGuestBrowsing(); updateStorageReadIdentity('', ''); jest.useRealTimers() })

test('obtains a separate guest identity and a read-only feed without cookies', async () => {
  fetcher.mockResolvedValueOnce(response(session(), 201)).mockResolvedValueOnce(response({ items: [{ id: content }] }))
  expect(await loadGuestContent('videos')).toEqual([{ id: content }])
  expect(getStorageReadIdentity()).toMatchObject({ accountId: id, provider: 'guest' })
  expect(fetcher).toHaveBeenNthCalledWith(1, 'https://backend.test/v1/guest/session', expect.objectContaining({ method: 'POST', credentials: 'omit', redirect: 'error' }))
  expect(fetcher).toHaveBeenNthCalledWith(2, 'https://backend.test/v1/guest/videos', expect.objectContaining({ method: 'GET', credentials: 'omit', headers: { Accept: 'application/json', Authorization: `Bearer ${token}` } }))
})

test('shares one in-flight bootstrap and keeps the bearer out of URLs', async () => {
  fetcher.mockResolvedValueOnce(response(session(), 201))
  await Promise.all([startGuestBrowsing(), startGuestBrowsing()])
  expect(fetcher).toHaveBeenCalledTimes(1)
  expect(guestStorageToken(getStorageReadIdentity())).toBe(token)
  expect(fetcher.mock.calls[0][0]).not.toContain(token)
})

test('does not replace an authenticated account', async () => {
  updateStorageReadIdentity('owned-account', 'fallback')
  await expect(startGuestBrowsing()).rejects.toThrow('GUEST_ACCOUNT_CHANGED')
  expect(fetcher).not.toHaveBeenCalled()
  expect(getStorageReadIdentity().accountId).toBe('owned-account')
})

test('late guest bootstrap cannot replace a new authenticated identity', async () => {
  let finish!: (value: unknown) => void
  fetcher.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
  const operation = startGuestBrowsing()
  updateStorageReadIdentity('owned-account', 'fallback')
  finish(response(session(), 201))
  await expect(operation).rejects.toThrow('GUEST_ACCOUNT_CHANGED')
  expect(getStorageReadIdentity().accountId).toBe('owned-account')
})

test('late content is discarded after the account changes', async () => {
  let finish!: (value: unknown) => void
  fetcher.mockResolvedValueOnce(response(session(), 201)).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  await startGuestBrowsing()
  const operation = loadGuestContent('posts')
  await Promise.resolve(); await Promise.resolve()
  updateStorageReadIdentity('owned-account', 'fallback')
  finish(response({ items: [{ id: content }] }))
  await expect(operation).rejects.toThrow('MEDIA_SESSION_CHANGED')
})

test('leaving guest browsing invalidates media credentials', async () => {
  fetcher.mockResolvedValueOnce(response(session(), 201)); await startGuestBrowsing()
  const identity = getStorageReadIdentity()
  stopGuestBrowsing()
  expect(() => guestStorageToken(identity)).toThrow('MEDIA_SESSION_CHANGED')
  expect(getStorageReadIdentity().provider).toBe('')
})

test('guest cleanup cannot sign out a newly authenticated account', () => {
  updateStorageReadIdentity('owned-account', 'fallback'); stopGuestBrowsing()
  expect(getStorageReadIdentity().accountId).toBe('owned-account')
})

test.each(['../messages', 'profiles/secret', 'chats'])('rejects a non-public route %s before any request', async kind => {
  await expect(loadGuestContent(kind as 'videos')).rejects.toThrow('GUEST_CONTENT_INVALID')
  expect(fetcher).not.toHaveBeenCalled()
})

test('rejects an invalid identifier before any request', async () => {
  await expect(loadGuestContent('posts', '../../chat')).rejects.toThrow('GUEST_CONTENT_INVALID')
  await expect(loadGuestContent('profiles')).rejects.toThrow('GUEST_CONTENT_INVALID')
  expect(fetcher).not.toHaveBeenCalled()
})

test('reads only the sanitized public profile endpoint', async () => {
  fetcher.mockResolvedValueOnce(response(session(), 201)).mockResolvedValueOnce(response({ profile: { id: content, display_name: 'Public name' } }))
  expect(await loadGuestContent('profiles', content)).toEqual([{ id: content, display_name: 'Public name' }])
  expect(fetcher.mock.calls[1][0]).toBe(`https://backend.test/v1/guest/profiles/${content}`)
})

test('renews a rejected guest bearer once and retries the public read', async () => {
  fetcher.mockResolvedValueOnce(response(session(), 201)).mockResolvedValueOnce(response({}, 401))
    .mockResolvedValueOnce(response({ ...session(), id: id.replace(/1$/, '3') }, 201)).mockResolvedValueOnce(response({ items: [] }))
  expect(await loadGuestContent('posts')).toEqual([])
  expect(fetcher).toHaveBeenCalledTimes(4)
  expect(getStorageReadIdentity().accountId).toBe(id.replace(/1$/, '3'))
})

test('does not repeatedly create sessions on server failure', async () => {
  fetcher.mockResolvedValueOnce(response(session(), 201)).mockResolvedValueOnce(response({ message: 'private diagnostics' }, 503))
  await expect(loadGuestContent('posts')).rejects.toThrow('GUEST_READ_503')
  expect(fetcher).toHaveBeenCalledTimes(2)
})

test('rejects malformed session responses', async () => {
  fetcher.mockResolvedValueOnce(response({ ...session(), role: 'admin' }, 201))
  await expect(startGuestBrowsing()).rejects.toThrow('GUEST_SESSION_INVALID')
  expect(getStorageReadIdentity().accountId).toBe('')
})

test('stopped in-flight bootstrap cannot reactivate guest media', async () => {
  let finish!: (value: unknown) => void
  fetcher.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
  const operation = startGuestBrowsing(); stopGuestBrowsing(); finish(response(session(), 201))
  await expect(operation).rejects.toThrow('GUEST_ACCOUNT_CHANGED')
  expect(getStorageReadIdentity().accountId).toBe('')
})

test('trusted backend discovery refreshes guest media without losing its identity', async () => {
  fetcher.mockResolvedValueOnce(response(session(), 201)).mockResolvedValueOnce(response({ items: [] }))
  await startGuestBrowsing(); const old = getStorageReadIdentity()
  mockBackend = 'https://trusted-endpoint.test'; invalidateStorageReadBackend()
  expect(getStorageReadIdentity()).not.toBe(old)
  expect(guestStorageToken(getStorageReadIdentity())).toBe(token)
  expect(await loadGuestContent('posts')).toEqual([])
  expect(fetcher).toHaveBeenCalledTimes(2)
  expect(fetcher.mock.calls[1][0]).toBe('https://trusted-endpoint.test/v1/guest/posts')
})

test('trusted discovery during bootstrap does not leave a guest feed without media credentials', async () => {
  let finish!: (value: unknown) => void
  fetcher.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
  const operation = startGuestBrowsing()
  mockBackend = 'https://trusted-endpoint.test'; invalidateStorageReadBackend()
  finish(response(session(), 201)); await operation
  expect(guestStorageToken(getStorageReadIdentity())).toBe(token)
})
