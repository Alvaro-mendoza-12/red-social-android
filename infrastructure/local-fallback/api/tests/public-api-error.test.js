const test = require('node:test')
const assert = require('node:assert/strict')
const { publicApiError } = require('../src/public-api-error')

test('known application rejection codes retain their status and meaning', () => {
  for (const message of ['AUTH_REQUIRED', 'INVALID_TOKEN', 'PAIRING_EXPIRED', 'PAIRING_NOT_PENDING']) {
    assert.deepEqual(publicApiError({ status: 401, message }), { status: 401, code: message })
  }
  assert.deepEqual(publicApiError({ statusCode: 413, message: 'PAYLOAD_TOO_LARGE' }), { status: 413, code: 'PAYLOAD_TOO_LARGE' })
})

test('unexpected client errors never expose paths, SQL, tokens or submitted text', () => {
  for (const message of ['/srv/private/customer.json: permission denied', 'SELECT secret FROM users', 'Invalid token=private-value', 'Unexpected token in JSON at position 2', 'x'.repeat(1000)]) {
    assert.deepEqual(publicApiError({ status: 400, message }), { status: 400, code: 'REQUEST_REJECTED' })
  }
})

test('unexpected server diagnostics are generic, even uppercase database codes', () => {
  assert.deepEqual(publicApiError({ status: 500, message: 'DATABASE_PASSWORD_MISSING', code: 'XX000' }), { status: 500, code: 'INTERNAL_ERROR' })
  assert.deepEqual(publicApiError(null), { status: 500, code: 'INTERNAL_ERROR' })
})

test('only deliberately exposed safe codes may describe server errors', () => {
  assert.deepEqual(publicApiError({ status: 503, expose: true, code: 'SERVICE_UNAVAILABLE' }), { status: 503, code: 'SERVICE_UNAVAILABLE' })
  for (const code of ['secret: value', '/srv/private', 'A'.repeat(81)]) {
    assert.deepEqual(publicApiError({ status: 503, expose: true, code }), { status: 503, code: 'INTERNAL_ERROR' })
  }
})

test('invalid, fractional or out-of-range status values cannot reach the HTTP response', () => {
  for (const status of [NaN, Infinity, 'invalid', 200, 600, 401.5]) {
    assert.deepEqual(publicApiError({ status, message: 'INTERNAL_DETAIL' }), { status: 500, code: 'INTERNAL_ERROR' })
  }
})
