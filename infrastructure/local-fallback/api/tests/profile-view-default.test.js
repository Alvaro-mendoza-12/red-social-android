const test = require('node:test')
const assert = require('node:assert/strict')

const { _test: { sanitizeProfileRow } } = require('../src/postgrest-compat-routes')

test('profile visits are enabled when the owner has not set a preference', () => {
  const result = sanitizeProfileRow({ id: 'owner-id', display_name: 'Owner' }, null)
  assert.equal(result.profile_view_history_enabled, true)
})

test('an explicit opt-out remains disabled', () => {
  const result = sanitizeProfileRow({ id: 'owner-id', profile_view_history_enabled: false }, null)
  assert.equal(result.profile_view_history_enabled, false)
})

test('public profiles never expose the email while defaulting visits', () => {
  const result = sanitizeProfileRow({ id: 'owner-id', email: 'private@example.com' }, null)
  assert.equal(result.email, undefined)
  assert.equal(result.profile_view_history_enabled, true)
})
