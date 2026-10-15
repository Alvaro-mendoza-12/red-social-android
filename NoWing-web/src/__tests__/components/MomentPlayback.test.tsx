import { act, fireEvent, render, screen } from '@testing-library/react'
import { MomentPlayback } from '../../features/moments/MomentPlayback'
import type { MomentItem } from '../../features/moments/momentsData'
jest.mock('../../features/moments/momentComposer.css', () => ({}))
jest.mock('../../core/auth/fallbackAuth', () => ({ fallbackApiJson: jest.fn() }))
jest.mock('../../components/StreamingMedia', () => { const React = jest.requireActual('react'); return { StreamingAudio: React.forwardRef((p: object, ref: unknown) => React.createElement('audio', { ...p, ref })), StreamingVideo: React.forwardRef((p: object, ref: unknown) => React.createElement('video', { ...p, ref })), StreamingImage: (p: object) => React.createElement('img', p) } })
const photo: MomentItem = { id: 'story', userId: 'owner', ownerName: 'Persona', mediaUrl: 'https://media.test/photo.png', mediaType: 'image', caption: '', visibility: 'all', allowReplies: true, createdAt: '', expiresAt: '', viewsCount: 0, viewedByMe: false, viewerPreview: [] }
let play: jest.SpyInstance, pause: jest.SpyInstance
beforeEach(() => { jest.useFakeTimers(); play = jest.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(); pause = jest.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => {}) })
afterEach(() => { jest.useRealTimers(); jest.restoreAllMocks() })
test('photo playback lasts six seconds and completes only once', () => {
  const done = jest.fn(), progress = jest.fn(); const { container } = render(<MomentPlayback moment={photo} paused={false} muted onProgress={progress} onComplete={done} />)
  fireEvent.load(container.querySelector('img')!); act(() => jest.advanceTimersByTime(5900)); expect(done).not.toHaveBeenCalled()
  act(() => jest.advanceTimersByTime(300)); expect(done).toHaveBeenCalledTimes(1)
})
test('pausing a photo freezes its remaining time', () => {
  const props = { moment: photo, paused: false, muted: true, onProgress: jest.fn(), onComplete: jest.fn() }
  const { container, rerender } = render(<MomentPlayback {...props} />); fireEvent.load(container.querySelector('img')!)
  act(() => jest.advanceTimersByTime(2000)); rerender(<MomentPlayback {...props} paused />)
  act(() => jest.advanceTimersByTime(10000)); expect(props.onComplete).not.toHaveBeenCalled()
  rerender(<MomentPlayback {...props} />); act(() => jest.advanceTimersByTime(4000)); expect(props.onComplete).toHaveBeenCalledTimes(1)
})
test('pause and buffering pause the actual video, not just progress', async () => {
  const props = { moment: { ...photo, mediaType: 'video', displayDurationMs: 18000 }, paused: false, muted: true, onProgress: jest.fn(), onComplete: jest.fn() }
  const { container, rerender } = render(<MomentPlayback {...props} />), video = container.querySelector('video')!
  await act(async () => fireEvent.canPlay(video)); expect(play).toHaveBeenCalled()
  pause.mockClear(); rerender(<MomentPlayback {...props} paused />); expect(pause).toHaveBeenCalled()
  rerender(<MomentPlayback {...props} />); pause.mockClear(); fireEvent.waiting(video); expect(pause).toHaveBeenCalled()
  video.currentTime = 3; await act(async () => fireEvent.canPlay(video)); act(() => jest.advanceTimersByTime(50)); expect(props.onProgress).toHaveBeenLastCalledWith(3000 / 18000 * 100)
})
test('separate sound starts at the returned clip coordinate and pauses with the moment', async () => {
  const props = { moment: { ...photo, music: { audioUrl: 'https://media.test/rendered.m4a', title: 'Canción', artist: 'Persona', startMs: 0, durationMs: 6000, embedded: false, volume: .8, originalVolume: 1, fadeInMs: 1000, fadeOutMs: 1000, duckOriginal: false, presentation: 'card' as const } }, paused: false, muted: false, onProgress: jest.fn(), onComplete: jest.fn() }
  const { container, rerender } = render(<MomentPlayback {...props} />), audio = container.querySelector('audio')!
  fireEvent.load(container.querySelector('img')!); await act(async () => fireEvent.canPlay(audio))
  act(() => jest.advanceTimersByTime(500)); expect(audio.currentTime).toBeGreaterThanOrEqual(.15); expect(audio.currentTime).toBeLessThanOrEqual(.5); expect(audio.volume).toBeCloseTo(.4)
  pause.mockClear(); rerender(<MomentPlayback {...props} paused />); expect(pause).toHaveBeenCalled()
})
test('embedded native sound is not played or attenuated a second time', async () => {
  const moment = { ...photo, mediaType: 'video', music: { audioUrl: 'https://media.test/source.mp3', title: 'Canción', artist: '', startMs: 120000, durationMs: 6000, embedded: true, volume: .2, originalVolume: .1, fadeInMs: 0, fadeOutMs: 0, duckOriginal: true, presentation: 'audio' as const } }
  const { container } = render(<MomentPlayback moment={moment} paused={false} muted={false} onProgress={() => {}} onComplete={() => {}} />)
  expect(container.querySelector('audio')).toBeNull(); await act(async () => fireEvent.canPlay(container.querySelector('video')!)); expect(container.querySelector('video')!.volume).toBe(1)
})
test('failed media exposes a retry and never advances invisibly', () => {
  const done = jest.fn(); const { container } = render(<MomentPlayback moment={photo} paused={false} muted onProgress={() => {}} onComplete={done} />)
  fireEvent.error(container.querySelector('img')!); expect(screen.getByRole('button', { name: 'Reintentar' })).toBeInTheDocument()
  act(() => jest.advanceTimersByTime(30000)); expect(done).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'Reintentar' })); fireEvent.load(container.querySelector('img')!); act(() => jest.advanceTimersByTime(6000)); expect(done).toHaveBeenCalledTimes(1)
})
test('unmount stops active media', async () => {
  const { container, unmount } = render(<MomentPlayback moment={{ ...photo, mediaType: 'video' }} paused={false} muted onProgress={() => {}} onComplete={() => {}} />)
  await act(async () => fireEvent.canPlay(container.querySelector('video')!)); pause.mockClear(); unmount(); expect(pause).toHaveBeenCalled()
})
test('a video stalled after starting offers retry rather than an endless spinner', async () => {
  const { container } = render(<MomentPlayback moment={{ ...photo, mediaType: 'video' }} paused={false} muted onProgress={() => {}} onComplete={() => {}} />)
  await act(async () => fireEvent.canPlay(container.querySelector('video')!)); fireEvent.waiting(container.querySelector('video')!)
  act(() => jest.advanceTimersByTime(20000)); expect(screen.getByRole('button', { name: 'Reintentar' })).toBeInTheDocument()
})
test('autoplay rejection can be resumed with a user gesture', async () => {
  play.mockRejectedValueOnce(new DOMException('Gesture required', 'NotAllowedError'))
  const { container } = render(<MomentPlayback moment={{ ...photo, mediaType: 'video' }} paused={false} muted onProgress={() => {}} onComplete={() => {}} />)
  await act(async () => fireEvent.canPlay(container.querySelector('video')!))
  expect(screen.getByRole('button', { name: 'Reproducir' })).toBeInTheDocument()
  await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Reproducir' })))
  expect(screen.queryByRole('button', { name: 'Reproducir' })).not.toBeInTheDocument()
})
