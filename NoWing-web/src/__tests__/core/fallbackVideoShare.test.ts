import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import { setFallbackVideoShare } from '../../core/fallbackData'
jest.mock('../../core/auth/fallbackAuth',()=>({fallbackApiJson:jest.fn()}))
const api=fallbackApiJson as jest.MockedFunction<typeof fallbackApiJson>
beforeEach(()=>{api.mockReset();api.mockResolvedValue({ok:true})})
test('adding a video to the profile is persisted by the server',async()=>{
  await setFallbackVideoShare('video-id',true)
  expect(api).toHaveBeenCalledWith('/v1/data/videos/video-id/share',{method:'POST',body:'{}'})
})
test('removing a shared video is persisted by the server',async()=>{
  await setFallbackVideoShare('video-id',false)
  expect(api).toHaveBeenCalledWith('/v1/data/videos/video-id/share',{method:'DELETE',body:undefined})
})
test('failed persistence is reported to the caller',async()=>{
  api.mockRejectedValue(new Error('Network unavailable'))
  await expect(setFallbackVideoShare('video-id',true)).rejects.toThrow('Network unavailable')
})
