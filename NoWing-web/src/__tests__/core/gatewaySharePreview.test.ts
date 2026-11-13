/** @jest-environment node */
import middleware from '../../../middleware'
const id='ce9b85b6-277b-47ac-bbb1-8d7b8510e6e0'
test.each([
 ['/u/'+id,'/p/'+id],
 ['/app/feed/post/'+id,'/v1/public/posts/'+id+'/share'],
 ['/app/videos/'+id,'/v1/public/videos/'+id+'/share'],
 ['/app/reels/'+id,'/v1/public/videos/'+id+'/share'],
])('crawler preview for %s uses only anonymous object metadata', (path, target)=>{
 const response=middleware(new Request('https://novvinapp.com'+path+'?access_token=must-not-forward',{
  headers:{'User-Agent':'WhatsApp/2.26',Authorization:'Bearer synthetic-private-session',Cookie:'session=synthetic-private-cookie'},
 }))
 expect(response.headers.get('x-middleware-rewrite')).toBe('https://conectate.tail9b267c.ts.net'+target)
 expect(response.headers.get('x-middleware-request-authorization')).toBeNull()
 expect(response.headers.get('x-middleware-request-cookie')).toBeNull()
 expect(response.headers.get('Vercel-CDN-Cache-Control')).toBe('no-store')
})
test('a person opening a shared app URL retains the app route',()=>{
 const response=middleware(new Request('https://novvinapp.com/app/feed/post/'+id,{headers:{'User-Agent':'Mozilla/5.0 Chrome/144 WhatsApp/2.26'}}))
 expect(response.headers.get('x-middleware-next')).toBe('1')
 expect(response.headers.get('x-middleware-rewrite')).toBeNull()
})
test('direct public preview URLs also discard ambient account credentials',()=>{
 const response=middleware(new Request('https://novvinapp.com/v1/public/posts/'+id+'/share.jpg',{headers:{Authorization:'Bearer synthetic-account',Cookie:'session=synthetic-account'}}))
 expect(response.headers.get('x-middleware-request-authorization')).toBeNull()
 expect(response.headers.get('x-middleware-request-cookie')).toBeNull()
})
