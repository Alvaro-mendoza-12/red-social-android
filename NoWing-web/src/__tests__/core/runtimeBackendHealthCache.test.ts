/** @jest-environment node */
import { performance } from 'node:perf_hooks'
jest.mock('../../core/backendTrust', () => {
  let configuredOrigin = true
  return {
    canonicalBackendBaseUrl: (value: string | undefined) => {
      if (configuredOrigin) { configuredOrigin = false; return 'https://healthy.example.test' }
      return value || ''
    },
    isExplicitlyTrustedBackend: (value: string) => value === 'https://healthy.example.test',
    verifyBackendDiscoveryEnvelope: jest.fn(async () => null),
  }
})

const capabilities = ['anonymous_bottles_v1','community_music_v1','moment_music_v1','moment_music_aac_lc_v1',
  'image_moments_exact_6s_v1','video_sound_identity_v1','video_feed_session_v1','video_feed_live_v1']
const fetcher = jest.fn()
beforeEach(() => { jest.resetModules(); fetcher.mockReset(); global.fetch = fetcher })

test('a ready endpoint verified during startup is reused by a later guest startup', async () => {
  fetcher.mockResolvedValue({ ok: true, json: async () => ({ ok: true, capabilities }) })
  const backend = await import('../../core/runtimeBackend')
  await expect(backend.initializeRuntimeBackend()).resolves.toBe('https://healthy.example.test')
  await expect(backend.initializeRuntimeBackend()).resolves.toBe('https://healthy.example.test')
  expect(fetcher).toHaveBeenCalledTimes(1)
})

test('the short readiness reuse window expires and performs a fresh probe', async () => {
  const clock = jest.spyOn(performance, 'now').mockReturnValue(1000)
  try {
    fetcher.mockResolvedValue({ ok: true, json: async () => ({ ok: true, capabilities }) })
    const backend = await import('../../core/runtimeBackend')
    await backend.initializeRuntimeBackend()
    clock.mockReturnValue(17_000)
    await backend.initializeRuntimeBackend()
    expect(fetcher).toHaveBeenCalledTimes(2)
  } finally { clock.mockRestore() }
})

test('a failed readiness response is retried immediately rather than cached as success', async () => {
  fetcher.mockImplementation(async (url: string) => url.endsWith('/ready')
    ? { ok: false, json: async () => ({}) } : { ok: true, json: async () => ({}) })
  const backend = await import('../../core/runtimeBackend')
  await backend.initializeRuntimeBackend()
  await backend.initializeRuntimeBackend()
  expect(fetcher.mock.calls.filter(([url]) => url.endsWith('/ready'))).toHaveLength(2)
})

test('explicit endpoint recovery bypasses an earlier successful readiness probe', async () => {
  fetcher.mockResolvedValue({ ok: true, json: async () => ({ ok: true, capabilities }) })
  const backend = await import('../../core/runtimeBackend')
  await backend.initializeRuntimeBackend()
  fetcher.mockClear()
  fetcher.mockResolvedValue({ ok: true, json: async () => ({}) })
  await backend.refreshRuntimeBackend('https://healthy.example.test/v1/data')
  expect(fetcher).toHaveBeenCalled()
})
