import { localAudioDuration } from '../../features/moments/localMomentAudio'

beforeEach(() => {
  Object.assign(URL, { createObjectURL: jest.fn(() => 'blob:audio'), revokeObjectURL: jest.fn() })
  jest.spyOn(HTMLMediaElement.prototype, 'load').mockImplementation(() => {})
})
afterEach(() => jest.restoreAllMocks())

test('cancelled local audio releases its object URL immediately', async () => {
  const controller = new AbortController()
  const pending = localAudioDuration(new File(['audio'], 'song.mp3', { type: 'audio/mpeg' }), controller.signal)
  controller.abort()
  await expect(pending).rejects.toMatchObject({ name: 'AbortError' })
  expect(URL.revokeObjectURL).toHaveBeenCalledTimes(1)
})

test('an already cancelled selection does not keep media loading', async () => {
  const controller = new AbortController(); controller.abort()
  await expect(localAudioDuration(new File(['audio'], 'song.mp3'), controller.signal)).rejects.toMatchObject({ name: 'AbortError' })
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:audio')
})
