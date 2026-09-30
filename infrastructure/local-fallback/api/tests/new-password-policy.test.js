'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { requireBcryptPasswordLength } = require('../src/new-password-policy')

function probe(body, fields = ['newPassword']) {
  let continued = false, status, payload
  const res = { status(value) { status = value; return this }, json(value) { payload = value; return this } }
  requireBcryptPasswordLength(...fields)({ body }, res, () => { continued = true })
  return { continued, status, payload }
}
test('new password length is bounded in UTF-8 bytes rather than characters', () => {
  assert.equal(probe({ newPassword: 'A'.repeat(72) }).continued, true)
  assert.equal(probe({ newPassword: 'A'.repeat(73) }).status, 400)
  assert.equal(probe({ newPassword: 'é'.repeat(36) }).continued, true)
  assert.equal(probe({ newPassword: 'é'.repeat(37) }).status, 400)
  assert.equal(probe({ newPassword: '🔑'.repeat(18) }).continued, true)
  assert.equal(probe({ newPassword: '🔑'.repeat(19) }).status, 400)
})
test('aliases match route precedence and invalid short input remains delegated', () => {
  assert.equal(probe({ password: 'A'.repeat(73) }, ['newPassword', 'password']).status, 400)
  assert.equal(probe({ newPassword: 'short', password: 'A'.repeat(73) }, ['newPassword', 'password']).continued, true)
  assert.equal(probe({}).continued, true)
  assert.equal(probe({ newPassword: 'A'.repeat(73) }).payload.error, 'PASSWORD_TOO_LONG')
})
test('every new account password write is guarded while legacy login stays compatible', () => {
  const source = fs.readFileSync(path.join(__dirname, '../src/server.js'), 'utf8')
  for (const route of ['/v1/admin/users/set-local-password', '/v1/auth/register', '/v1/auth/change-password',
    '/v1/auth/password-reset/confirm', '/v1/auth/password-recovery-codes/confirm',
    '/v1/auth/complete-migrated-password-reset', '/v1/auth/activate-local-password']) {
    const line = source.split('\n').find(line => line.startsWith(`app.post('${route}'`))
    assert.ok(line.includes('requireBcryptPasswordLength('), route)
  }
  assert.ok(!source.split('\n').find(line => line.startsWith("app.post('/v1/auth/login'")).includes('requireBcryptPasswordLength'))
  const rooms = fs.readFileSync(path.join(__dirname, '../src/data-routes.js'), 'utf8')
  assert.ok(rooms.includes("if (req.body?.isPublic === false) return requireBcryptPasswordLength('password')(req, res, next)"))
})
