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
    decision_id: 'decision-1',
    rule_code: 'fraud_scam',
    rule_title: 'Fraude o estafa',
    explanation: 'Actividad fraudulenta documentada',
    evidence: [
      { source: 'report', reference: 'private-reference', label: 'Reporte revisado por moderación' },
    ],
    severity: 'critical',
    appeal_deadline: '2030-01-10T00:00:00.000Z',
    appeal_id: 'appeal-1',
    appeal_status: 'pending',
    appeal_reviewer_note: null,
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
  assert.equal(payload.decision_id, 'decision-1')
  assert.equal(payload.rule_code, 'fraud_scam')
  assert.equal(payload.rule_title, 'Fraude o estafa')
  assert.equal(payload.explanation, 'Actividad fraudulenta documentada')
  assert.deepEqual(payload.evidence, [
    { source: 'report', label: 'Reporte revisado por moderación' },
  ])
  assert.equal(payload.evidence[0].reference, undefined)
  assert.equal(payload.severity, 'critical')
  assert.equal(payload.appeal_deadline, '2030-01-10T00:00:00.000Z')
  assert.equal(payload.appeal_status, 'pending')
  assert.equal(await findActiveSuspension({ async query() { assert.fail('unexpected query') } }, ''), null)

  console.log('account status tests passed')
}

run().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
