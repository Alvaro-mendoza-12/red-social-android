jest.mock('../../core/runtimeBackend', () => ({ rewriteRuntimeStorageUrl: (url: string) => url }))
jest.mock('../../core/media/storageReadClient', () => ({ trustedStorageBackend: () => 'https://private.test', prepareStorageReadAccessToken: jest.fn(async () => 'synthetic-media-token-never-production') }))

const media = 'https://private.test/storage/v1/object/public/videos/test.mp4'
class WorkerStub extends EventTarget {
  state = 'activated'
  registrations: string[] = []
  postMessage = jest.fn((data, ports) => {
    if (data.type !== 'NOVVIN_MEDIA_REGISTER') return
    this.registrations.push(data.id)
    ports[0].peer.onmessage?.({ data: { ok: true } })
  })
}
class ChannelStub {
  port1 = { close: jest.fn(), onmessage: null as null | ((event: unknown) => void) }
  port2 = { peer: this.port1 }
}
let container: EventTarget & { controller: WorkerStub; register: jest.Mock }
beforeEach(() => {
  jest.resetModules()
  container = Object.assign(new EventTarget(), { controller: new WorkerStub(), register: jest.fn(async () => ({})) })
  Object.defineProperty(navigator, 'serviceWorker', { configurable: true, value: container })
  Object.defineProperty(globalThis, 'MessageChannel', { configurable: true, value: ChannelStub })
  Object.defineProperty(crypto, 'randomUUID', { configurable: true, value: () => 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' })
})

test('an open page registers new sources on the current controller after an update', async () => {
  const {updateStorageReadIdentity} = await import('../../core/media/storageReadSession')
  updateStorageReadIdentity('owned-account', 'fallback')
  const {browserStreamingSource} = await import('../../core/media/browserStreaming')
  const first = container.controller
  await browserStreamingSource(media)
  container.controller = new WorkerStub(); first.state = 'redundant'
  container.dispatchEvent(new Event('controllerchange'))
  await browserStreamingSource(media + '?next=1')
  expect(first.registrations).toHaveLength(1)
  expect(container.controller.registrations).toHaveLength(1)
})

test('an update during registration resumes on the replacement controller', async () => {
  const {updateStorageReadIdentity} = await import('../../core/media/storageReadSession')
  updateStorageReadIdentity('owned-account', 'fallback')
  const {browserStreamingSource} = await import('../../core/media/browserStreaming')
  const first=container.controller
  first.postMessage.mockImplementationOnce(() => {
    container.controller = new WorkerStub(); first.state = 'redundant'
    first.dispatchEvent(new Event('statechange'))
  })
  await expect(browserStreamingSource(media)).resolves.toMatch(/^\/__novvin_media\//)
  expect(container.controller.registrations).toHaveLength(1)
})

test('an identity switch while waiting for credentials cannot register old media', async () => {
  const {updateStorageReadIdentity} = await import('../../core/media/storageReadSession')
  updateStorageReadIdentity('account-a', 'fallback')
  const {prepareStorageReadAccessToken}=await import('../../core/media/storageReadClient')
  ;(prepareStorageReadAccessToken as jest.Mock).mockImplementationOnce(async()=>{updateStorageReadIdentity('account-b','fallback');return 'old-token'})
  const {browserStreamingSource}=await import('../../core/media/browserStreaming')
  await expect(browserStreamingSource(media)).rejects.toThrow('MEDIA_SESSION_CHANGED')
  expect(container.controller.registrations).toHaveLength(0)
})

test('remounted and concurrent avatars share one registration on the same controller', async () => {
  const {updateStorageReadIdentity} = await import('../../core/media/storageReadSession')
  updateStorageReadIdentity('owned-account', 'fallback')
  const {browserStreamingSource} = await import('../../core/media/browserStreaming')
  const sources = await Promise.all([browserStreamingSource(media), browserStreamingSource(media), browserStreamingSource(media)])
  expect(new Set(sources).size).toBe(1)
  await browserStreamingSource(media)
  expect(container.controller.registrations).toHaveLength(1)
})

test('an authorized avatar has a synchronous handle on remount and cannot cross accounts', async () => {
  const { updateStorageReadIdentity } = await import('../../core/media/storageReadSession')
  updateStorageReadIdentity('owned-account', 'fallback')
  const { browserStreamingSource, cachedBrowserStreamingSource } = await import('../../core/media/browserStreaming')
  expect(cachedBrowserStreamingSource(media)).toBeUndefined()
  const source = await browserStreamingSource(media)
  expect(cachedBrowserStreamingSource(media)).toBe(source)
  updateStorageReadIdentity('other-account', 'fallback')
  expect(cachedBrowserStreamingSource(media)).toBeUndefined()
})
