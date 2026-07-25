const assert = require('assert/strict')
const {
  accountSuspendedPayload,
  findActiveSuspension,
  isActiveSuspension,
  suspensionEnd,
} = require('../src/account-status')

async function run() {
  const future = '2030-01-02T00:00:00.000Z'
  const expired = '2020-01-02T00:00:00.000Z'
  assert.equal(isActiveSuspension({ is_suspended: true, suspended_until: null }), true)
  assert.equal(isActiveSuspension({ is_suspended: true, suspended_until: future }, Date.parse('2030-01-01')), true)
  assert.equal(isActiveSuspension({ is_suspended: true, suspended_until: expired }, Date.parse('2030-01-01')), false)
  assert.equal(isActiveSuspension({ is_suspended: false, suspended_until: future }), false)
  assert.equal(suspensionEnd({ suspension_end_date: future }), future)

  let captured = null
  const row = {
    is_suspended: true,
    suspended_until: future,
    suspension_reason: 'Prueba',
  }
  const found = await findActiveSuspension({
    async query(sql, params) {
      captured = { sql: String(sql), params }
      return { rows: [row] }
    },
  }, ' USER-1 ')
  assert.equal(found, row)
  assert.match(captured.sql, /fallback_profile_moderation/)
  assert.deepEqual(captured.params, ['user-1'])

  const payload = accountSuspendedPayload(row)
  assert.equal(payload.error, 'ACCOUNT_SUSPENDED')
  assert.equal(payload.suspended_until, future)
  assert.equal(payload.suspension_reason, 'Prueba')
  assert.equal(await findActiveSuspension({ async query() { assert.fail('unexpected query') } }, ''), null)

  console.log('account status tests passed')
}

run().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
