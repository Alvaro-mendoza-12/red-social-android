import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import { cancelFallbackFriendRequest } from '../../core/fallbackData'
import { getStorageReadIdentity, updateStorageReadIdentity } from '../../core/media/storageReadSession'

jest.mock('../../core/auth/fallbackAuth', () => ({ fallbackApiJson: jest.fn() }))
const api = jest.mocked(fallbackApiJson)
beforeEach(() => { api.mockReset(); updateStorageReadIdentity('fixture-account', 'fallback') })

test('cancellation uses its POST action, exact encoded ID and starting login identity', async () => {
  api.mockResolvedValue({ request: { id: 'request/one' } })
  const identity = getStorageReadIdentity()
  await expect(cancelFallbackFriendRequest('request/one')).resolves.toEqual({ request: { id: 'request/one' } })
  expect(api).toHaveBeenCalledWith('/v1/data/friends/request%2Fone/cancel', {
    method: 'POST', expectedIdentity: identity, expectedAccountId: 'fixture-account',
  })
})

test.each(['FRIEND_REQUEST_ALREADY_RESOLVED', 'HTTP_404'])('failure %s never falls back to deleting a friendship', async message => {
  api.mockRejectedValue(new Error(message))
  await expect(cancelFallbackFriendRequest('request')).rejects.toThrow(message)
  expect(api).toHaveBeenCalledTimes(1)
  expect(api.mock.calls[0][1]?.method).toBe('POST')
})
