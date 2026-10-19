import { loadRecommendedVideoPage, loadFriendsVideoPage, mixBasicVideoSession } from '../../core/videoRecommendations'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
jest.mock('../../core/auth/fallbackAuth', () => ({ fallbackApiJson: jest.fn() }))
const api = jest.mocked(fallbackApiJson)
beforeEach(() => api.mockReset())

test('friends request their own paginated catalogue without recommendations',async()=>{
 api.mockResolvedValue({items:[{id:'friend-video'}]})
 const page=await loadFriendsVideoPage({owner:'account',offset:40})
 expect(api.mock.calls[0][0]).toContain('friendsOnly=true');expect(api.mock.calls[0][0]).toContain('offset=40')
 expect(api.mock.calls[0][0]).not.toContain('recommendations');expect(page.offset).toBe(41);expect(page.hasMore).toBe(false)
})
test('preserves authoritative server order and its continuation cursor', async () => {
  api.mockResolvedValue({items:[{id:'second'},{id:'first'}],page:{nextCursor:'opaque',hasMore:true},algorithm:{authoritative:true}})
  const page = await loadRecommendedVideoPage({owner:'account',refreshId:'session'})
  expect(page.items.map(item => item.id)).toEqual(['second','first']); expect(page.nextCursor).toBe('opaque')
  expect(api.mock.calls[0][0]).toContain('surface=video')
  expect(api.mock.calls[0][1]).toMatchObject({expectedAccountId:'account'})
})
test('uses the same cursor and browsing session on the next page', async () => {
  api.mockResolvedValue({items:[],page:{nextCursor:null,hasMore:false},algorithm:{authoritative:true}})
  const page = await loadRecommendedVideoPage({owner:'account',refreshId:'session',cursor:'opaque',offset:20})
  expect(api.mock.calls[0][0]).toContain('cursor=opaque'); expect(api.mock.calls[0][0]).toContain('refreshId=session')
  expect(page.hasMore).toBe(false); expect(page.legacy).toBe(false)
})
test.each(['USER_ALGORITHM_DISABLED','ALGORITHM_PROFILE_DISABLED'])('unavailable or disabled algorithm %s keeps the basic feed available', async code => {
  api.mockRejectedValueOnce(new Error(code)).mockResolvedValueOnce({items:Array.from({length:20},(_,id) => ({id}))})
  const page = await loadRecommendedVideoPage({owner:'account',refreshId:'session'})
  expect(api.mock.calls[1][0]).toContain('recommended=true'); expect(api.mock.calls[1][0]).toContain('feedSessionId=session')
  expect(page.legacy).toBe(true); expect(page.offset).toBe(20); expect(page.hasMore).toBe(true)
})
test('cursor failures and network errors are never silently replaced with the first catalogue page', async () => {
  api.mockRejectedValue(new Error('RECOMMENDATION_CURSOR_STALE'))
  await expect(loadRecommendedVideoPage({owner:'account',refreshId:'session',cursor:'opaque'})).rejects.toThrow('RECOMMENDATION_CURSOR_STALE')
  expect(api).toHaveBeenCalledTimes(1)
})

test('basic feeds are stable within a session and change on entry without repeating the first video', () => {
  const items = Array.from({ length: 20 }, (_, id) => ({ id: String(id) }))
  const first = mixBasicVideoSession(items, 'viewer', 'entry-one')
  expect(mixBasicVideoSession(items, 'viewer', 'entry-one')).toEqual(first)
  const second = mixBasicVideoSession(items, 'viewer', 'entry-two')
  expect(second[0].id).not.toBe(first[0].id)
  expect(new Set(second.map(item => item.id))).toEqual(new Set(items.map(item => item.id)))
  expect(mixBasicVideoSession(items, 'another-viewer', 'entry-two')).not.toEqual(second)
})
