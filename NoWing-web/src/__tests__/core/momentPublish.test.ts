import { publishUbuntuMoment, fetchUbuntuMomentsFeed } from '../../core/momentsUbuntu'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
jest.mock('../../core/auth/fallbackAuth', () => ({ fallbackApiJson: jest.fn() }))
const api = fallbackApiJson as jest.Mock
beforeEach(() => api.mockReset())
test('publishing is account-fenced and retains private audience, music and overlay fields', async () => {
  api.mockResolvedValue({})
  await publishUbuntuMoment({ mediaUrl: 'https://media.test/photo.png', mediaType: 'image', durationHours: 24, visibility: 'private', allowReplies: false, displayDurationMs: 6000, expectedAccountId: 'owner-a', musicFields: { musicStartMs: 120000, musicEmbedded: false }, overlay: { text: 'Texto', color: '#12aaff', font: 'mono', x: .4, y: .3 } })
  const options = api.mock.calls[0][1]
  expect(options.expectedAccountId).toBe('owner-a')
  expect(JSON.parse(options.body)).toMatchObject({ visibility: 'private', musicStartMs: 120000, musicEmbedded: false, overlayColor: '#FF12aaff', overlayText: 'Texto', displayDurationMs: 6000, allowReplies: false })
})
test('feed mapping keeps playback duration and soundtrack data', async () => {
  api.mockResolvedValue({ items: [{ id: 'moment', user_id: 'owner', media_url: 'https://media.test/clip.mp4', media_type: 'video', display_duration_ms: 18000, music_audio_url: 'https://media.test/sound.m4a', music_duration_ms: 18000, music_start_ms: 0, music_embedded: false, overlay_text: 'Hola' }] })
  expect((await fetchUbuntuMomentsFeed('owner'))[0]).toMatchObject({ displayDurationMs: 18000, music: { durationMs: 18000, embedded: false, startMs: 0 }, overlay: { text: 'Hola' } })
})
