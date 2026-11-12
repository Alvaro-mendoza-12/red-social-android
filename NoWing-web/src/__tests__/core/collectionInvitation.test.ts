import { collectionInvitationPath } from '../../core/collectionInvitation'
const id='00000000-0000-4000-8000-000000000071',token='a'.repeat(43)
test('web and app invitations map to the same internal preview',()=>{
  expect(collectionInvitationPath(`https://red-social-android.vercel.app/group/${id}?invite=${token}`)).toBe(`/group/${id}?invite=${token}`)
  expect(collectionInvitationPath(`conectate://group/${id}?invite=${token}`)).toBe(`/group/${id}?invite=${token}`)
  expect(collectionInvitationPath(`https://conectate.com/community/${id}`)).toBe(`/community/${id}`)
  for(const host of ['novvinapp.com','www.novvinapp.com']) expect(collectionInvitationPath(`https://${host}/group/${id}?invite=${token}`)).toBe(`/group/${id}?invite=${token}`)
})
test('rejects arbitrary QR links, credentials, incomplete tokens and extra paths',()=>{
  for(const value of [`https://example.org/group/${id}?invite=${token}`,`https://conectate.com.evil.test/group/${id}?invite=${token}`,`https://user@conectate.com/group/${id}?invite=${token}`,`https://conectate.com/group/${id}`,`https://conectate.com/group/${id}/more?invite=${token}`,'x'.repeat(2049)]) expect(()=>collectionInvitationPath(value)).toThrow()
})
