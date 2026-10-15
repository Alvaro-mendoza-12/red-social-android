import { fallbackApiJson, readFallbackSession } from '../../core/auth/fallbackAuth'
import { loadOwnProfileVisits, recordWebProfileVisit } from '../../core/profileVisits'
jest.mock('../../core/auth/fallbackAuth',()=>({fallbackApiJson:jest.fn(),readFallbackSession:jest.fn()}))
const api=fallbackApiJson as jest.MockedFunction<typeof fallbackApiJson>,session=readFallbackSession as jest.Mock
const owner='11111111-1111-4111-8111-111111111111',target='22222222-2222-4222-8222-222222222222'
beforeEach(()=>{api.mockReset();session.mockReturnValue({user:{id:owner}})})
test('self visits and opted-out targets do not make requests',async()=>{
  await recordWebProfileVisit(owner,true);await recordWebProfileVisit(target,false)
  expect(api).not.toHaveBeenCalled()
})
test('the viewer own privacy preference prevents recording',async()=>{
  api.mockResolvedValue({profile:{profile_view_history_enabled:false}})
  await recordWebProfileVisit(target,true)
  expect(api).toHaveBeenCalledTimes(1)
})
test('eligible visits use the authenticated registry and stable pair',async()=>{
  api.mockResolvedValueOnce({profile:{profile_view_history_enabled:true}}).mockResolvedValueOnce({})
  await recordWebProfileVisit(target,true)
  expect(api.mock.calls[1][0]).toBe('/rest/v1/profile_views?on_conflict=profile_id,viewer_id')
  const input=api.mock.calls[1][1]!
  expect(input.expectedAccountId).toBe(owner)
  expect(JSON.parse(String(input.body))).toMatchObject({profile_id:target,viewer_id:owner})
})
test('visits are joined only to server-visible public profile fields',async()=>{
  api.mockResolvedValueOnce({profile:{profile_view_history_enabled:true}}).mockResolvedValueOnce([{viewer_id:target,viewed_at:'2026-10-14T12:00:00Z'}]).mockResolvedValueOnce([{id:target,display_name:'Persona',avatar_url:'https://example.invalid/avatar.png'}])
  const result=await loadOwnProfileVisits(owner)
  expect(result.items).toEqual([{id:target,name:'Persona',avatar:'https://example.invalid/avatar.png',time:'2026-10-14T12:00:00Z'}])
  expect(api.mock.calls[2][0]).not.toContain('email')
})
test('disabled own history never requests the visit table',async()=>{
  api.mockResolvedValue({profile:{profile_view_history_enabled:false}})
  expect(await loadOwnProfileVisits(owner)).toEqual({enabled:false,items:[]})
  expect(api).toHaveBeenCalledTimes(1)
})
