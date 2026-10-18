const test = require('node:test')
const assert = require('node:assert/strict')
const { generateEmailCode, emailCodeHash, matchesCode, createEmailRecoveryStore } = require('../src/password-reset-email-code')
const secret = 'test-email-recovery-private-key-32-characters'

test('email codes have exactly six digits; hashing binds the email and preserves leading zeros', () => {
  for (let n = 0; n < 300; n++) assert.match(generateEmailCode(), /^[0-9]{6}$/)
  const a = emailCodeHash('Person@Example.org', '000123', secret)
  assert.equal(a, emailCodeHash('person@example.org', '000123', secret))
  assert.notEqual(a, emailCodeHash('other@example.org', '000123', secret))
  assert.notEqual(a, emailCodeHash('person@example.org', '123', secret))
  assert.equal(matchesCode(a, a), true)
  assert.equal(matchesCode(a, '00'), false)
})

function fixture({ expired = false, unknown = false, mayIssueMail } = {}) {
  const calls = []
  const row = { code_hash: emailCodeHash('person@example.org', '000123', secret), attempts: 0, consumed: false }
  const client = {
    async query(sql, params = []) {
      calls.push({ sql, params })
      if (sql.includes('SELECT id FROM fallback_users')) return { rows: unknown ? [] : [{ id: 'fixture-user' }] }
      if (sql.includes('SELECT code_hash, attempts')) {
        assert.match(sql, /expires_at > NOW\(\)/)
        return { rows: expired || row.consumed ? [] : [row] }
      }
      if (sql.includes('SET attempts = attempts + 1')) { row.attempts++; row.consumed = row.attempts >= 5 }
      else if (sql.includes('UPDATE fallback_password_reset_email_codes SET consumed_at')) row.consumed = true
      return { rows: [] }
    },
    release() { calls.push({ sql: 'RELEASE' }) },
  }
  const store = createEmailRecoveryStore({ pool: { connect: async () => client }, secret,
    makeToken: () => 'a'.repeat(64), hashToken: (token) => 'hash:' + token, mayIssueMail })
  return { store, row, calls }
}

test('suppressed requests leave the existing code and reset grant usable', async () => {
  for (const legacy of [false, true]) {
    const { store, calls } = fixture({ mayIssueMail: async () => false })
    const result = legacy ? await store.issueLegacy('fixture-user', 'hash', 15, {})
      : await store.issue('fixture-user', 'person@example.org')
    assert.equal(result, legacy ? false : null)
    assert(calls.some(c => c.sql.includes('FOR UPDATE')))
    assert(!calls.some(c => /^(UPDATE|INSERT)/.test(c.sql)))
    assert(calls.some(c => c.sql === 'COMMIT'))
  }
})

test('the code is consumed before a separate password-reset grant is issued', async () => {
  const { store, calls } = fixture()
  const result = await store.verify('person@example.org', '000123')
  assert.equal(result.resetToken, 'a'.repeat(64))
  assert.equal(result.expiresInMinutes, 10)
  assert.equal(await store.verify('person@example.org', '000123'), null)
  const insert = calls.find(x => x.sql.includes('INSERT INTO fallback_password_reset_tokens'))
  assert.equal(insert.params[1], 'hash:' + 'a'.repeat(64))
  assert.match(insert.sql, /10 minutes/)
  assert(calls.find(x => x.sql === 'COMMIT'))
})

test('five incorrect attempts lock the code even if a correct guess follows', async () => {
  const { store, row, calls } = fixture()
  for (let n = 0; n < 5; n++) assert.equal(await store.verify('person@example.org', '999999'), null)
  assert.equal(row.attempts, 5)
  assert.equal(await store.verify('person@example.org', '000123'), null)
  assert(!calls.find(x => x.sql.includes('INSERT INTO fallback_password_reset_tokens')))
})

test('unknown, expired and malformed codes do not issue a reset grant', async () => {
  assert.equal(await fixture({ unknown: true }).store.verify('person@example.org', '000123'), null)
  assert.equal(await fixture({ expired: true }).store.verify('person@example.org', '000123'), null)
  assert.equal(await fixture().store.verify('person@example.org', 'abcdef'), null)
})

test('request serializes on the account, stores a hash and invalidates old grants', async () => {
  const { store, calls } = fixture()
  const code = await store.issue('fixture-user', 'person@example.org')
  assert.match(code, /^[0-9]{6}$/)
  assert.match(calls[1].sql, /FOR UPDATE/)
  const insert = calls.find(x => x.sql.includes('INSERT INTO fallback_password_reset_email_codes'))
  assert.equal(insert.params[1], emailCodeHash('person@example.org', code, secret))
  assert(!calls.some(x => x.params?.includes(code)))
  assert(calls.some(x => x.sql.includes('UPDATE fallback_password_reset_tokens')))
})
