const assert = require('node:assert/strict')
const test = require('node:test')
const {
  codeAtStep, createRecoveryCodes, decryptSecret, encryptSecret,
  matchingStep, provisioningUri, recoveryHash,
} = require('../src/two-factor')

const master = 'test-only-stable-master-secret-with-at-least-32-characters'

test('RFC 6238 SHA1 vector, adjacent clock window and replay prevention', () => {
  const secret = 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ'
  assert.equal(codeAtStep(secret, 1), '287082')
  assert.equal(matchingStep(secret, '287082', 59_000), 1)
  assert.equal(matchingStep(secret, '287082', 59_000, 1), null)
  assert.equal(matchingStep(secret, '287082', 61_000), 1)
  assert.equal(matchingStep(secret, '287082', 61_000, 1), null)
  assert.equal(matchingStep(secret, '28708x', 59_000), null)
})

test('factor secrets are authenticated and recovery codes are random one-use identifiers', () => {
  const secret = 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ'
  const ciphertext = encryptSecret(secret, master)
  assert.notEqual(ciphertext, secret)
  assert.equal(decryptSecret(ciphertext, master), secret)
  const bytes = Buffer.from(ciphertext, 'base64url')
  bytes[bytes.length - 1] ^= 1
  assert.throws(() => decryptSecret(bytes.toString('base64url'), master))
  const codes = createRecoveryCodes()
  assert.equal(codes.length, 10)
  assert.equal(new Set(codes).size, 10)
  assert.ok(codes.every((code) => /^[A-Z2-7]{4}(?:-[A-Z2-7]{4}){3}$/.test(code)))
  assert.equal(recoveryHash(codes[0], master), recoveryHash(codes[0].replaceAll('-', ''), master))
  assert.notEqual(recoveryHash(codes[0], master), recoveryHash(codes[1], master))
  assert.equal(recoveryHash('invalid', master), null)
  assert.ok(provisioningUri(secret, 'user@example.com').includes('period=30'))
})
