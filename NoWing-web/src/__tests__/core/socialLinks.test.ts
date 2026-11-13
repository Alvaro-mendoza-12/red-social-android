import { buildProfileShareUrl, buildProfileQrPayload, buildVideoShareUrl, getPublicWebOrigin } from '../../core/socialLinks'
test('profile clipboard shares use the public preview on the official domain',()=>{
  const id='74d923b6-fe46-4ad4-bd2e-39c6bf74b1b3'
  expect(getPublicWebOrigin()).toBe('https://novvinapp.com')
  expect(buildProfileShareUrl(id)).toBe('https://novvinapp.com/p/'+id+'?preview=5')
})
test('QR payloads preserve the route recognized by installed Android clients',()=>{
  expect(buildProfileQrPayload('profile-id')).toBe('https://novvinapp.com/u/profile-id')
})
test('video links use the actual detail route on the canonical domain',()=>{
  expect(buildVideoShareUrl('video-1234')).toBe('https://novvinapp.com/app/reels/video-1234?preview=1')
  expect(()=>buildVideoShareUrl('../private')).toThrow()
})
