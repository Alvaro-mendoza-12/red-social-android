'use strict'
// Isolated, rollback-only schema; no real accounts, codes or outgoing mail.
const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const { Client } = require('pg')
const { ensurePasswordMailOutboxSchema, createPasswordMailOutbox } = require('../src/password-reset-mail-outbox')
const { createEmailRecoveryStore, emailCodeHash } = require('../src/password-reset-email-code')

async function verify() {
  const client = new Client({ connectionString: process.env.DATABASE_URL })
  await client.connect()
  const schema = `recovery_mail_qa_${crypto.randomBytes(8).toString('hex')}`
  const secret = crypto.randomBytes(32).toString('hex')
  try {
    await client.query('BEGIN')
    await client.query(`CREATE SCHEMA "${schema}"`)
    await client.query(`SET LOCAL search_path TO "${schema}"`)
    await client.query('CREATE TABLE fallback_users(id UUID PRIMARY KEY,email TEXT)')
    await client.query(`CREATE TABLE fallback_password_reset_email_codes(user_id UUID PRIMARY KEY,
      code_hash TEXT,attempts INTEGER,expires_at TIMESTAMPTZ,consumed_at TIMESTAMPTZ,created_at TIMESTAMPTZ DEFAULT NOW())`)
    await client.query(`CREATE TABLE fallback_password_reset_tokens(id UUID DEFAULT gen_random_uuid(),
      user_id UUID,token_hash TEXT,expires_at TIMESTAMPTZ,consumed_at TIMESTAMPTZ)`)
    await ensurePasswordMailOutboxSchema(client)
    await ensurePasswordMailOutboxSchema(client)
    const user = crypto.randomUUID(), email = 'outbox-fixture@example.invalid'
    await client.query('INSERT INTO fallback_users(id,email) VALUES($1,$2)', [user, email])
    const transactionClient = {
      query: (sql, params) => client.query(sql === 'BEGIN' ? 'SAVEPOINT mail_fixture' :
        sql === 'COMMIT' ? 'RELEASE SAVEPOINT mail_fixture' :
        sql === 'ROLLBACK' ? 'ROLLBACK TO SAVEPOINT mail_fixture' : sql, params),
      release() {},
    }
    const pool = { query: (...args) => client.query(...args), connect: async () => transactionClient }
    const sent = []
    let fail = true
    const outbox = createPasswordMailOutbox({ pool, secret, mailer: { async deliver(...args) {
      if (fail) throw { code: 'ECONNECTION' }
      sent.push(args)
    } } })
    const store = createEmailRecoveryStore({ pool, secret, makeToken: () => crypto.randomBytes(32).toString('hex'),
      hashToken: t => crypto.createHash('sha256').update(t).digest('hex'), enqueueMail: outbox.enqueue })
    const first = await store.issue(user, email)
    const firstJob = (await client.query('SELECT * FROM fallback_password_mail_outbox')).rows[0]
    assert.equal(firstJob.code_hash, emailCodeHash(email, first, secret))
    assert(!firstJob.ciphertext.includes(email) && !firstJob.ciphertext.includes(first))
    await outbox.drain()
    assert.equal((await client.query('SELECT status FROM fallback_password_mail_outbox')).rows[0].status, 'pending')
    fail = false
    await client.query('UPDATE fallback_password_mail_outbox SET run_at=NOW()')
    await outbox.drain()
    assert.equal(sent.length, 1)
    assert.equal(sent[0][1], first)
    assert.equal((await client.query('SELECT ciphertext FROM fallback_password_mail_outbox')).rows[0].ciphertext, null)
    const second = await store.issue(user, email)
    await store.issue(user, email)
    const pending = (await client.query("SELECT count(*)::int AS n FROM fallback_password_mail_outbox WHERE status='pending'")).rows[0].n
    assert.equal(pending, 1)
    assert.equal(await store.verify(email, second), null)
    await outbox.drain()
    assert.equal(sent.length, 2)
    await client.query('DELETE FROM fallback_users WHERE id=$1', [user])
    assert.equal((await client.query('SELECT count(*)::int AS n FROM fallback_password_mail_outbox')).rows[0].n, 0)
    await outbox.stop()
    console.log(JSON.stringify({ passed: true, atomicCodeAndMail: true, encryptedAtRest: true,
      transientRetry: true, supersededMailCancelled: true, payloadWipedAfterSend: true,
      deletedUserJobsRemoved: true, actualEmailsSent: 0, scope: 'Isolated PostgreSQL schema rolled back; fake SMTP' }))
  } finally {
    await client.query('ROLLBACK')
    await client.end()
  }
}
verify().catch(() => { console.error('Isolated mail outbox verification failed'); process.exitCode = 1 })
