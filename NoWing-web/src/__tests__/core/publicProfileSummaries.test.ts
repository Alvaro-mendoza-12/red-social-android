import { publicProfileSummaries } from '../../core/publicProfileSummaries'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
let mockOwner = 'owner'
jest.mock('../../core/auth/fallbackAuth', () => ({ fallbackApiJson: jest.fn(), isFallbackAuthConfigured: true, readFallbackSession: () => ({ user: { id: mockOwner } }) }))
jest.mock('../../core/supabase', () => ({ supabase: null }))

test('Ubuntu summaries use the supported public projection and bind the active account', async () => {
  const id = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'
  jest.mocked(fallbackApiJson).mockResolvedValue([{ id, display_name: 'Amigo' }])
  expect((await publicProfileSummaries([id, id, 'invalid'])).get(id)?.display_name).toBe('Amigo')
  expect(fallbackApiJson).toHaveBeenCalledWith(`/rest/v1/profiles?select=id,display_name,username,avatar_url,avatar_media_type,is_admin,is_verified,badges&id=in.(${id})`, { expectedAccountId: 'owner' })
})

test('a long inbox does not silently lose the identities beyond the first batch',async()=>{
 jest.mocked(fallbackApiJson).mockReset()
 const ids=Array.from({length:125},(_,i)=>`${i.toString(16).padStart(8,'0')}-bbbb-cccc-dddd-eeeeeeeeeeee`)
 jest.mocked(fallbackApiJson).mockImplementation(async(path)=>ids.filter(id=>String(path).includes(id)).map(id=>({id,is_verified:true})) as never)
 const rows=await publicProfileSummaries(ids)
 expect(rows.size).toBe(125)
 expect(jest.mocked(fallbackApiJson)).toHaveBeenCalledTimes(2)
})

test('later batches remain bound to the original account if the session changes',async()=>{
 jest.mocked(fallbackApiJson).mockReset();mockOwner='owner'
 const ids=Array.from({length:101},(_,i)=>`${i.toString(16).padStart(8,'0')}-bbbb-cccc-dddd-eeeeeeeeeeee`)
 jest.mocked(fallbackApiJson).mockImplementation(async(_path,init)=>{
  if(init?.expectedAccountId!==mockOwner)throw new Error('ACCOUNT_CHANGED')
  mockOwner='other-account';return [] as never
 })
 try {
  await expect(publicProfileSummaries(ids)).rejects.toThrow('ACCOUNT_CHANGED')
  expect(jest.mocked(fallbackApiJson).mock.calls.every(([,init])=>init?.expectedAccountId==='owner')).toBe(true)
 } finally {mockOwner='owner'}
})
