/** @jest-environment node */
import middleware from '../../../middleware'

test.each([['POST', '/v1/guest/session'], ['GET', '/v1/guest/videos'], ['GET', '/storage/v1/object/public/videos/test.mp4'], ['HEAD', '/storage/v1/render/image/public/avatars/test.png']])('gateway strips browser cookies before forwarding isolated %s route %s', (method, path) => {
  const request = new Request(`https://red-social-android-6xn6c21te-alvaro-s-projects1.vercel.app${path}`, {
    method, headers: { Cookie: 'deployment=synthetic; session=synthetic-account', Authorization: 'Bearer synthetic-guest', Range: 'bytes=0-1' },
  })
  const result = middleware(request)
  expect(result.headers.get('x-middleware-request-cookie')).toBeNull()
  expect(result.headers.get('x-middleware-request-authorization')).toBe('Bearer synthetic-guest')
  expect(result.headers.get('x-middleware-request-range')).toBe('bytes=0-1')
  expect(result.headers.get('x-middleware-rewrite')).toBe(`https://conectate.tail9b267c.ts.net${path}`)
})

test('normal account session routes keep their cookie authentication', () => {
  const result = middleware(new Request('https://red-social-android.vercel.app/v1/auth/refresh', {
    headers: { Cookie: 'session=synthetic-account' },
  }))
  expect(result.headers.get('x-middleware-request-cookie')).toBe('session=synthetic-account')
})


test.each(['POST', 'PUT'])('storage %s preserves explicit upload authorization without forwarding browser cookies', method => {
  const result = middleware(new Request('https://red-social-android.vercel.app/storage/v1/object/videos/test.mp4', {
    method, headers: { Cookie: 'session=synthetic-account', Authorization: 'Bearer synthetic-upload' },
  }))
  expect(result.headers.get('x-middleware-request-cookie')).toBeNull()
  expect(result.headers.get('x-middleware-request-authorization')).toBe('Bearer synthetic-upload')
})
