import { hideFallbackChat, saveFallbackChatPreferences, toggleFallbackChatReaction } from '../../core/fallbackData'
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


test('hiding a chat cannot send under a different account after token lookup', async () => {
  const pending = hideFallbackChat('shared-chat')
  clearFallbackSession(); persistFallbackSession({ ...session, user: { ...session.user, id: 'owner-b' } })
  await expect(pending).rejects.toThrow(/sesión|SESSION|ACCOUNT_CHANGED/)
  expect(fetch).not.toHaveBeenCalled()
})

test('hiding a chat cannot replay a stale 401 after switching accounts', async () => {
  let finish!: (response: Response) => void
  ;(fetch as jest.Mock).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const pending = hideFallbackChat('shared-chat')
  await Promise.resolve(); expect(fetch).toHaveBeenCalledTimes(1)
  clearFallbackSession(); persistFallbackSession({ ...session, user: { ...session.user, id: 'owner-b' } })
  finish({ ok: false, status: 401, text: async () => JSON.stringify({ error: 'INVALID_TOKEN' }) } as Response)
  await expect(pending).rejects.toThrow(/sesión|SESSION|ACCOUNT_CHANGED/)
  expect(fetch).toHaveBeenCalledTimes(1)
})

test('a hide response from an old login does not notify the new inbox', async () => {
  let finish!: (response: Response) => void
  ;(fetch as jest.Mock).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const listener = jest.fn()
  window.addEventListener('novvin:inbox-changed', listener)
  try {
    const pending = hideFallbackChat('shared-chat')
    await Promise.resolve()
    clearFallbackSession(); persistFallbackSession(session)
    finish({ ok: true, status: 200, text: async () => '{"ok":true}' } as Response)
    await expect(pending).rejects.toThrow(/sesión|SESSION/)
    expect(listener).not.toHaveBeenCalled()
  } finally { window.removeEventListener('novvin:inbox-changed', listener) }
})

test('a current hide uses DELETE and notifies its inbox after success', async () => {
  const listener = jest.fn()
  window.addEventListener('novvin:inbox-changed', listener)
  try {
    await hideFallbackChat('chat/one')
    expect(fetch).toHaveBeenCalledWith('https://api.example.invalid/v1/data/messages/chats/chat%2Fone', expect.objectContaining({ method: 'DELETE' }))
    expect(listener).toHaveBeenCalledTimes(1)
  } finally { window.removeEventListener('novvin:inbox-changed', listener) }
})


test('chat preference save cannot switch accounts while awaiting its token', async () => {
  const pending = saveFallbackChatPreferences('shared-chat', { isMuted: true })
  clearFallbackSession(); persistFallbackSession({ ...session, user: { ...session.user, id: 'owner-b' } })
  await expect(pending).rejects.toThrow(/sesión|SESSION|ACCOUNT_CHANGED/)
  expect(fetch).not.toHaveBeenCalled()
})

test('chat preference save cannot replay an old 401 under a new login', async () => {
  let finish!: (response: Response) => void
  ;(fetch as jest.Mock).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const pending = saveFallbackChatPreferences('shared-chat', { isArchived: true })
  await Promise.resolve(); expect(fetch).toHaveBeenCalledTimes(1)
  clearFallbackSession(); persistFallbackSession(session)
  finish({ ok: false, status: 401, text: async () => JSON.stringify({ error: 'INVALID_TOKEN' }) } as Response)
  await expect(pending).rejects.toThrow(/sesión|SESSION|ACCOUNT_CHANGED/)
  expect(fetch).toHaveBeenCalledTimes(1)
})

test('a late chat preference response does not notify a new session', async () => {
  let finish!: (response: Response) => void
  ;(fetch as jest.Mock).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const listener = jest.fn()
  window.addEventListener('novvin:conversation-preferences-changed', listener)
  try {
    const pending = saveFallbackChatPreferences('shared-chat', { isPinned: true })
    await Promise.resolve()
    clearFallbackSession(); persistFallbackSession(session)
    finish({ ok: true, status: 200, text: async () => '{"ok":true}' } as Response)
    await expect(pending).rejects.toThrow(/sesión|SESSION/)
    expect(listener).not.toHaveBeenCalled()
  } finally { window.removeEventListener('novvin:conversation-preferences-changed', listener) }
})


test('a private chat reaction cannot switch accounts during token lookup', async () => {
  const pending = toggleFallbackChatReaction('shared-chat', 'message-a', '👍')
  clearFallbackSession(); persistFallbackSession({ ...session, user: { ...session.user, id: 'owner-b' } })
  await expect(pending).rejects.toThrow(/sesión|SESSION|ACCOUNT_CHANGED/)
  expect(fetch).not.toHaveBeenCalled()
})

test('a private chat reaction cannot replay a 401 under a new login', async () => {
  let finish!: (response: Response) => void
  ;(fetch as jest.Mock).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const pending = toggleFallbackChatReaction('shared-chat', 'message-a', '👍')
  await Promise.resolve(); expect(fetch).toHaveBeenCalledTimes(1)
  clearFallbackSession(); persistFallbackSession(session)
  finish({ ok: false, status: 401, text: async () => JSON.stringify({ error: 'INVALID_TOKEN' }) } as Response)
  await expect(pending).rejects.toThrow(/sesión|SESSION|ACCOUNT_CHANGED/)
  expect(fetch).toHaveBeenCalledTimes(1)
})

test('a current private chat reaction keeps its route and payload', async () => {
  await toggleFallbackChatReaction('chat/one', 'message/one', '👍')
  expect(fetch).toHaveBeenCalledWith('https://api.example.invalid/v1/data/messages/chats/chat%2Fone/messages/message%2Fone/reactions', expect.objectContaining({ method: 'POST', body: JSON.stringify({ emoji: '👍' }) }))
})
