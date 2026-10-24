import { readCachedStorageImage } from '../../core/media/imagePreviewCache'
import { readBoundedStorageBlob } from '../../core/media/boundedStorageRead'
import { getStorageReadIdentity, invalidateStorageReadIdentity, updateStorageReadIdentity } from '../../core/media/storageReadSession'

jest.mock('../../core/media/boundedStorageRead', () => ({ readBoundedStorageBlob: jest.fn() }))
const read = readBoundedStorageBlob as jest.MockedFunction<typeof readBoundedStorageBlob>
const options = () => ({ url: 'https://private.test/storage/v1/object/public/chat-media/a/sticker.webp', backendBase: 'https://private.test', identity: getStorageReadIdentity(), maxBytes: 16 * 1024 * 1024, getAccessToken: async () => 'synthetic-token', signal: new AbortController().signal })
beforeEach(() => { read.mockReset(); updateStorageReadIdentity('account-a', 'fallback') })
afterEach(() => { invalidateStorageReadIdentity(); jest.restoreAllMocks() })

test('reopening an image reuses its exact bytes and retry explicitly refreshes them', async () => {
  const blob = new Blob(['animated-webp'], { type: 'image/webp' })
  read.mockResolvedValue(blob)
  expect(await readCachedStorageImage(options())).toBe(blob)
  expect(await readCachedStorageImage(options())).toBe(blob)
  expect(read).toHaveBeenCalledTimes(1)
  await readCachedStorageImage(options(), true)
  expect(read).toHaveBeenCalledTimes(2)
})

test('a different account cannot reuse a preview or complete an old read', async () => {
  read.mockResolvedValue(new Blob(['a'], { type: 'image/png' }))
  const previous = options()
  await readCachedStorageImage(previous)
  updateStorageReadIdentity('account-b', 'fallback')
  await expect(readCachedStorageImage(previous)).rejects.toThrow('MEDIA_SESSION_CHANGED')
  await readCachedStorageImage(options())
  expect(read).toHaveBeenCalledTimes(2)
})

test('expired images and non-image payloads are downloaded again', async () => {
  const now = jest.spyOn(Date, 'now').mockReturnValue(1000)
  read.mockResolvedValue(new Blob(['a'], { type: 'image/png' }))
  await readCachedStorageImage(options())
  now.mockReturnValue(31 * 60 * 1000)
  await readCachedStorageImage(options())
  expect(read).toHaveBeenCalledTimes(2)
  read.mockResolvedValue(new Blob(['<html>'], { type: 'text/html' }))
  await readCachedStorageImage(options(), true)
  await readCachedStorageImage(options())
  expect(read).toHaveBeenCalledTimes(4)
})
