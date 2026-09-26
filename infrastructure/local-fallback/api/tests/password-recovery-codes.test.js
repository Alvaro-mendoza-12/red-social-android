const assert = require('node:assert/strict')
const { test } = require('node:test')
const {
  hashPasswordRecoveryCode,
  issuePasswordRecoveryCodes,
  normalizePasswordRecoveryCode,
} = require('../src/password-recovery-codes')
const { recoveryHash } = require('../src/two-factor')

const secret = 'a'.repeat(64)

test('password recovery codes are high entropy and shown in a readable format', () => {
  const codes = issuePasswordRecoveryCodes()
  assert.equal(codes.length, 10)
  assert.equal(new Set(codes).size, codes.length)
  for (const code of codes) {
    assert.match(code, /^[A-Z2-7]{4}(?:-[A-Z2-7]{4}){3}$/)
  }
})

test('password recovery hash accepts case and separators without sharing the 2FA hash domain', () => {
  const code = issuePasswordRecoveryCodes(1)[0]
  assert.equal(normalizePasswordRecoveryCode(code.toLowerCase()), code.replaceAll('-', ''))
  assert.equal(hashPasswordRecoveryCode(code, secret), hashPasswordRecoveryCode(code.toLowerCase(), secret))
  assert.notEqual(hashPasswordRecoveryCode(code, secret), recoveryHash(code, secret))
  assert.equal(hashPasswordRecoveryCode('AAAA', secret), null)
  assert.equal(hashPasswordRecoveryCode(code, 'short'), null)
})
