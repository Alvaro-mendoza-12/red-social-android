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

test('canonical privacy overrides stale compatibility values and stays internal',()=>{
 const result=sanitizeProfileRow({id:'owner-id',profile_view_history_enabled:true,
   __profile_override:{profile_view_history_enabled:false}},null)
 assert.equal(result.profile_view_history_enabled,false)
 assert.equal(result.__profile_override,undefined)
})

test('foreign compatibility profiles redact private contact fields',()=>{
 const original={id:'owner-id',email:'primary@fixture.invalid',secondary_email:'backup@fixture.invalid',phone:'+000123'}
 const result=sanitizeProfileRow(original,{ids:new Set(['viewer-id'])})
 for(const key of ['email','secondary_email','phone'])assert.equal(result[key],undefined)
 assert.equal(original.phone,'+000123')
 assert.equal(original.secondary_email,'backup@fixture.invalid')
})

test('the profile owner retains private contact fields',()=>{
 const original={id:'owner-id',email:'primary@fixture.invalid',secondary_email:'backup@fixture.invalid',phone:'+000123'}
 const result=sanitizeProfileRow(original,{ids:new Set(['owner-id'])})
 for(const key of ['email','secondary_email','phone'])assert.equal(result[key],original[key])
})
