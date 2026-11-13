import { isolatedBackendRequestCredentials, isPublicBackendPath, productionBackendOrigin, publicBackendTransportUrl } from '../../core/publicBackendTransport'

test.each(['https://novvinapp.com', 'https://www.novvinapp.com'])('the registered domain uses its own API gateway and isolated media cookies: %s', page => {
  expect(publicBackendTransportUrl(productionBackendOrigin + '/v1/auth/session', page)).toBe(page + '/v1/auth/session')
  expect(isolatedBackendRequestCredentials(page + '/v1/guest/session', page)).toBe('same-origin')
})

test.each(['http://novvinapp.com', 'https://novvinapp.com.evil.test', 'https://novvinapp.com:444', 'https://u:p@novvinapp.com'])('registered domain trust rejects %s', page => {
  expect(publicBackendTransportUrl(productionBackendOrigin, page)).toBe(productionBackendOrigin)
})

test('both public profile previews are served through the branded gateway', () => {
  const page = 'https://novvinapp.com'
  for (const path of ['/p/test_profile', '/p/test_profile/card.jpg']) {
    expect(isPublicBackendPath(path)).toBe(true)
    expect(isolatedBackendRequestCredentials(page + path, page)).toBe('same-origin')
  }
  expect(isPublicBackendPath('/p/test_profile/other')).toBe(false)
})

test('web API, signed media and signaling use public same-origin HTTPS', () => {
  const page = 'https://red-social-android.vercel.app'
  expect(publicBackendTransportUrl(productionBackendOrigin, page)).toBe(page)
  expect(publicBackendTransportUrl(productionBackendOrigin + '/storage/v1/object/private/chat/a.webp?token=synthetic', page)).toBe(page + '/storage/v1/object/private/chat/a.webp?token=synthetic')
  expect(publicBackendTransportUrl(productionBackendOrigin + '/call', page)).toBe(page + '/call')
})

test('production voice port uses the public gateway without requesting local-network access', () => {
  const page = 'https://red-social-android.vercel.app'
  expect(publicBackendTransportUrl(productionBackendOrigin + ':8443', page)).toBe(page)
  expect(publicBackendTransportUrl(productionBackendOrigin + ':8443/turn-credentials', page)).toBe(page + '/turn-credentials')
  expect(publicBackendTransportUrl(productionBackendOrigin + ':8443/socket.io/?EIO=4&transport=polling', page)).toBe(page + '/socket.io/?EIO=4&transport=polling')
  for (const value of [productionBackendOrigin + ':8444', 'https://other.ts.net:8443', 'https://user:secret@conectate.tail9b267c.ts.net:8443']) expect(publicBackendTransportUrl(value, page)).toBe(value)
})

test('preview deployments retain their own public origin for pre-promotion checks', () => {
  const page = 'https://red-social-android-ab123-alvaro-s-projects1.vercel.app'
  expect(publicBackendTransportUrl(productionBackendOrigin, page)).toBe(page)
})

test('the corrections branch alias keeps login requests on its public HTTPS gateway', () => {
  const page = 'https://red-social-android-git-correcciones-dot-alvaro-s-projects1.vercel.app'
  expect(publicBackendTransportUrl(productionBackendOrigin + '/v1/auth/login', page))
    .toBe('https://red-social-android-git-correcciones-dot-alvaro-s-projects1.vercel.app/v1/auth/login')
})

test.each([
  'https://red-social-android-git-unreviewed-alvaro-s-projects1.vercel.app',
  'https://red-social-android-git-correcciones-dot-another-team.vercel.app',
  'https://red-social-android-git-correcciones-dot-alvaro-s-projects1.vercel.app.evil.test',
])('does not extend branch alias trust to %s', page => {
  expect(publicBackendTransportUrl(productionBackendOrigin, page)).toBe(productionBackendOrigin)
})

test.each(['https://attacker.vercel.app', 'http://red-social-android.vercel.app', 'https://red-social-android.vercel.app.evil.test', 'https://red-social-android.vercel.app:444'])('does not install a proxy transport on %s', page => {
  expect(publicBackendTransportUrl(productionBackendOrigin, page)).toBe(productionBackendOrigin)
})

test('does not redirect credentials to a different discovered backend or an injected origin', () => {
  const page = 'https://red-social-android.vercel.app'
  for (const candidate of ['https://other.ts.net', 'https://conectate.tail9b267c.ts.net.evil.test', 'https://user:password@conectate.tail9b267c.ts.net']) expect(publicBackendTransportUrl(candidate, page)).toBe(candidate)
})

test('only explicitly supported API and media paths enter the fixed upstream', () => {
  for (const path of ['/v1/auth/login', '/ready', '/realtime/', '/socket.io/', '/call/socket.io/', '/storage/v1/object/private/a']) expect(isPublicBackendPath(path)).toBe(true)
  for (const path of ['/', '/login', '/app/profile', '/backend-config.json', '/_novvin_storage/a', '/arbitrary-origin', '//attacker.test']) expect(isPublicBackendPath(path)).toBe(false)
})

describe('isolated gateway request credentials', () => {
  const page = 'https://red-social-android-6xn6c21te-alvaro-s-projects1.vercel.app'
  test.each(['/v1/guest/session', '/v1/guest/videos', '/storage/v1/object/public/videos/test.mp4', '/storage/v1/render/image/public/avatars/test.png'])('preserves deployment access only on isolated gateway path %s', path => {
    expect(isolatedBackendRequestCredentials(page + path, page)).toBe('same-origin')
  })
  test.each([
    ['https://red-social-android.vercel.app', 'https://red-social-android.vercel.app/v1/guest/posts'],
    ['https://red-social-android-git-correcciones-dot-alvaro-s-projects1.vercel.app', 'https://red-social-android-git-correcciones-dot-alvaro-s-projects1.vercel.app/v1/guest/moments'],
  ])('supports official gateway %s', (origin, target) => {
    expect(isolatedBackendRequestCredentials(target, origin)).toBe('same-origin')
  })
  test.each([
    page + '/v1/auth/login',
    page + '/storage/admin',
    page + '/v1/guest/../auth/refresh',
    'https://red-social-android.vercel.app/v1/guest/session',
    'https://other.ts.net/v1/guest/session',
    'https://user:synthetic@red-social-android-6xn6c21te-alvaro-s-projects1.vercel.app/v1/guest/session',
    page + ':444/v1/guest/session',
    '/v1/guest/session',
  ])('omits ambient cookies for unrelated targets %s', target => {
    expect(isolatedBackendRequestCredentials(target, page)).toBe('omit')
  })
  test.each(['https://conectate.tail9b267c.ts.net', 'https://attacker.vercel.app', 'https://red-social-android-git-unreviewed-alvaro-s-projects1.vercel.app', 'http://red-social-android.vercel.app', 'https://red-social-android.vercel.app:444', 'https://red-social-android.vercel.app.evil.test'])('does not enable cookie transport on untrusted host %s', origin => {
    expect(isolatedBackendRequestCredentials(origin + '/v1/guest/session', origin)).toBe('omit')
  })
})
