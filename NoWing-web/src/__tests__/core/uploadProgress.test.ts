import { uploadWithProgress } from '../../core/uploadProgress'

class TestResponse {
  constructor(readonly body: string | null, readonly options: ResponseInit) {}
  get status() { return this.options.status }
  get headers() { return new Headers(this.options.headers) }
  async json() { return JSON.parse(this.body || '') }
}
class FakeXHR {
  static last: FakeXHR
  upload = { onprogress: null as null | ((event: object) => void), onload: null as null | ((event: object) => void) }
  onload?: () => void; onerror?: () => void; onabort?: () => void; ontimeout?: () => void
  status = 200; statusText = 'OK'; responseText = '{"ok":true}'; withCredentials = false
  open = jest.fn(); send = jest.fn(); setRequestHeader = jest.fn()
  abort = jest.fn(() => this.onabort?.())
  getAllResponseHeaders() { return 'Content-Type: application/json\r\nX-Result: checked\r\n' }
  constructor() { FakeXHR.last = this }
}
const originalXHR = globalThis.XMLHttpRequest, originalResponse = globalThis.Response
beforeEach(() => { Object.assign(globalThis, { XMLHttpRequest: FakeXHR, Response: TestResponse }) })
afterAll(() => { Object.assign(globalThis, { XMLHttpRequest: originalXHR, Response: originalResponse }) })

test('sends the authenticated form unchanged and reports real bytes before verification', async () => {
  const form = new FormData(), progress = jest.fn()
  const pending = uploadWithProgress('https://api.invalid/upload', { method: 'POST', body: form, credentials: 'include', headers: { Authorization: 'Bearer inert-test' } }, progress)
  const xhr = FakeXHR.last
  expect(xhr.open).toHaveBeenCalledWith('POST', 'https://api.invalid/upload')
  expect(xhr.send).toHaveBeenCalledWith(form)
  expect(xhr.withCredentials).toBe(true)
  expect(xhr.setRequestHeader).toHaveBeenCalledWith('authorization', 'Bearer inert-test')
  xhr.upload.onprogress?.({ loaded: 40, total: 100, lengthComputable: true })
  xhr.upload.onload?.({ loaded: 100, total: 100 })
  expect(progress.mock.calls.map(call => call[0])).toEqual([{ phase: 'uploading', loaded: 40, total: 100 }, { phase: 'verifying', loaded: 100, total: 100 }])
  xhr.onload?.()
  const response = await pending
  expect(await response.json()).toEqual({ ok: true })
  expect(response.headers.get('x-result')).toBe('checked')
})
test('abort retains the timeout reason and cancels the network operation', async () => {
  const controller = new AbortController(), reason = new DOMException('deadline', 'TimeoutError')
  const pending = uploadWithProgress('/upload', { signal: controller.signal }, jest.fn())
  controller.abort(reason)
  await expect(pending).rejects.toBe(reason)
  expect(FakeXHR.last.abort).toHaveBeenCalledTimes(1)
})
test('a previously aborted request never starts', async () => {
  const controller = new AbortController(); controller.abort()
  await expect(uploadWithProgress('/upload', { signal: controller.signal }, jest.fn())).rejects.toHaveProperty('name', 'AbortError')
  expect(FakeXHR.last.open).not.toHaveBeenCalled()
})
test('HTTP errors resolve for the shared authentication/error handler; network errors reject', async () => {
  const first = uploadWithProgress('/upload', {}, jest.fn()); FakeXHR.last.status = 401; FakeXHR.last.onload?.()
  expect((await first).status).toBe(401)
  const second = uploadWithProgress('/upload', {}, jest.fn()); FakeXHR.last.onerror?.()
  await expect(second).rejects.toThrow('Network request failed')
})
test('empty successful responses remain valid', async () => {
  const pending = uploadWithProgress('/upload', {}, jest.fn()); FakeXHR.last.status = 204; FakeXHR.last.onload?.()
  expect((await pending).status).toBe(204)
})
