import { captureVideoFrameDataUrl, readVideoDurationSeconds } from '../../../features/reels/reelFilePreparation'

const file = new File(['test'], 'clip.webm', { type: 'video/webm' })
const createUrl = jest.fn(() => 'blob:video-fixture')
const revokeUrl = jest.fn()
let media: HTMLVideoElement
const originals = [Object.getOwnPropertyDescriptor(URL, 'createObjectURL'), Object.getOwnPropertyDescriptor(URL, 'revokeObjectURL')]

beforeEach(() => {
  jest.useFakeTimers()
  createUrl.mockClear()
  revokeUrl.mockClear()
  Object.defineProperty(URL, 'createObjectURL', { value: createUrl, configurable: true })
  Object.defineProperty(URL, 'revokeObjectURL', { value: revokeUrl, configurable: true })
  jest.spyOn(HTMLMediaElement.prototype, 'load').mockImplementation(function (this: HTMLVideoElement) { media = this })
})

afterEach(() => {
  jest.restoreAllMocks()
  jest.useRealTimers()
  for (const [index, name] of ['createObjectURL', 'revokeObjectURL'].entries()) {
    const descriptor = originals[index]
    if (descriptor) Object.defineProperty(URL, name, descriptor)
    else Reflect.deleteProperty(URL, name)
  }
})

test('reading duration returns zero and releases the object URL if no media events arrive', async () => {
  const duration = readVideoDurationSeconds(file)
  jest.advanceTimersByTime(12000)
  await expect(duration).resolves.toBe(0)
  expect(revokeUrl).toHaveBeenCalledTimes(1)
  expect(media.hasAttribute('src')).toBe(false)
  expect(media.onloadedmetadata).toBeNull()
})

test('a poster at zero seconds uses the first loaded frame and does not wait for seeked', async () => {
  const drawImage = jest.fn()
  jest.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ drawImage } as unknown as CanvasRenderingContext2D)
  jest.spyOn(HTMLCanvasElement.prototype, 'toDataURL').mockReturnValue('data:image/jpeg;base64,YWJj')
  const capture = captureVideoFrameDataUrl(file, 0, 180)
  Object.defineProperties(media, { readyState: { value: 2 }, videoWidth: { value: 320 }, videoHeight: { value: 480 } })
  media.dispatchEvent(new Event('loadedmetadata'))
  media.dispatchEvent(new Event('loadeddata'))
  await expect(capture).resolves.toBe('data:image/jpeg;base64,YWJj')
  expect(drawImage).toHaveBeenCalledWith(media, 0, 0, 180, 270)
  expect(revokeUrl).toHaveBeenCalledTimes(1)
})

test('a media frame request rejects after a bounded wait and releases the URL', async () => {
  const capture = captureVideoFrameDataUrl(file, 4)
  const result = expect(capture).rejects.toThrow('El video tardó demasiado en cargarse.')
  jest.advanceTimersByTime(12000)
  await result
  expect(revokeUrl).toHaveBeenCalledTimes(1)
  expect(media.onseeked).toBeNull()
})
