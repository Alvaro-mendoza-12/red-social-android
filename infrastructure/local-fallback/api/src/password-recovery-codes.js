const crypto = require('node:crypto')
const { createRecoveryCodes } = require('./two-factor')

const RECOVERY_CODE_PATTERN = /^[A-Z2-7]{16}$/

function normalizePasswordRecoveryCode(value) {
  const normalized = String(value || '').toUpperCase().replace(/[\s-]/g, '')
  return RECOVERY_CODE_PATTERN.test(normalized) ? normalized : null
}

function hashPasswordRecoveryCode(value, secret) {
  const code = normalizePasswordRecoveryCode(value)
  if (!code || String(secret || '').length < 32) return null
  return crypto.createHmac('sha256', secret)
    .update(`novvin-password-recovery-v1:${code}`)
    .digest('hex')
}

function issuePasswordRecoveryCodes(count = 10) {
  return createRecoveryCodes(count)
}

module.exports = {
  hashPasswordRecoveryCode,
  issuePasswordRecoveryCodes,
  normalizePasswordRecoveryCode,
}
