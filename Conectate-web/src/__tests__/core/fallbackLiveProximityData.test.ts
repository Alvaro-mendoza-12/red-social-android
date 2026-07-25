import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import {
  createFallbackLiveStream,
  enableFallbackProximity,
  loadFallbackLiveStreams,
  matchFallbackRandomChat,
  sendFallbackRandomMessage,
} from '../../core/fallbackData'

jest.mock('../../core/auth/fallbackAuth', () => ({
  fallbackApiJson: jest.fn(),
}))

const apiMock = fallbackApiJson as jest.MockedFunction<typeof fallbackApiJson>

describe('live and proximity fallback API contracts', () => {
  beforeEach(() => {
    apiMock.mockReset()
    apiMock.mockResolvedValue({})
  })

  it('loads active live discovery through the authenticated Ubuntu API', async () => {
    await loadFallbackLiveStreams(12)
    expect(apiMock).toHaveBeenCalledWith('/v1/data/live?limit=12')
  })

  it('creates a live stream with its selected source', async () => {
    await createFallbackLiveStream({ title: 'Prueba', source: 'screen', hashtags: ['qa'] })
    expect(apiMock).toHaveBeenCalledWith('/v1/data/live', {
      method: 'POST',
      body: JSON.stringify({ title: 'Prueba', source: 'screen', hashtags: ['qa'] }),
    })
  })

  it('sends coordinates only to presence and keeps radius within the request contract', async () => {
    const input = {
      latitude: -12.04,
      longitude: -77.03,
      radiusKm: 8,
      gender: 'any',
      lookingFor: 'female',
    }
    await enableFallbackProximity(input)
    expect(apiMock).toHaveBeenCalledWith('/v1/data/proximity/presence', {
      method: 'PUT',
      body: JSON.stringify(input),
    })
  })

  it('uses idempotent client identifiers for random chat messages', async () => {
    await matchFallbackRandomChat({ gender: 'any', lookingFor: 'any' })
    await sendFallbackRandomMessage('session-id', {
      content: 'hola',
      clientMessageId: 'client-message-id',
    })
    expect(apiMock).toHaveBeenNthCalledWith(1, '/v1/data/proximity/random/match', {
      method: 'POST',
      body: JSON.stringify({ gender: 'any', lookingFor: 'any' }),
    })
    expect(apiMock).toHaveBeenNthCalledWith(
      2,
      '/v1/data/proximity/random/sessions/session-id/messages',
      {
        method: 'POST',
        body: JSON.stringify({ content: 'hola', clientMessageId: 'client-message-id' }),
      },
    )
  })
})
