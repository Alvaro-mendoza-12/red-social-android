import { isPublicBackendPath, productionBackendOrigin, publicBackendTransportUrl } from '../../core/publicBackendTransport'

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
