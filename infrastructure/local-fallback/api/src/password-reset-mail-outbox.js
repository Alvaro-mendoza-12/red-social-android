const crypto = require('node:crypto')

const MAX_ATTEMPTS = 5
function retryDelaySeconds(attempt) { return Math.min(60, 2 ** Math.max(0, attempt - 1)) }
function mailErrorCode(error) {
  const code = String(error?.code || 'MAIL_DELIVERY_FAILED').toUpperCase()
  return /^[A-Z0-9_]{1,64}$/.test(code) ? code : 'MAIL_DELIVERY_FAILED'
}
function createPasswordMailCipher(secret) {
  if (String(secret || '').length < 32) throw new Error('Recovery mail requires a private encryption key')
  const key = crypto.createHash('sha256').update(`novvin:password-mail:key:v1:${secret}`).digest()
  return {
    seal(id, payload) {
      const nonce = crypto.randomBytes(12)
      const cipher = crypto.createCipheriv('aes-256-gcm', key, nonce)
      cipher.setAAD(Buffer.from(`novvin:password-mail:v1:${id}`))
      const encrypted = Buffer.concat([cipher.update(JSON.stringify(payload), 'utf8'), cipher.final()])
      return Buffer.concat([nonce, cipher.getAuthTag(), encrypted]).toString('base64')
    },
    open(id, sealed) {
      const bytes = Buffer.from(sealed, 'base64')
      if (bytes.length < 29) throw new Error('INVALID_MAIL_ENVELOPE')
      const decipher = crypto.createDecipheriv('aes-256-gcm', key, bytes.subarray(0, 12))
      decipher.setAAD(Buffer.from(`novvin:password-mail:v1:${id}`))
      decipher.setAuthTag(bytes.subarray(12, 28))
      return JSON.parse(Buffer.concat([decipher.update(bytes.subarray(28)), decipher.final()]).toString('utf8'))
    },
  }
}

async function ensurePasswordMailOutboxSchema(pool) {
  await pool.query(`CREATE TABLE IF NOT EXISTS fallback_password_mail_outbox (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
    kind TEXT NOT NULL CHECK (kind IN ('numeric', 'legacy')), code_hash TEXT NOT NULL,
    ciphertext TEXT, status TEXT NOT NULL DEFAULT 'pending'
      CHECK (status IN ('pending', 'delivering', 'sent', 'cancelled', 'failed')),
    attempts INTEGER NOT NULL DEFAULT 0, run_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    lease_until TIMESTAMPTZ, expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), delivered_at TIMESTAMPTZ, last_error_code TEXT
  )`)
  await pool.query(`CREATE INDEX IF NOT EXISTS idx_password_mail_due
    ON fallback_password_mail_outbox(run_at) WHERE status IN ('pending', 'delivering')`)
  await pool.query(`CREATE INDEX IF NOT EXISTS idx_password_mail_user ON fallback_password_mail_outbox(user_id)`)
}

/** The code and the encrypted mail are committed together; SMTP never delays the HTTP response. */
function createPasswordMailOutbox({ pool, secret, mailer, onError = () => {} }) {
  const cipher = createPasswordMailCipher(secret)
  let running = null
  let stopped = false
  async function enqueue(client, { userId, email, token, codeHash, minutes, kind }) {
    if (!['numeric', 'legacy'].includes(kind) || !Number.isInteger(minutes) || minutes < 1 || minutes > 60 ||
        !email || !token || !codeHash) throw new Error('INVALID_MAIL_JOB')
    // The issuer already holds the account lock. Superseded codes cannot be retried later.
    await client.query(`UPDATE fallback_password_mail_outbox SET status='cancelled', ciphertext=NULL,
      lease_until=NULL WHERE user_id=$1 AND status IN ('pending','delivering')`, [userId])
    const id = crypto.randomUUID()
    await client.query(`INSERT INTO fallback_password_mail_outbox(id,user_id,kind,code_hash,ciphertext,expires_at)
      VALUES ($1,$2,$3,$4,$5,NOW()+($6::text || ' minutes')::interval)`,
    [id, userId, kind, codeHash, cipher.seal(id, { email, token }), minutes])
  }
  async function processDue(limit) {
    await pool.query(`UPDATE fallback_password_mail_outbox SET status='cancelled',ciphertext=NULL,lease_until=NULL
      WHERE status IN ('pending','delivering') AND expires_at<=NOW()`)
    await pool.query(`UPDATE fallback_password_mail_outbox SET status='failed',ciphertext=NULL,lease_until=NULL
      WHERE status='delivering' AND lease_until<=NOW() AND attempts>=$1`, [MAX_ATTEMPTS])
    let processed = 0
    while (!stopped && processed < limit) {
      const { rows } = await pool.query(`WITH due AS (
        SELECT id FROM fallback_password_mail_outbox
        WHERE expires_at>NOW() AND ciphertext IS NOT NULL AND attempts<$1
          AND ((status='pending' AND run_at<=NOW()) OR (status='delivering' AND lease_until<=NOW()))
        ORDER BY run_at FOR UPDATE SKIP LOCKED LIMIT 1
      ) UPDATE fallback_password_mail_outbox job SET status='delivering',attempts=job.attempts+1,
          lease_until=NOW()+INTERVAL '2 minutes'
        FROM due WHERE job.id=due.id RETURNING job.*`, [MAX_ATTEMPTS])
      const job = rows[0]
      if (!job) break
      processed++
      try {
        const payload = cipher.open(job.id, job.ciphertext)
        const source = job.kind === 'numeric' ? 'fallback_password_reset_email_codes' : 'fallback_password_reset_tokens'
        const hashColumn = job.kind === 'numeric' ? 'code_hash' : 'token_hash'
        const valid = await pool.query(`SELECT 1 FROM ${source} code
          JOIN fallback_users account ON account.id=code.user_id
          JOIN fallback_password_mail_outbox mail ON mail.user_id=code.user_id
          WHERE mail.id=$1 AND mail.status='delivering' AND code.user_id=$2 AND code.${hashColumn}=$3
            AND code.consumed_at IS NULL AND code.expires_at>NOW() AND account.email=$4`,
        [job.id, job.user_id, job.code_hash, payload.email])
        if (!valid.rows[0]) {
          await pool.query(`UPDATE fallback_password_mail_outbox SET status='cancelled',ciphertext=NULL,
            lease_until=NULL WHERE id=$1`, [job.id])
          continue
        }
        const minutes = Math.max(1, Math.ceil((new Date(job.expires_at).getTime() - Date.now()) / 60_000))
        await mailer.deliver(payload.email, payload.token, minutes)
        await pool.query(`UPDATE fallback_password_mail_outbox SET status='sent',ciphertext=NULL,
          lease_until=NULL,delivered_at=NOW(),last_error_code=NULL WHERE id=$1 AND status='delivering'`, [job.id])
      } catch (error) {
        const permanent = ['EAUTH', 'INVALID_MAIL_ENVELOPE'].includes(mailErrorCode(error)) ||
          (Number(error?.responseCode) >= 500 && Number(error?.responseCode) < 600) || job.attempts >= MAX_ATTEMPTS
        await pool.query(`UPDATE fallback_password_mail_outbox
          SET status=$2,ciphertext=CASE WHEN $2='failed' THEN NULL ELSE ciphertext END,
              lease_until=NULL,run_at=NOW()+($3::text || ' seconds')::interval,last_error_code=$4
          WHERE id=$1 AND status='delivering'`,
        [job.id, permanent ? 'failed' : 'pending', retryDelaySeconds(job.attempts), mailErrorCode(error)])
        onError(mailErrorCode(error))
      }
    }
    // Terminal jobs no longer contain recipient or code; retain counters for at most one day.
    await pool.query(`DELETE FROM fallback_password_mail_outbox
      WHERE status IN ('sent','cancelled','failed') AND created_at<NOW()-INTERVAL '1 day'`)
    return processed
  }
  function drain(limit = 4) {
    if (stopped || running) return running || Promise.resolve(0)
    running = processDue(limit).finally(() => { running = null })
    return running
  }
  function wake() { setImmediate(() => { drain().catch((error) => onError(mailErrorCode(error))) }) }
  async function stop() { stopped = true; await running }
  return { enqueue, drain, wake, stop }
}
module.exports = { createPasswordMailCipher, createPasswordMailOutbox, ensurePasswordMailOutboxSchema,
  retryDelaySeconds, mailErrorCode, MAX_ATTEMPTS }
