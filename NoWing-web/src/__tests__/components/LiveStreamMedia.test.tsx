import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { LiveStreamAudio, LiveStreamVideo } from '../../components/LiveStreamMedia'
import { useLiveVideoAvailable } from '../../core/media/useLiveVideoAvailable'
jest.mock('../../components/liveStreamMedia.css', () => ({}))

class Track extends EventTarget {
  kind = 'video'; readyState = 'live'; enabled = true; muted = false
}
class Stream extends EventTarget {
  tracks: Track[] = []
  getVideoTracks() { return this.tracks }
  add(track: Track) { this.tracks.push(track); this.dispatchEvent(new Event('addtrack')) }
  remove(track: Track) { this.tracks = this.tracks.filter(value => value !== track); this.dispatchEvent(new Event('removetrack')) }
}
const asStream = (value: Stream) => value as unknown as MediaStream
const play = jest.spyOn(HTMLMediaElement.prototype, 'play')
const pause = jest.spyOn(HTMLMediaElement.prototype, 'pause')
beforeEach(() => { play.mockReset().mockResolvedValue(); pause.mockReset().mockImplementation(() => {}) })
afterAll(() => { play.mockRestore(); pause.mockRestore(); delete (HTMLMediaElement.prototype as unknown as Record<string, unknown>).setSinkId })

function VideoStatus({ stream }: { stream: MediaStream }) { return <span>{useLiveVideoAvailable(stream) ? 'visible' : 'oculto'}</span> }

test('reveals a camera arriving on the existing audio stream and hides a removed or ended track', () => {
  const stream = new Stream(), track = new Track()
  render(<VideoStatus stream={asStream(stream)} />)
  expect(screen.getByText('oculto')).toBeInTheDocument()
  act(() => stream.add(track)); expect(screen.getByText('visible')).toBeInTheDocument()
  act(() => { track.muted = true; track.dispatchEvent(new Event('mute')) }); expect(screen.getByText('visible')).toBeInTheDocument()
  act(() => { track.muted = false; track.dispatchEvent(new Event('unmute')) }); expect(screen.getByText('visible')).toBeInTheDocument()
  act(() => { track.readyState = 'ended'; track.dispatchEvent(new Event('ended')) }); expect(screen.getByText('oculto')).toBeInTheDocument()
  act(() => { stream.remove(track); track.readyState = 'live'; track.dispatchEvent(new Event('unmute')) }); expect(screen.getByText('oculto')).toBeInTheDocument()
})

test('attaches a stream already available before the video mounts and clears it on unmount', async () => {
  const stream = asStream(new Stream())
  const { unmount } = render(<LiveStreamVideo stream={stream} label="Cámara de Camila" />)
  const video = screen.getByLabelText('Cámara de Camila') as HTMLVideoElement
  await waitFor(() => expect(play).toHaveBeenCalled())
  expect(video.srcObject).toBe(stream)
  unmount(); expect(video.srcObject).toBeNull(); expect(pause).toHaveBeenCalled()
})

test('keeps the video attached when muting or choosing another output', async () => {
  const stream = asStream(new Stream())
  const { rerender } = render(<LiveStreamVideo stream={stream} label="Pantalla" muted />)
  const video = screen.getByLabelText('Pantalla') as HTMLVideoElement
  await waitFor(() => expect(play).toHaveBeenCalled())
  rerender(<LiveStreamVideo stream={stream} label="Pantalla" outputDeviceId="headset" />)
  expect(video.srcObject).toBe(stream)
  expect(pause).not.toHaveBeenCalled()
})

test('does not request an output route for muted previews or an untouched default output', async () => {
  const sink = jest.fn().mockRejectedValue(new DOMException('Policy blocked', 'NotAllowedError'))
  Object.defineProperty(HTMLMediaElement.prototype, 'setSinkId', { configurable: true, value: sink })
  const { unmount } = render(<LiveStreamVideo stream={asStream(new Stream())} label="Tu pantalla" muted />)
  await waitFor(() => expect(play).toHaveBeenCalled())
  expect(sink).not.toHaveBeenCalled(); expect(screen.queryByRole('status')).toBeNull()
  unmount()
  render(<LiveStreamAudio stream={asStream(new Stream())} label="Sala" />)
  expect(sink).not.toHaveBeenCalled()
})

test('offers an explicit audio action after autoplay rejection and removes it after a user gesture', async () => {
  play.mockRejectedValueOnce(new DOMException('Autoplay blocked', 'NotAllowedError'))
  render(<LiveStreamAudio stream={asStream(new Stream())} label="Camila" />)
  fireEvent.click(await screen.findByRole('button', { name: 'Escuchar a Camila' }))
  await waitFor(() => expect(screen.queryByRole('button', { name: 'Escuchar a Camila' })).toBeNull())
})

test('ignores a late autoplay rejection after switching the stream', async () => {
  let reject!: (error: Error) => void
  play.mockImplementationOnce(() => new Promise<void>((_, fail) => { reject = fail }))
  const { rerender } = render(<LiveStreamVideo stream={asStream(new Stream())} label="Llamada" />)
  rerender(<LiveStreamVideo stream={asStream(new Stream())} label="Llamada" />)
  await act(async () => reject(new DOMException('Blocked', 'NotAllowedError')))
  expect(screen.queryByRole('button')).toBeNull()
})

test('applies a selected audio output and recovers when another output is chosen', async () => {
  const sink = jest.fn().mockRejectedValueOnce(new Error('Unavailable')).mockResolvedValue(undefined)
  Object.defineProperty(HTMLMediaElement.prototype, 'setSinkId', { configurable: true, value: sink })
  const stream = asStream(new Stream()), { rerender } = render(<LiveStreamAudio stream={stream} label="Camila" outputDeviceId="headset" />)
  await screen.findByRole('status');expect(sink).toHaveBeenCalledWith('headset')
  rerender(<LiveStreamAudio stream={stream} label="Camila" outputDeviceId="" />)
  await waitFor(() => expect(screen.queryByRole('status')).toBeNull());expect(sink).toHaveBeenLastCalledWith('')
})
