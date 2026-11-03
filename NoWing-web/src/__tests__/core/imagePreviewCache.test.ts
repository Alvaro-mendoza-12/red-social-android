import { readCachedStorageImage, readCachedAvatarVideo } from '../../core/media/imagePreviewCache'
import { readBoundedStorageBlob } from '../../core/media/boundedStorageRead'
import { getStorageReadIdentity, invalidateStorageReadIdentity, updateStorageReadIdentity } from '../../core/media/storageReadSession'

jest.mock('../../core/media/boundedStorageRead', () => ({ readBoundedStorageBlob: jest.fn() }))
const read = readBoundedStorageBlob as jest.MockedFunction<typeof readBoundedStorageBlob>
const options = () => ({ url: 'https://private.test/storage/v1/object/public/chat-media/a/sticker.webp', backendBase: 'https://private.test', identity: getStorageReadIdentity(), maxBytes: 16 * 1024 * 1024, getAccessToken: async () => 'synthetic-token', signal: new AbortController().signal })
beforeEach(() => { read.mockReset(); updateStorageReadIdentity('account-a', 'fallback') })
afterEach(() => { invalidateStorageReadIdentity(); jest.restoreAllMocks() })

test('short avatar videos share their session cache and cannot populate the image cache', async () => {
  const blob = new Blob(['short-avatar-video'], { type: 'video/mp4' })
  read.mockResolvedValue(blob)
  expect(await readCachedAvatarVideo(options())).toBe(blob)
  expect(await readCachedAvatarVideo(options())).toBe(blob)
  expect(read).toHaveBeenCalledTimes(1)
  await readCachedStorageImage(options())
  await readCachedStorageImage(options())
  expect(read).toHaveBeenCalledTimes(3)
  updateStorageReadIdentity('account-b', 'fallback')
  await readCachedAvatarVideo(options())
  expect(read).toHaveBeenCalledTimes(4)
})

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

test('concurrent bubbles share a transfer and cancelling one does not cancel the other', async () => {
  let complete!: (blob: Blob) => void
  read.mockImplementationOnce(() => new Promise(resolve => { complete = resolve }))
  const firstController = new AbortController()
  const firstOptions = { ...options(), signal: firstController.signal }, secondOptions = options()
  const first = readCachedStorageImage(firstOptions)
  const second = readCachedStorageImage(secondOptions)
  expect(read).toHaveBeenCalledTimes(1)
  const firstRejected = expect(first).rejects.toMatchObject({ name: 'AbortError' })
  // Use the original caller's signal, not the shared transfer's signal.
  const transferSignal = read.mock.calls[0][0].signal
  expect(transferSignal).not.toBe(firstOptions.signal)
  firstController.abort()
  await firstRejected
  expect(transferSignal.aborted).toBe(false)
  const blob = new Blob(['one-download'], { type: 'image/png' })
  complete(blob)
  expect(await second).toBe(blob)
  expect(await readCachedStorageImage(options())).toBe(blob)
  expect(read).toHaveBeenCalledTimes(1)
})

test('unmounting every consumer aborts the transfer and a later mount starts a fresh one', async () => {
  read.mockImplementationOnce(() => new Promise(() => {}))
  const controller = new AbortController()
  const first = readCachedStorageImage({ ...options(), signal: controller.signal })
  const rejected = expect(first).rejects.toMatchObject({ name: 'AbortError' })
  const transferSignal = read.mock.calls[0][0].signal
  controller.abort()
  await rejected
  expect(transferSignal.aborted).toBe(true)
  const blob = new Blob(['fresh-read'], { type: 'image/png' })
  read.mockResolvedValueOnce(blob)
  expect(await readCachedStorageImage(options())).toBe(blob)
  expect(read).toHaveBeenCalledTimes(2)
})
