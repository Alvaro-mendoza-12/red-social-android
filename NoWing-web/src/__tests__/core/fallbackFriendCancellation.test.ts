import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import { cancelFallbackFriendRequest } from '../../core/fallbackData'

jest.mock('../../core/auth/fallbackAuth', () => ({fallbackApiJson: jest.fn()}))
const api = jest.mocked(fallbackApiJson)
beforeEach(() => api.mockReset())

test('cancellation uses the authenticated API and encodes the exact request ID', async () => {
  api.mockResolvedValue({request:{id:'request/one'}})
  await expect(cancelFallbackFriendRequest('request/one')).resolves.toEqual({request:{id:'request/one'}})
  expect(api).toHaveBeenCalledWith('/v1/data/friends/request%2Fone', {method:'DELETE'})
})

test('a cancellation failure remains visible to the calling component', async () => {
  api.mockRejectedValue(new Error('FRIEND_REQUEST_ALREADY_RESOLVED'))
  await expect(cancelFallbackFriendRequest('request')).rejects.toThrow('FRIEND_REQUEST_ALREADY_RESOLVED')
})
