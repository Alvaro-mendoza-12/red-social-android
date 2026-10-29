import { publicProfileSummaries } from '../../core/publicProfileSummaries'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
jest.mock('../../core/auth/fallbackAuth', () => ({ fallbackApiJson: jest.fn(), isFallbackAuthConfigured: true, readFallbackSession: () => ({ user: { id: 'owner' } }) }))
jest.mock('../../core/supabase', () => ({ supabase: null }))

test('Ubuntu summaries use the supported public projection and bind the active account', async () => {
  const id = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'
  jest.mocked(fallbackApiJson).mockResolvedValue([{ id, display_name: 'Amigo' }])
  expect((await publicProfileSummaries([id, id, 'invalid'])).get(id)?.display_name).toBe('Amigo')
  expect(fallbackApiJson).toHaveBeenCalledWith(`/rest/v1/profiles?select=id,display_name,username,avatar_url,avatar_media_type,is_admin,is_verified&id=in.(${id})`, { expectedAccountId: 'owner' })
})
