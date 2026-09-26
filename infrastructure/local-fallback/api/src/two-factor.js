const crypto = require('node:crypto')

const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
const PERIOD_SECONDS = 30
const DIGITS = 6

function factorKey(masterSecret) {
  if (String(masterSecret || '').length < 32) throw new Error('TWO_FACTOR_KEY_UNAVAILABLE')
  return Buffer.from(crypto.hkdfSync(
    'sha256', Buffer.from(masterSecret), Buffer.from('novvin-2fa-v1'),
    Buffer.from('totp-encryption'), 32,
  ))
}

function encodeBase32(bytes) {
  let bits = 0
  let value = 0
  let output = ''
  for (const byte of bytes) {
    value = (value << 8) | byte
    bits += 8
    while (bits >= 5) {
      output += ALPHABET[(value >>> (bits - 5)) & 31]
      bits -= 5
    }
  }
  if (bits) output += ALPHABET[(value << (5 - bits)) & 31]
  return output
}

function decodeBase32(text) {
  const source = String(text || '').replace(/=+$/, '').toUpperCase()
  if (!source || !/^[A-Z2-7]+$/.test(source)) throw new Error('INVALID_TOTP_SECRET')
  let bits = 0
  let value = 0
  const bytes = []
  for (const character of source) {
    value = (value << 5) | ALPHABET.indexOf(character)
    bits += 5
    if (bits >= 8) {
      bytes.push((value >>> (bits - 8)) & 255)
      bits -= 8
    }
  }
  return Buffer.from(bytes)
}

function generateSecret() {
  return encodeBase32(crypto.randomBytes(20))
}

function encryptSecret(secret, masterSecret) {
  const nonce = crypto.randomBytes(12)
  const cipher = crypto.createCipheriv('aes-256-gcm', factorKey(masterSecret), nonce)
  const encrypted = Buffer.concat([cipher.update(secret, 'utf8'), cipher.final()])
  return Buffer.concat([nonce, cipher.getAuthTag(), encrypted]).toString('base64url')
}

function decryptSecret(stored, masterSecret) {
  const bytes = Buffer.from(String(stored || ''), 'base64url')
  if (bytes.length < 29) throw new Error('INVALID_TOTP_CIPHERTEXT')
  const decipher = crypto.createDecipheriv(
    'aes-256-gcm', factorKey(masterSecret), bytes.subarray(0, 12),
  )
  decipher.setAuthTag(bytes.subarray(12, 28))
  return Buffer.concat([decipher.update(bytes.subarray(28)), decipher.final()]).toString('utf8')
}

function codeAtStep(secret, step) {
  const counter = Buffer.alloc(8)
  counter.writeBigUInt64BE(BigInt(step))
  const digest = crypto.createHmac('sha1', decodeBase32(secret)).update(counter).digest()
  const offset = digest[digest.length - 1] & 15
  const value = (digest.readUInt32BE(offset) & 0x7fffffff) % (10 ** DIGITS)
  return String(value).padStart(DIGITS, '0')
}

function matchingStep(secret, suppliedCode, nowMs = Date.now(), lastUsedStep = -1) {
  const code = String(suppliedCode || '').trim()
  if (!/^\d{6}$/.test(code)) return null
  const current = Math.floor(nowMs / 1000 / PERIOD_SECONDS)
  for (const step of [current, current - 1, current + 1]) {
    if (step <= lastUsedStep || step < 0) continue
    const expected = codeAtStep(secret, step)
    if (crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(code))) return step
  }
  return null
}

function createRecoveryCodes(count = 10) {
  return Array.from({ length: count }, () => {
    const code = encodeBase32(crypto.randomBytes(10))
    return code.match(/.{1,4}/g).join('-')
  })
}

function recoveryHash(code, masterSecret) {
  const normalized = String(code || '').toUpperCase().replace(/[^A-Z2-7]/g, '')
  if (normalized.length !== 16) return null
  return crypto.createHmac('sha256', factorKey(masterSecret))
    .update(`recovery:${normalized}`).digest('hex')
}

function provisioningUri(secret, email) {
  const issuer = 'Novvin'
  return `otpauth://totp/${encodeURIComponent(`${issuer}:${email}`)}` +
    `?secret=${secret}&issuer=${encodeURIComponent(issuer)}` +
    `&algorithm=SHA1&digits=${DIGITS}&period=${PERIOD_SECONDS}`
}

module.exports = {
  codeAtStep,
  createRecoveryCodes,
  decryptSecret,
  encryptSecret,
  generateSecret,
  matchingStep,
  provisioningUri,
  recoveryHash,
}
