const test = require('node:test')
const assert = require('node:assert/strict')
const { createPasswordMailCipher, createPasswordMailOutbox, retryDelaySeconds, mailErrorCode } = require('../src/password-reset-mail-outbox')
const secret = 'private-unit-fixture-key-with-at-least-32-characters'

test('encrypted mail is bound to its job and hides the recipient and recovery code', () => {
  const cipher = createPasswordMailCipher(secret)
  const payload = { email: 'qa@example.invalid', token: '000123' }
  const sealed = cipher.seal('job-a', payload)
  assert.deepEqual(cipher.open('job-a', sealed), payload)
  assert(!sealed.includes(payload.email) && !sealed.includes(payload.token))
  assert.throws(() => cipher.open('job-b', sealed))
  assert.throws(() => createPasswordMailCipher('short'))
  assert.throws(() => createPasswordMailCipher(secret + 'other').open('job-a', sealed))
})
test('retries are bounded and error diagnostics exclude raw SMTP content', () => {
  assert.deepEqual([1, 2, 3, 4, 5, 9].map(retryDelaySeconds), [1, 2, 4, 8, 16, 60])
  assert.equal(mailErrorCode({ code: 'ECONNECTION' }), 'ECONNECTION')
  assert.equal(mailErrorCode({ code: 'password=secret\nrecipient@example.com' }), 'MAIL_DELIVERY_FAILED')
})
function fixture({ valid = true, sendError, attempts = 1 } = {}) {
  const calls = [], sent = []
  const cipher = createPasswordMailCipher(secret)
  let claimed = false
  const pool = { async query(sql, params = []) {
    calls.push({ sql, params })
    if (sql.startsWith('WITH due')) {
      if (claimed) return { rows: [] }
      claimed = true
      return { rows: [{ id: 'job-a', user_id: 'user-a', kind: 'numeric', code_hash: 'hash', attempts,
        expires_at: new Date(Date.now() + 60_000), ciphertext: cipher.seal('job-a', { email: 'qa@example.invalid', token: '000123' }) }] }
    }
    if (sql.startsWith('SELECT 1 FROM')) return { rows: valid ? [{}] : [] }
    return { rows: [] }
  } }
  const outbox = createPasswordMailOutbox({ pool, secret, mailer: { async deliver(...args) {
    if (sendError) throw sendError
    sent.push(args)
  } } })
  return { pool, calls, sent, outbox }
}
test('enqueue replaces older deliveries and persists only an authenticated encrypted payload', async () => {
  const { pool, calls, outbox } = fixture()
  await outbox.enqueue(pool, { userId: 'user-a', email: 'qa@example.invalid', token: '000123', codeHash: 'hash', minutes: 15, kind: 'numeric' })
  assert.match(calls[0].sql, /status='cancelled'/)
  const insert = calls[1]
  assert.match(insert.sql, /INSERT INTO/)
  assert(!JSON.stringify(insert).includes('000123') && !JSON.stringify(insert).includes('qa@example.invalid'))
  assert.deepEqual(createPasswordMailCipher(secret).open(insert.params[0], insert.params[4]), { email: 'qa@example.invalid', token: '000123' })
})
test('successful delivery validates the current code and wipes its encrypted payload', async () => {
  const { outbox, calls, sent } = fixture()
  assert.equal(await outbox.drain(), 1)
  assert.equal(sent.length, 1)
  assert.match(calls.find(c => c.sql.startsWith('SELECT 1 FROM')).sql, /consumed_at IS NULL/)
  assert(calls.some(c => c.sql.includes("status='sent',ciphertext=NULL")))
  await outbox.stop()
  assert.equal(await outbox.drain(), 0)
})
test('superseded or consumed codes are cancelled without sending', async () => {
  const { outbox, sent, calls } = fixture({ valid: false })
  await outbox.drain()
  assert.equal(sent.length, 0)
  assert(calls.some(c => c.sql.includes("status='cancelled',ciphertext=NULL")))
})
test('transient failures retry while permanent and exhausted failures wipe secrets', async () => {
  for (const [options, expected] of [
    [{ sendError: { code: 'ECONNECTION' } }, 'pending'],
    [{ sendError: { code: 'EAUTH' } }, 'failed'],
    [{ sendError: { responseCode: 550 } }, 'failed'],
    [{ sendError: { code: 'ETIMEDOUT' }, attempts: 5 }, 'failed'],
  ]) {
    const { outbox, calls } = fixture(options)
    await outbox.drain()
    assert.equal(calls.find(c => c.sql.includes('SET status=$2')).params[1], expected)
  }
})
