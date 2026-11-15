import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import { loadUbuntuChallengesBundle } from '../../core/challengesUbuntu'
import { publicProfileSummaries } from '../../core/publicProfileSummaries'

jest.mock('../../core/auth/fallbackAuth', () => ({ fallbackApiJson: jest.fn() }))
jest.mock('../../core/publicProfileSummaries', () => ({ publicProfileSummaries: jest.fn() }))

test('loads independent round details together after finding the active round', async () => {
  const api = jest.mocked(fallbackApiJson)
  const profiles = jest.mocked(publicProfileSummaries)
  let finishMeta!: (response: unknown) => void
  let showConcurrentRequests!: () => void
  const submissionsRequested = new Promise<void>(resolve => { showConcurrentRequests = resolve })
  api.mockImplementation(async (path) => {
    if (path.endsWith('get_or_generate_active_challenge')) {
      return [{ id: 'round-1', challenge_id: 'challenge-1', winner_ids: ['winner'] }]
    }
    if (path.includes('challenges_master')) return new Promise(resolve => { finishMeta = resolve })
    showConcurrentRequests()
    return [{ id: 'submission', user_id: 'winner', vote_count: 4 }]
  })
  profiles.mockResolvedValue(new Map([['winner', { display_name: 'Lucía' }]]))

  const bundle = loadUbuntuChallengesBundle('owner')
  await submissionsRequested
  expect(profiles).toHaveBeenCalledWith(['winner'])
  finishMeta([{ id: 'challenge-1', title: 'Ronda creativa' }])
  await expect(bundle).resolves.toMatchObject({
    challengeMeta: { title: 'Ronda creativa' },
    winnerProfiles: [{ id: 'winner', name: 'Lucía' }],
    ranking: [{ userId: 'winner', totalVotes: 4 }],
  })
  expect(api.mock.calls.every(([, init]) => init?.expectedAccountId === 'owner')).toBe(true)
})
