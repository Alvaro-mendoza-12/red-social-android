import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import { mapMomentOverlay, mapMomentSound, momentDuration, momentFileError, musicGain, musicPublishFields, musicRange, searchMomentMusic, type MomentMusicSelection } from '../../features/moments/momentMusic'
jest.mock('../../core/auth/fallbackAuth', () => ({ fallbackApiJson: jest.fn() }))
const api = fallbackApiJson as jest.Mock
const selection: MomentMusicSelection = { track: { id: 'community:track', title: 'Pista', artist: 'Artista', audioUrl: 'https://media.test/full.mp3', durationMs: 180000, source: 'novvin_community' }, startMs: 120000, volume: .7, originalVolume: .5, fadeInMs: 1000, fadeOutMs: 1000, duckOriginal: true, presentation: 'card' }
beforeEach(() => api.mockReset())
test('the file picker matches the existing server format and size policy', () => {
  expect(momentFileError({ type: 'video/mp4', size: 35 * 1024 * 1024 })).toBe('')
  expect(momentFileError({ type: 'video/webm', size: 1000 })).toContain('MP4')
  expect(momentFileError({ type: 'image/gif', size: 1000 })).toContain('WEBP')
  expect(momentFileError({ type: 'video/mp4', size: 35 * 1024 * 1024 + 1 })).toContain('35 MB')
})
test('photos use six seconds and videos use their duration up to twenty-five seconds', () => {
  expect(momentDuration('image', 20000)).toBe(6000)
  expect(momentDuration('video', 12000)).toBe(12000)
  expect(momentDuration('video', 90000)).toBe(25000)
  expect(momentDuration('video', NaN)).toBe(25000)
})
test('a six-second clip can begin two minutes into the full song', () => {
  expect(musicRange(180000, 6000, 120000)).toEqual({ startMs: 120000, endMs: 126000, durationMs: 6000, maxStartMs: 174000 })
})
test('seeking is bounded by source duration rather than clip duration', () => {
  expect(musicRange(180000, 6000, 200000).startMs).toBe(174000)
  expect(musicRange(180000, 6000, -20).startMs).toBe(0)
  expect(musicRange(NaN, NaN, NaN)).toEqual({ startMs: 0, endMs: 1000, durationMs: 1000, maxStartMs: 0 })
})
test('publication retains song coordinates and declares separate audio', () => {
  expect(musicPublishFields(selection, 6000)).toMatchObject({ musicStartMs: 120000, musicEndMs: 126000, musicDurationMs: 6000, musicAudioUrl: selection.track.audioUrl, musicEmbedded: false, musicVolume: .7, originalAudioVolume: .5, duckOriginalAudio: true })
  expect(musicPublishFields(undefined, 6000)).toEqual({})
})
test('device audio uses its uploaded source and sanitizes gains and fades', () => {
  expect(musicPublishFields({ ...selection, volume: NaN, originalVolume: -1, fadeInMs: Infinity, fadeOutMs: 9000 }, 6000, 'https://media.test/owned.m4a')).toMatchObject({ musicAudioUrl: 'https://media.test/owned.m4a', musicVolume: 1, originalAudioVolume: 0, musicFadeInMs: 0, musicFadeOutMs: 3000 })
})
test('fades apply once to separately rendered clips and stop outside the clip', () => {
  const sound = { volume: .8, durationMs: 6000, fadeInMs: 1000, fadeOutMs: 1000 }
  expect([-1, 0, 500, 2000, 5500, 6000].map(value => musicGain(sound, value))).toEqual([0, 0, .4, .8, .4, 0])
})
test('rendered feed clips start at zero and preserve embedded metadata', () => {
  expect(mapMomentSound({ music_audio_url: 'https://media.test/clip.m4a', music_start_ms: 0, music_duration_ms: 6000, music_embedded: true, music_volume: .4 })).toMatchObject({ startMs: 0, durationMs: 6000, embedded: true, volume: .4 })
  expect(mapMomentSound({})).toBeUndefined()
})
test('native ARGB text colors become CSS RGBA without losing alpha', () => {
  expect(mapMomentOverlay({ overlay_text: 'Hola', overlay_color: '#80FF0000', overlay_x: 2, overlay_y: -.5 })).toMatchObject({ color: '#FF000080', x: 1, y: 0 })
})
test('community search uses the full track and excludes unavailable reuse permissions', async () => {
  const item = { id: 'community:track', title: 'Pista', artist: 'Persona', durationMs: 180000, audioUrl: 'https://media.test/full.mp3', previewUrl: 'https://media.test/preview.mp3', moderationState: 'APPROVED', visibility: 'PUBLIC_REUSABLE', allowReuse: true, allowUseInMoments: true }
  api.mockResolvedValue({ items: [item, { ...item, id: 'community:blocked', allowReuse: false }, { ...item, durationMs: Infinity }, { ...item, audioUrl: '' }, { ...item, moderationState: 'PENDING' }], nextCursor: 'next' })
  const result = await searchMomentMusic('  canción  ', 'cursor')
  expect(result.tracks).toHaveLength(1)
  expect(result.tracks[0].audioUrl).toBe(item.audioUrl)
  expect(result.nextCursor).toBe('next')
  expect(String(api.mock.calls[0][0])).toContain('cursor=cursor')
})
