const crypto = require('node:crypto')
const CODE_MINUTES = 15
const GRANT_MINUTES = 10
const MAX_ATTEMPTS = 5

function generateEmailCode() { return String(crypto.randomInt(0, 1_000_000)).padStart(6, '0') }
function emailCodeHash(email, code, secret) {
  return crypto.createHmac('sha256', secret).update(`novvin:email-password-code:v1:${email.trim().toLowerCase()}:${code}`).digest('hex')
}
function matchesCode(expected, actual) {
  const a = Buffer.from(expected, 'hex'), b = Buffer.from(actual, 'hex')
  return a.length === 32 && b.length === 32 && crypto.timingSafeEqual(a, b)
}

function createEmailRecoveryStore({ pool, secret, makeToken, hashToken, enqueueMail }) {
  if (!secret || secret.length < 32) throw new Error('Email recovery requires a private hashing key')
  async function transaction(task) {
    const client = await pool.connect()
    try {
      await client.query('BEGIN')
      const result = await task(client)
      await client.query('COMMIT')
      return result
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {})
      throw error
    } finally { client.release() }
  }
  return {
    async issue(userId, email) {
      const code = generateEmailCode()
      await transaction(async (client) => {
        const account = await client.query('SELECT id FROM fallback_users WHERE id = $1 FOR UPDATE', [userId])
        if (!account.rows[0]) throw new Error('Email recovery account unavailable')
        await client.query('UPDATE fallback_password_reset_tokens SET consumed_at = NOW() WHERE user_id = $1 AND consumed_at IS NULL', [userId])
        await client.query(`INSERT INTO fallback_password_reset_email_codes(user_id, code_hash, attempts, expires_at, consumed_at)
          VALUES ($1, $2, 0, NOW() + INTERVAL '15 minutes', NULL)
          ON CONFLICT (user_id) DO UPDATE SET code_hash = EXCLUDED.code_hash, attempts = 0,
          expires_at = EXCLUDED.expires_at, consumed_at = NULL, created_at = NOW()`, [userId, emailCodeHash(email, code, secret)])
        if (enqueueMail) await enqueueMail(client, { userId, email, token: code,
          codeHash: emailCodeHash(email, code, secret), minutes: CODE_MINUTES, kind: 'numeric' })
      })
      return code
    },
    async issueLegacy(userId, tokenHash, minutes, delivery) {
      await transaction(async (client) => {
        await client.query('SELECT id FROM fallback_users WHERE id = $1 FOR UPDATE', [userId])
        await client.query('UPDATE fallback_password_reset_email_codes SET consumed_at = NOW() WHERE user_id = $1 AND consumed_at IS NULL', [userId])
        await client.query('UPDATE fallback_password_reset_tokens SET consumed_at = NOW() WHERE user_id = $1 AND consumed_at IS NULL', [userId])
        await client.query(`INSERT INTO fallback_password_reset_tokens(user_id, token_hash, expires_at)
          VALUES ($1, $2, NOW() + ($3::text || ' minutes')::interval)`, [userId, tokenHash, minutes])
        if (enqueueMail && delivery) await enqueueMail(client, { userId, ...delivery,
          codeHash: tokenHash, minutes, kind: 'legacy' })
      })
    },
    async verify(email, code) {
      if (!/^[0-9]{6}$/.test(code)) return null
      return transaction(async (client) => {
        // Always acquire the account lock first, matching issue() and reset/confirm.
        const account = await client.query('SELECT id FROM fallback_users WHERE email = $1 FOR UPDATE', [email])
        const userId = account.rows[0]?.id
        if (!userId) return null
        const result = await client.query(`SELECT code_hash, attempts FROM fallback_password_reset_email_codes
          WHERE user_id = $1 AND consumed_at IS NULL AND expires_at > NOW() FOR UPDATE`, [userId])
        const row = result.rows[0]
        if (!row || row.attempts >= MAX_ATTEMPTS) return null
        if (!matchesCode(row.code_hash, emailCodeHash(email, code, secret))) {
          await client.query(`UPDATE fallback_password_reset_email_codes SET attempts = attempts + 1,
            consumed_at = CASE WHEN attempts + 1 >= $2 THEN NOW() ELSE consumed_at END WHERE user_id = $1`, [userId, MAX_ATTEMPTS])
          return null
        }
        const resetToken = makeToken()
        await client.query('UPDATE fallback_password_reset_email_codes SET consumed_at = NOW() WHERE user_id = $1', [userId])
        await client.query('UPDATE fallback_password_reset_tokens SET consumed_at = NOW() WHERE user_id = $1 AND consumed_at IS NULL', [userId])
        await client.query(`INSERT INTO fallback_password_reset_tokens(user_id, token_hash, expires_at)
          VALUES ($1, $2, NOW() + INTERVAL '10 minutes')`, [userId, hashToken(resetToken)])
        return { ok: true, resetToken, expiresInMinutes: GRANT_MINUTES }
      })
    },
  }
}
module.exports = { createEmailRecoveryStore, generateEmailCode, emailCodeHash, matchesCode, CODE_MINUTES, GRANT_MINUTES, MAX_ATTEMPTS }
