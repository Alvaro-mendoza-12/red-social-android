import { deleteFallbackChatMessage, deleteFallbackRoomMessage } from '../../core/fallbackData'
import { clearFallbackSession, persistFallbackSession } from '../../core/auth/fallbackAuth'
jest.mock('../../core/runtimeBackend', () => ({ getRuntimeBackendUrl: () => 'https://api.example.invalid', refreshRuntimeBackend: jest.fn(), subscribeRuntimeBackend: jest.fn() }))
// Executes the real outbox against a minimal IndexedDB transaction adapter.
let mockPending: Record<string, unknown> | undefined
let mockDeleted: string[] = []
let mockReads = 0
let mockRenewAfterRead = 0
type Request = { result: unknown; onsuccess?: () => void }
const originalIndexedDB = globalThis.indexedDB
beforeAll(() => Object.defineProperty(globalThis, 'indexedDB', { configurable: true, value: {
  open() {
    const database = { close() {}, transaction(storeName: string) {
      const tx: { oncomplete?: () => void; objectStore: () => { get: () => Request; delete: (id: string) => Request } } = {
        objectStore() { return {
          get() { return request(storeName === 'messages' ? mockPending : undefined, ++mockReads) },
          delete(id: string) { mockDeleted.push(id); if (storeName === 'messages') mockPending = undefined; return request(undefined) },
        } },
      }
      function request(value: unknown, readNumber = 0): Request {
        const read: Request = { result: value }
        queueMicrotask(() => {
          read.onsuccess?.(); tx.oncomplete?.()
          if (readNumber > 0 && readNumber === mockRenewAfterRead) queueMicrotask(() => { clearFallbackSession(); persistFallbackSession(session) })
        })
        return read
      }
      return tx
    } }
    const request: { result: typeof database; onsuccess?: () => void } = { result: database }
    queueMicrotask(() => request.onsuccess?.())
    return request
  },
} }))
afterAll(() => Object.defineProperty(globalThis, 'indexedDB', { configurable: true, value: originalIndexedDB }))
const token = 'test.' + btoa(JSON.stringify({ exp: 4102444800 })) + '.synthetic'
const session = { accessToken: token, user: { id: 'owner-a', email: 'test@example.invalid' }, expiresIn: 3600 }
const originalHeaders = globalThis.Headers, originalFetch = globalThis.fetch
beforeEach(() => {
  mockPending = undefined; mockDeleted = []; mockReads = 0; mockRenewAfterRead = 0
  localStorage.clear(); clearFallbackSession()
  Object.assign(globalThis, { Headers: class { set() {} has() { return false } }, fetch: jest.fn().mockResolvedValue({ ok: true, status: 200, text: async () => '{"message":{"id":"message-a"}}' }) })
  persistFallbackSession(session)
})
afterEach(() => { clearFallbackSession(); Object.assign(globalThis, { Headers: originalHeaders, fetch: originalFetch }) })
test.each(['chat', 'group', 'community'] as const)('%s delete must not cross a renewed login during real outbox lookup', async scope => {
  const pending = scope === 'chat' ? deleteFallbackChatMessage('room', 'message-a', true) : deleteFallbackRoomMessage(scope, 'room', 'message-a', true)
  clearFallbackSession(); persistFallbackSession(session)
  await expect(pending).rejects.toThrow(/sesión|SESSION|ACCOUNT_CHANGED/)
  expect(fetch).not.toHaveBeenCalled()
})


test.each(['chat', 'group', 'community'] as const)('%s pending message is not removed after renewing its login during lookup', async scope => {
  mockPending = { id: 'message-a', owner: 'owner-a', scope, room: 'room', payload: { content: 'Privado' }, createdAt: '2026-11-11T00:00:00Z', senderId: 'owner-a', state: 'pending', attempts: 0, retryAt: 0 }
  const pending = scope === 'chat' ? deleteFallbackChatMessage('room', 'message-a', false) : deleteFallbackRoomMessage(scope, 'room', 'message-a', false)
  clearFallbackSession(); persistFallbackSession(session)
  await expect(pending).rejects.toThrow(/sesión|SESSION|ACCOUNT_CHANGED/)
  expect(mockDeleted).toEqual([])
  expect(fetch).not.toHaveBeenCalled()
})


test.each(['chat', 'group', 'community'] as const)('%s cancellation checks identity after database wait before delete transaction', async scope => {
  mockPending = { id: 'message-a', owner: 'owner-a', scope, room: 'room', payload: { content: 'Privado' }, createdAt: '2026-11-11T00:00:00Z', senderId: 'owner-a', state: 'pending', attempts: 0, retryAt: 0 }
  mockRenewAfterRead = 2
  const pending = scope === 'chat' ? deleteFallbackChatMessage('room', 'message-a', false) : deleteFallbackRoomMessage(scope, 'room', 'message-a', false)
  await expect(pending).rejects.toThrow(/sesión|SESSION|ACCOUNT_CHANGED/)
  expect(mockReads).toBe(2)
  expect(mockDeleted).toEqual([])
  expect(fetch).not.toHaveBeenCalled()
})

test.each(['chat', 'group', 'community'] as const)('%s current pending cancellation removes the message and notifies once', async scope => {
  mockPending = { id: 'message-a', owner: 'owner-a', scope, room: 'room', payload: { content: 'Privado' }, createdAt: '2026-11-11T00:00:00Z', senderId: 'owner-a', state: 'pending', attempts: 0, retryAt: 0 }
  const changed = jest.fn()
  window.addEventListener('novvin:outbox-changed', changed)
  try {
    const pending = scope === 'chat' ? deleteFallbackChatMessage('room', 'message-a', false) : deleteFallbackRoomMessage(scope, 'room', 'message-a', false)
    await expect(pending).resolves.toEqual({ message: expect.objectContaining({ id: 'message-a', status: 'cancelled', content: '' }) })
    expect(mockDeleted).toEqual(['message-a'])
    expect(changed).toHaveBeenCalledTimes(1)
    expect(fetch).not.toHaveBeenCalled()
  } finally { window.removeEventListener('novvin:outbox-changed', changed) }
})
