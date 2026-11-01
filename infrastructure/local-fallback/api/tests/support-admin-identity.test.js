const test = require('node:test')
const assert = require('node:assert/strict')
const { trustedSupportCandidate } = require('../src/data-routes')

test('an email or profile flag cannot substitute for support authorization', async () => {
  const fake = { local_id: 'ordinary', public_id: 'ordinary-public', email: 'support@example.test', is_admin: true }
  assert.equal(await trustedSupportCandidate({}, [fake], new Set(), async () => ({ level: 'none' })), null)
})

test('a revoked candidate is skipped and a currently authorized contact is returned', async () => {
  const revoked = { local_id: 'revoked', public_id: 'revoked-public' }
  const active = { local_id: 'active', public_id: 'active-public' }
  const calls = []
  const target = await trustedSupportCandidate({}, [revoked, active], new Set(), async (_, identity) => {
    calls.push(identity.values)
    return { level: identity.localUserId === 'active' ? 'write' : 'none' }
  })
  assert.equal(target, active)
  assert.deepEqual(calls, [['revoked', 'revoked-public'], ['active', 'active-public']])
})

test('support prefers another authorized contact over the requesting administrator', async () => {
  const me = { local_id: 'me', public_id: 'me-public' }
  const other = { local_id: 'other', public_id: 'other-public' }
  const target = await trustedSupportCandidate({}, [me, other], new Set(['me-public']), async () => ({ level: 'full' }))
  assert.equal(target, other)
})

test('lookup failures fail closed instead of returning an unverified contact', async () => {
  await assert.rejects(() => trustedSupportCandidate({}, [{ local_id: 'candidate' }], new Set(), async () => {
    throw new Error('database unavailable')
  }), /database unavailable/)
})

test('empty or unidentified candidates cannot become support contacts', async () => {
  let lookups = 0
  const check = async () => { lookups++; return { level: 'full' } }
  assert.equal(await trustedSupportCandidate({}, [], new Set(), check), null)
  assert.equal(await trustedSupportCandidate({}, [{}], new Set(), check), null)
  assert.equal(lookups, 0)
})

test('a missing or unknown permission is never treated as a support grant', async () => {
  for (const access of [null, {}, { level: 'invalid' }]) {
    assert.equal(await trustedSupportCandidate({}, [{ local_id: 'unknown' }], new Set(), async () => access), null)
  }
})
