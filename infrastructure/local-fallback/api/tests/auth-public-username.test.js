const { test } = require('node:test')
const assert = require('node:assert/strict')
const { publicUsername, hydrateAuthPublicUsername } = require('../src/auth-public-username')

test('public handles never reveal the login address', () => {
  assert.equal(publicUsername(' alvaro '), 'alvaro')
  assert.equal(publicUsername('alvaro@example.com'), null)
  assert.equal(publicUsername(''), null)
  assert.equal(publicUsername('x'.repeat(25)), null)
})
test('login and registration load the permanent profile username by account id', async () => {
  const original = { id: 'owned-user', email: 'private@example.invalid', display_name: 'Álvaro' }
  const pool = { query: async (sql, params) => {
    assert.match(sql, /fallback_profile_overrides/)
    assert.deepEqual(params, [original.id])
    return { rows: [{ username: 'alvaro_03' }] }
  } }
  const hydrated = await hydrateAuthPublicUsername(pool, original)
  assert.equal(hydrated.username, 'alvaro_03')
  assert.equal(hydrated.email, original.email)
  assert.equal(original.username, undefined)
})
test('accounts without a profile handle do not derive one from their email', async () => {
  const pool = { query: async () => ({ rows: [] }) }
  assert.equal((await hydrateAuthPublicUsername(pool, { id: 'owned-user', email: 'private@example.invalid' })).username, null)
  assert.equal(await hydrateAuthPublicUsername(pool, null), null)
})
