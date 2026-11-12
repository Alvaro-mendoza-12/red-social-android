'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const source = fs.readFileSync(path.join(__dirname, '../src/server.js'), 'utf8')
const start = source.indexOf("app.post('/v1/auth/login',")
const end = source.indexOf("app.post('/v1/auth/refresh',", start)
assert(start >= 0 && end > start)

async function login(row) {
  let handler
  let comparisons = 0
  vm.runInNewContext(source.slice(start, end), {
    app: { post(_path, callback) { handler = callback } },
    isWebClientRequest: () => false, normalizeEmail: value => value.toLowerCase(), isValidEmail: () => true,
    pool: { query: async () => ({ rows: row ? [row] : [] }) }, userSelectColumns: 'id,password_hash',
    bcrypt: { compare: async () => { comparisons++; return false } },
    verifyRestoredSupabasePassword: async () => false, recordAudit: async () => {},
  })
  const result = {}
  const res = { status(value) { result.status = value; return res }, json(value) { result.body = JSON.parse(JSON.stringify(value)); return res } }
  await handler({ body: { email: 'test@example.invalid', password: 'Wrong password' } }, res, error => { throw error })
  return { ...result, comparisons }
}

test('failed login does not disclose whether an address is absent, local, or migrated', async () => {
  const missing = await login(null)
  const local = await login({ id: 'local', password_enabled: true, password_hash: 'hash' })
  const migrated = await login({ id: 'migrated', password_enabled: false })
  for (const result of [missing, local, migrated]) {
    assert.equal(result.status, 401)
    assert.equal(result.body.error, 'INVALID_CREDENTIALS')
    assert.deepEqual(result.body, missing.body)
  }
  // An absent address still incurs one bcrypt comparison instead of returning
  // immediately after the account lookup. This is not a timing-proof claim.
  assert.equal(missing.comparisons, 1)
})
