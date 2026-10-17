/** @jest-environment node */
import { ReadableStream } from 'node:stream/web'
import {
  readBoundedStorageBlob, isTrustedStorageReadUrl,
} from '../../core/media/boundedStorageRead'
import {
  getStorageReadIdentity, invalidateStorageReadBackend, invalidateStorageReadIdentity,
  onStorageReadInvalidated, requireStorageReadIdentity, subscribeStorageReadIdentity,
  updateStorageReadIdentity,
} from '../../core/media/storageReadSession'

const base = 'https://backend.test'
const url = `${base}/storage/v1/object/public/chat-media/a/image.png`
test.each([...Array.from({length:32},(_,index)=>index),127])('rejects control character %i in raw and decoded media paths',code=>{
  const char=String.fromCharCode(code),encoded='%'+code.toString(16).padStart(2,'0')
  expect(isTrustedStorageReadUrl(`${base}/storage/v1/object/public/chat-media/a/${char}file.png`,base)).toBe(false)
  expect(isTrustedStorageReadUrl(`${base}/storage/v1/object/public/chat-media/a/${encoded}file.png`,base)).toBe(false)
  updateStorageReadIdentity('account'+char+'id','fallback')
  expect(getStorageReadIdentity().accountId).toBe('')
})
const fetcher = jest.fn()
const token = 'synthetic.test.signature'

function response(chunks: Uint8Array[], headers: Record<string, string> = {}, other: Record<string, unknown> = {}) {
  return {
    ok: true, status: 200, redirected: false, type: 'basic', url,
    headers: new Headers({ 'Content-Type': 'image/png', ...headers }),
    body: new ReadableStream({ start(controller) { for (const chunk of chunks) controller.enqueue(chunk); controller.close() } }),
    ...other,
  }
}

function options(maxBytes = 100) {
  return { url, backendBase: base, identity: getStorageReadIdentity(), maxBytes,
    getAccessToken: jest.fn(async () => token), signal: new AbortController().signal, fetcher }
}

beforeEach(() => { jest.useRealTimers(); fetcher.mockReset(); invalidateStorageReadIdentity(); updateStorageReadIdentity('account-a', 'fallback') })
afterEach(() => { invalidateStorageReadIdentity(); jest.useRealTimers() })

describe('storage origin policy', () => {
  test.each([
    `${base}/storage/v1/object/public/chat-media/a.png`,
    `${base}/storage/v1/object/sign/chat-media/a.png?token=object-capability`,
    `${base}/storage/v1/object/chat-media/a.png`,
    `${base}:443/storage/v1/object/chat-media/a%20b.png`,
  ])('accepts exact HTTPS authority %s', (candidate) => expect(isTrustedStorageReadUrl(candidate, base)).toBe(true))

  test.each([
    'http://backend.test/storage/v1/object/chat-media/a.png',
    'https://backend.test.attacker.test/storage/v1/object/chat-media/a.png',
    'https://backend.test:444/storage/v1/object/chat-media/a.png',
    'https://user:password@backend.test/storage/v1/object/chat-media/a.png',
    `${base}/storage/v1/object/chat-media/../a.png`,
    `${base}/storage/v1/object/chat-media/%2e%2e/a.png`,
    `${base}/storage/v1/object/chat-media/a%2fb.png`,
    `${base}/storage/v1/object/chat-media/a%5cb.png`,
    `${base}/storage/v1/object/chat-media/a%00b.png`,
    `${base}/storage/v1/object/public/chat-media/`,
    `${base}/storage/v1/object/public/chat-media`,
    `${base}/storage/v1/object/chat-media/%xx`,
    `${base}/api/storage/v1/object/chat-media/a.png`,
    `${base}/storage/v1/object/chat-media\\a.png`,
  ])('rejects untrusted or ambiguous %s', (candidate) => expect(isTrustedStorageReadUrl(candidate, base)).toBe(false))

  test('supports a configured base path only', () => {
    expect(isTrustedStorageReadUrl(`${base}/api/storage/v1/object/chat-media/a.png`, `${base}/api`)).toBe(true)
    expect(isTrustedStorageReadUrl(url, `${base}/api`)).toBe(false)
    expect(isTrustedStorageReadUrl(url, `${base}?redirect=attacker`)).toBe(false)
  })
})

describe('account and generation', () => {
  test('keeps the same identity during ordinary token renewal', () => {
    const first = getStorageReadIdentity(); updateStorageReadIdentity('account-a', 'fallback')
    expect(getStorageReadIdentity()).toBe(first)
  })
  test('invalidates A to B to A generations', () => {
    const first = getStorageReadIdentity(); updateStorageReadIdentity('account-b', 'fallback'); updateStorageReadIdentity('account-a', 'fallback')
    expect(() => requireStorageReadIdentity(first)).toThrow('MEDIA_SESSION_CHANGED')
  })
  test('invalidates same account after logout', () => {
    const first = getStorageReadIdentity(); invalidateStorageReadIdentity(); updateStorageReadIdentity('account-a', 'fallback')
    expect(() => requireStorageReadIdentity(first)).toThrow('MEDIA_SESSION_CHANGED')
  })
  test('does not clear Supabase identity when clearing fallback only', () => {
    updateStorageReadIdentity('account-b', 'supabase'); const first = getStorageReadIdentity(); invalidateStorageReadIdentity('fallback')
    expect(getStorageReadIdentity()).toBe(first)
  })
  test('invalidates synchronously before notification', () => {
    const events: string[] = []; const removeInvalidate = onStorageReadInvalidated(() => events.push('cleanup')); const removeListener = subscribeStorageReadIdentity(() => events.push('render'))
    invalidateStorageReadIdentity(); removeInvalidate(); removeListener(); expect(events).toEqual(['cleanup', 'render'])
  })
  test('backend changes retain account but invalidate generation', () => {
    const first = getStorageReadIdentity(); invalidateStorageReadBackend(); expect(getStorageReadIdentity().accountId).toBe('account-a'); expect(getStorageReadIdentity()).not.toBe(first)
  })
  test.each(['bad id', 'bad\naccount', 'a'.repeat(257)])('rejects invalid account', (accountId) => {
    updateStorageReadIdentity(accountId, 'fallback'); expect(getStorageReadIdentity().accountId).toBe('')
  })
})

describe('bounded authorized streaming', () => {
  test('uses current Bearer and safe fetch options, returning only verified bytes', async () => {
    fetcher.mockResolvedValue(response([new Uint8Array([1, 2, 3])]))
    const blob = await readBoundedStorageBlob(options())
    expect(blob.size).toBe(3); expect(blob.type).toBe('image/png')
    const init = fetcher.mock.calls[0][1]
    expect(init.headers.get('Authorization')).toBe(`Bearer ${token}`)
    expect(init).toMatchObject({ method: 'GET', redirect: 'error', cache: 'no-store', credentials: 'omit', referrerPolicy: 'no-referrer' })
  })
  test('does not send a token or request to an external origin', async () => {
    const input = options(); input.url = 'https://attacker.test/storage/v1/object/chat-media/a.png'
    await expect(readBoundedStorageBlob(input)).rejects.toThrow('MEDIA_UNTRUSTED_ORIGIN'); expect(input.getAccessToken).not.toHaveBeenCalled(); expect(fetcher).not.toHaveBeenCalled()
  })
  test('caps actual streamed length when no Content-Length exists', async () => {
    fetcher.mockResolvedValue(response([new Uint8Array(60), new Uint8Array(41)]))
    await expect(readBoundedStorageBlob(options(100))).rejects.toThrow('MEDIA_TOO_LARGE')
  })
  test('rejects advertised excess before reading', async () => {
    fetcher.mockResolvedValue(response([new Uint8Array([1])], { 'Content-Length': '101' }))
    await expect(readBoundedStorageBlob(options(100))).rejects.toThrow('MEDIA_TOO_LARGE')
  })
  test('checks bytes even when an incorrect Content-Length is smaller', async () => {
    fetcher.mockResolvedValue(response([new Uint8Array(101)], { 'Content-Length': '1' }))
    await expect(readBoundedStorageBlob(options(100))).rejects.toThrow('MEDIA_TOO_LARGE')
  })
  test('allows exact maximum', async () => {
    fetcher.mockResolvedValue(response([new Uint8Array(100)])); expect((await readBoundedStorageBlob(options(100))).size).toBe(100)
  })
  test.each([401, 403, 404, 429, 503])('denied status %d has no Blob or public fallback', async (status) => {
    fetcher.mockResolvedValue(response([new Uint8Array([1])], {}, { ok: false, status }))
    await expect(readBoundedStorageBlob(options())).rejects.toThrow(`MEDIA_READ_${status}`); expect(fetcher).toHaveBeenCalledTimes(1)
  })
  test('rejects 206 because this transport is a complete small-object read', async () => {
    fetcher.mockResolvedValue(response([new Uint8Array([1])], {}, { status: 206 })); await expect(readBoundedStorageBlob(options())).rejects.toThrow('MEDIA_READ_206')
  })
  test('rejects unexpected redirects despite transport mock', async () => {
    fetcher.mockResolvedValue(response([new Uint8Array([1])], {}, { redirected: true })); await expect(readBoundedStorageBlob(options())).rejects.toThrow('MEDIA_READ_200')
  })
  test('rejects a response from a second authority', async () => {
    fetcher.mockResolvedValue(response([new Uint8Array([1])], {}, { url: 'https://attacker.test/a' })); await expect(readBoundedStorageBlob(options())).rejects.toThrow('MEDIA_REDIRECT_DENIED')
  })
  test('rejects empty result', async () => {
    fetcher.mockResolvedValue(response([])); await expect(readBoundedStorageBlob(options())).rejects.toThrow('MEDIA_EMPTY')
  })
  test('does not request anonymously when a credential is missing', async () => {
    const input = options(); input.getAccessToken.mockResolvedValue(''); await expect(readBoundedStorageBlob(input)).rejects.toThrow('MEDIA_SESSION_REQUIRED'); expect(fetcher).not.toHaveBeenCalled()
  })
  test('aborts a pending credential on logout before any request', async () => {
    const input = options(); let complete!: (value: string) => void; input.getAccessToken.mockImplementation(() => new Promise((resolve) => { complete = resolve }))
    const job = readBoundedStorageBlob(input); await Promise.resolve(); invalidateStorageReadIdentity(); complete(token)
    await expect(job).rejects.toThrow(); expect(fetcher).not.toHaveBeenCalled()
  })
  test('does not deliver a chunk when account changes inside read()', async () => {
    const read = jest.fn(async () => { updateStorageReadIdentity('account-b', 'fallback'); return { value: new Uint8Array([1, 2]), done: false } })
    fetcher.mockResolvedValue(response([], {}, { body: { getReader: () => ({ read, cancel: jest.fn(async () => {}), releaseLock: jest.fn() }) } }))
    await expect(readBoundedStorageBlob(options())).rejects.toThrow(); expect(read).toHaveBeenCalledTimes(1)
  })
  test('cancels stalled transfer after 30 seconds', async () => {
    jest.useFakeTimers(); fetcher.mockResolvedValue(response([], {}, { body: { getReader: () => ({ read: () => new Promise(() => {}), cancel: jest.fn(async () => {}), releaseLock: jest.fn() }) } }))
    const job = readBoundedStorageBlob(options()); const expectation = expect(job).rejects.toThrow(); await jest.advanceTimersByTimeAsync(30_001); await expectation
  })
  test('cancels stalled credentials after eight seconds', async () => {
    jest.useFakeTimers(); const input = options(); input.getAccessToken.mockImplementation(() => new Promise(() => {}))
    const job = readBoundedStorageBlob(input); const expectation = expect(job).rejects.toThrow(); await jest.advanceTimersByTimeAsync(8_001); await expectation; expect(fetcher).not.toHaveBeenCalled()
  })
  test('rejects unknown/unbounded limits before a request', async () => {
    await expect(readBoundedStorageBlob(options(Number.POSITIVE_INFINITY))).rejects.toThrow('MEDIA_INVALID_LIMIT'); expect(fetcher).not.toHaveBeenCalled()
  })
  test('caps simultaneous preparations at four and queued reads at 24', async () => {
    const inputs = Array.from({ length: 28 }, () => {
      const input = options(); input.getAccessToken.mockImplementation(() => new Promise(() => {})); return input
    })
    const jobs = inputs.map((input) => readBoundedStorageBlob(input))
    const settled = Promise.allSettled(jobs)
    await Promise.resolve()
    expect(inputs.reduce((sum, input) => sum + input.getAccessToken.mock.calls.length, 0)).toBe(4)
    await expect(readBoundedStorageBlob(options())).rejects.toThrow('MEDIA_READ_QUEUE_FULL')
    invalidateStorageReadIdentity(); expect((await settled).every((result) => result.status === 'rejected')).toBe(true)
    expect(fetcher).not.toHaveBeenCalled()
  })
  test('cancelling a queued read releases its queue capacity', async () => {
    const inputs = Array.from({ length: 28 }, () => {
      const controller = new AbortController(); const input = { ...options(), signal: controller.signal }
      input.getAccessToken.mockImplementation(() => new Promise(() => {})); return { controller, input }
    })
    const jobs = inputs.map(({ input }) => readBoundedStorageBlob(input)); const settled = Promise.allSettled(jobs)
    await Promise.resolve(); inputs[27].controller.abort(); await Promise.resolve()
    const replacement = options(); replacement.getAccessToken.mockImplementation(() => new Promise(() => {}))
    const replacementJob = readBoundedStorageBlob(replacement); const replacementSettled = Promise.allSettled([replacementJob])
    invalidateStorageReadIdentity(); await settled
    const result = (await replacementSettled)[0]
    expect(result.status).toBe('rejected')
    if (result.status === 'rejected') expect(String(result.reason)).not.toContain('MEDIA_READ_QUEUE_FULL')
  })
})
