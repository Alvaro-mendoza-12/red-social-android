'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { allocateLegacyHandle } = require('../scripts/migrate-legacy-usernames')

test('legacy visible names receive stable normalized unique handles', () => {
  const owners = new Map([['alvaro', new Set(['account-1'])]])
  assert.equal(allocateLegacyHandle('Álvaro', 'account-1', owners), 'alvaro')
  assert.equal(allocateLegacyHandle('Álvaro', 'account-2', owners), 'alvaro2')
  owners.set('alvaro2', new Set(['account-2']))
  assert.equal(allocateLegacyHandle('Álvaro', 'account-3', owners), 'alvaro3')
  assert.equal(allocateLegacyHandle('ADMIN', 'account-4', owners), 'usuarioadmin')
})

test('legacy handle allocation respects twenty-four character limit', () => {
  const name = 'Un nombre extremadamente largo'
  const first = allocateLegacyHandle(name, 'one', new Map())
  const second = allocateLegacyHandle(name, 'two', new Map([[first, new Set(['one'])]]))
  assert.ok(first.length <= 24)
  assert.ok(second.length <= 24)
  assert.notEqual(first, second)
})
