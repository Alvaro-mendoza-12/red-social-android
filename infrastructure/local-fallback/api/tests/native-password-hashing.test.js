'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const native = require('bcrypt')
const legacy = require('bcryptjs')

test('native bcrypt validates existing bcryptjs hashes and rejects bad passwords', async () => {
  for (const password of ['Compatible-QA-9!', 'Contraseña-áé-🔑-9!']) {
    const hash = await legacy.hash(password, 6)
    assert.equal(await native.compare(password, hash), true)
    assert.equal(await native.compare(password + 'wrong', hash), false)
  }
})

test('new native hashes keep the existing algorithm and configured cost', async () => {
  const hash = await native.hash('Compatibility-only-9!', 12)
  assert.equal(native.getRounds(hash), 12)
  assert.match(hash, /^\$2b\$12\$/)
  assert.equal(await legacy.compare('Compatibility-only-9!', hash), true)
})
