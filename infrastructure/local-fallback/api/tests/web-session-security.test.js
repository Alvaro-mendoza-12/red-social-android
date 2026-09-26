const assert = require('assert/strict')
const {
  isTrustedWebSessionRequest,
  parseCookieHeader,
  readWebSessionCookie,
  serializeWebSessionCookie,
} = require('../src/web-session-security')

function request(headers = {}) {
  return { headers }
}

function run() {
  assert.deepEqual(
    Object.fromEntries(parseCookieHeader('theme=dark; novvin-web-session=opaque%20secret; duplicate=first; duplicate=second')),
    {
      theme: 'dark',
      'novvin-web-session': 'opaque secret',
      duplicate: 'first',
    },
  )
  assert.equal(
    readWebSessionCookie(request({ cookie: 'novvin-web-session=opaque-token' })),
    'opaque-token',
  )
  assert.equal(
    readWebSessionCookie(
      request({ cookie: '__Secure-novvin-web-session=production-token' }),
      { production: true },
    ),
    'production-token',
  )

  const productionCookie = serializeWebSessionCookie('opaque-token', {
    production: true,
    maxAgeSeconds: 120,
  })
  assert.match(productionCookie, /^__Secure-novvin-web-session=opaque-token;/)
  assert.match(productionCookie, /Path=\/v1\/auth\/web-session/)
  assert.match(productionCookie, /HttpOnly/)
  assert.match(productionCookie, /Max-Age=120/)
  assert.match(productionCookie, /Secure/)
  assert.match(productionCookie, /SameSite=None/)
  assert.match(productionCookie, /Partitioned/)
  assert.doesNotMatch(productionCookie, /Domain=/)

  const developmentCookie = serializeWebSessionCookie('local-token', { maxAgeSeconds: 60 })
  assert.match(developmentCookie, /^novvin-web-session=local-token;/)
  assert.match(developmentCookie, /SameSite=Lax/)
  assert.doesNotMatch(developmentCookie, /Secure/)
  assert.doesNotMatch(developmentCookie, /Partitioned/)

  const allowedOrigins = ['https://app.novvin.example']
  assert.equal(isTrustedWebSessionRequest(request({
    origin: 'https://app.novvin.example',
    'x-novvin-web-session': 'web-v1',
  }), { allowedOrigins, production: true }), true)
  assert.equal(isTrustedWebSessionRequest(request({
    origin: 'https://evil.example',
    'x-novvin-web-session': 'web-v1',
  }), { allowedOrigins, production: true }), false)
  assert.equal(isTrustedWebSessionRequest(request({
    origin: 'https://app.novvin.example',
  }), { allowedOrigins, production: true }), false)
  assert.equal(isTrustedWebSessionRequest(request({
    'x-novvin-web-session': 'web-v1',
  }), { allowedOrigins, production: true }), false)
  assert.equal(isTrustedWebSessionRequest(request({
    'x-novvin-web-session': 'web-v1',
  }), { allowedOrigins: '*', production: false }), true)

  console.log('web session security tests passed')
}

run()
