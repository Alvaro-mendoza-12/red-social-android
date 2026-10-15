import { publishMoment } from '../../features/moments/momentsData'
import { readFallbackSession, fallbackApiJson } from '../../core/auth/fallbackAuth'
import { uploadFallbackFile } from '../../core/fallbackData'
jest.mock('../../core/auth/fallbackAuth', () => ({ isFallbackAuthConfigured: true, readFallbackSession: jest.fn(), fallbackApiJson: jest.fn() }))
jest.mock('../../core/ubuntuBackend', () => ({ isUbuntuPrimary: true }))
jest.mock('../../core/ubuntuDataClient', () => ({ getDataClient: jest.fn() }))
jest.mock('../../core/fallbackData', () => ({ uploadFallbackFile: jest.fn() }))
const session = readFallbackSession as jest.Mock, upload = uploadFallbackFile as jest.Mock, api = fallbackApiJson as jest.Mock
const input = { file: new File(['photo'], 'photo.png', { type: 'image/png' }), caption: 'Texto', durationHours: 24, visibility: 'private', allowReplies: false, expectedAccountId: 'owner-a' }
beforeAll(() => Object.defineProperty(crypto, 'randomUUID', { configurable: true, value: () => '00000000-0000-4000-8000-000000000001' }))
beforeEach(() => { session.mockReturnValue({ user: { id: 'owner-a' } }); upload.mockReset(); upload.mockResolvedValue({ publicUrl: 'https://media.test/owned.png' }); api.mockReset(); api.mockResolvedValue({}) })
test('a changed account is rejected before any upload', async () => {
  session.mockReturnValue({ user: { id: 'owner-b' } })
  await expect(publishMoment(input)).rejects.toThrow('Tu cuenta cambió')
  expect(upload).not.toHaveBeenCalled(); expect(api).not.toHaveBeenCalled()
})
test('both device soundtrack and visual uploads are bound to the original owner', async () => {
  await publishMoment({ ...input, music: { track: { id: 'device:track', source: 'novvin_moment_local', title: 'Audio', artist: '', audioUrl: '', durationMs: 180000, localFile: new File(['audio'], 'audio.wav', { type: 'audio/wav' }) }, startMs: 120000, volume: 1, originalVolume: 1, fadeInMs: 0, fadeOutMs: 0, duckOriginal: false, presentation: 'audio' } })
  expect(upload.mock.calls.map(call => [call[0], call[3]])).toEqual([['community-music', 'owner-a'], ['moment-media', 'owner-a']])
  expect(api.mock.calls[0][1].expectedAccountId).toBe('owner-a')
})
test('a failed media upload never publishes a partial moment', async () => {
  upload.mockRejectedValue(new Error('Connection interrupted'))
  await expect(publishMoment(input)).rejects.toThrow('Connection interrupted')
  expect(api).not.toHaveBeenCalled()
})
test('migrated profile identity is accepted while requests stay fenced to its local account', async () => {
  session.mockReturnValue({ user: { id: 'local-owner-a', supabaseUserId: 'owner-a' } })
  await publishMoment(input)
  expect(upload.mock.calls[0][3]).toBe('local-owner-a')
  expect(api.mock.calls[0][1].expectedAccountId).toBe('local-owner-a')
})
