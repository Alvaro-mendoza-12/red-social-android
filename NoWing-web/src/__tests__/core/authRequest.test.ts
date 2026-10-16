import { authenticationConnectionMessage, authenticationTimeoutMs, fetchAuthentication } from '../../core/auth/authRequest'

const originalFetch = global.fetch
beforeEach(() => { jest.useFakeTimers(); global.fetch = jest.fn() })
afterEach(() => { jest.useRealTimers(); global.fetch = originalFetch })

test('an unanswered login stops after the deadline and is never automatically replayed', async () => {
  const mocked = global.fetch as jest.Mock
  mocked.mockImplementation(() => new Promise(() => undefined))
  const request = fetchAuthentication('https://api.example.test/v1/auth/login', { method: 'POST', body: 'synthetic' })
  const rejected = expect(request).rejects.toThrow(authenticationConnectionMessage)
  await jest.advanceTimersByTimeAsync(authenticationTimeoutMs)
  await rejected
  expect(mocked).toHaveBeenCalledTimes(1)
  expect(mocked.mock.calls[0][1].signal.aborted).toBe(true)
  expect(jest.getTimerCount()).toBe(0)
})

test('a successful response is preserved and clears the deadline', async () => {
  const response = { ok: true, status: 201 } as Response
  ;(global.fetch as jest.Mock).mockResolvedValue(response)
  await expect(fetchAuthentication('https://api.example.test/v1/auth/web-login-pairing', { method: 'POST' })).resolves.toBe(response)
  expect(jest.getTimerCount()).toBe(0)
})

test('HTTP errors are returned without a credential or QR replay', async () => {
  const response = { ok: false, status: 503 } as Response
  ;(global.fetch as jest.Mock).mockResolvedValue(response)
  await expect(fetchAuthentication('https://api.example.test/v1/auth/login', { method: 'POST' })).resolves.toBe(response)
  expect(global.fetch).toHaveBeenCalledTimes(1)
  expect(jest.getTimerCount()).toBe(0)
})

test('caller cancellation stops the request without changing its meaning', async () => {
  ;(global.fetch as jest.Mock).mockImplementation(() => new Promise(() => undefined))
  const parent = new AbortController()
  const request = fetchAuthentication('https://api.example.test/v1/auth/login', { method: 'POST', signal: parent.signal })
  const cancelled = expect(request).rejects.toMatchObject({ name: 'AbortError' })
  parent.abort()
  await cancelled
  expect(jest.getTimerCount()).toBe(0)
})

test('a request cancelled before sending never reaches fetch', async () => {
  const parent = new AbortController()
  parent.abort()
  await expect(fetchAuthentication('https://api.example.test/v1/auth/login', { method: 'POST', signal: parent.signal })).rejects.toMatchObject({ name: 'AbortError' })
  expect(global.fetch).not.toHaveBeenCalled()
  expect(jest.getTimerCount()).toBe(0)
})

test('connection errors do not mention infrastructure or blame the entered password', () => {
  expect(authenticationConnectionMessage).toContain('Novvin')
  expect(authenticationConnectionMessage).not.toMatch(/Ubuntu|Tailscale|contraseña|https?:/i)
})
