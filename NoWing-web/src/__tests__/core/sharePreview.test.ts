import { publicSharePreviewPath } from '../../core/sharePreview'
const id = 'ce9b85b6-277b-47ac-bbb1-8d7b8510e6e0'
test.each(['WhatsApp/2.26','facebookexternalhit/1.1','Twitterbot/1.0','TelegramBot','LinkedInBot','Discordbot','Slackbot'])('uses object-specific public metadata for %s', agent => {
  expect(publicSharePreviewPath('/u/'+id,agent)).toBe('/p/'+id)
  expect(publicSharePreviewPath('/app/feed/post/'+id,agent)).toBe('/v1/public/posts/'+id+'/share')
  expect(publicSharePreviewPath('/app/videos/'+id,agent)).toBe('/v1/public/videos/'+id+'/share')
  expect(publicSharePreviewPath('/app/reels/'+id,agent)).toBe('/v1/public/videos/'+id+'/share')
})
test('human visitors retain the app route, including login and pending shared content',()=>{
  for(const route of ['/u/'+id,'/app/feed/post/'+id,'/app/videos/'+id]) {
    expect(publicSharePreviewPath(route,'Mozilla/5.0 Chrome/144')).toBeNull()
    expect(publicSharePreviewPath(route,'Mozilla/5.0 Chrome/144 WhatsApp/2.26')).toBeNull()
  }
})
test.each(['/app/profile','/u/../../secret','/u/%2fsecret','/app/feed/post/not-a-uuid','/app/messages/'+id,'//attacker.test/p/'+id,'/p/'+id+'/card.jpg'])('does not rewrite unrelated or unsafe paths: %s',path=>{
  expect(publicSharePreviewPath(path,'WhatsApp')).toBeNull()
})
