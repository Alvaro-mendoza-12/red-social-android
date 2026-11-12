/** @jest-environment node */
import { initializeRuntimeBackend, getRuntimeBackendUrl } from '../../core/runtimeBackend'

const fetcher = jest.fn()
const emptyConfig = () => ({ ok: true, json: async () => ({}) })
beforeEach(() => { fetcher.mockReset(); global.fetch = fetcher })

test('concurrent startup consumers share the same in-flight discovery', async () => {
  let finish!: (value: unknown) => void
  let requested!: () => void
  const started = new Promise<void>(resolve => { requested = resolve })
  fetcher.mockImplementation(() => { requested(); return new Promise(resolve => { finish = resolve }) })
  const first = initializeRuntimeBackend(), second = initializeRuntimeBackend()
  await started
  finish(emptyConfig())
  // Both callers must complete from this one request rather than an abandoned duplicate.
  expect(fetcher).toHaveBeenCalledTimes(1)
  await expect(Promise.all([first, second])).resolves.toEqual(['', ''])
})

test('completed unsuccessful discovery may be attempted again without trusting an arbitrary origin', async () => {
  fetcher.mockResolvedValue({ ok: true, json: async () => ({ backendUrl: 'https://untrusted.example' }) })
  await expect(initializeRuntimeBackend()).resolves.toBe('')
  await expect(initializeRuntimeBackend()).resolves.toBe('')
  expect(getRuntimeBackendUrl()).toBe('')
  expect(fetcher.mock.calls.map(([url]) => url)).toEqual(['/backend-config.json', '/backend-config.json'])
})
