'use strict'

const crypto = require('node:crypto')
const { createAgreement, changeAgreement, agreementFail: fail } = require('./chat-agreements-domain')

async function ensureChatAgreementTables(db) {
  await db.query(`CREATE TABLE IF NOT EXISTS experimental_chat_agreements (
    id UUID PRIMARY KEY, chat_id TEXT NOT NULL, author_id TEXT NOT NULL, state JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW())`)
  await db.query(`CREATE INDEX IF NOT EXISTS idx_experimental_chat_agreements_room
    ON experimental_chat_agreements(chat_id, created_at DESC, id)`)
  await db.query('ALTER TABLE experimental_chat_agreements ADD COLUMN IF NOT EXISTS archived BOOLEAN NOT NULL DEFAULT FALSE')
  await db.query(`CREATE INDEX IF NOT EXISTS idx_experimental_chat_agreements_page
    ON experimental_chat_agreements(chat_id, archived, created_at DESC, id DESC)`)
  await db.query(`CREATE TABLE IF NOT EXISTS experimental_chat_agreement_operations (
    actor_id TEXT NOT NULL, operation_id UUID NOT NULL, request_hash TEXT NOT NULL,
    agreement_id UUID NOT NULL REFERENCES experimental_chat_agreements(id) ON DELETE CASCADE,
    PRIMARY KEY(actor_id, operation_id))`)
}

/** All writes in a room share a lock, including limits and lost-ACK reconciliation. */
async function writeAgreement(pool, { chatId, agreementId, command, authorize, creating }) {
  const db = await pool.connect()
  try {
    await db.query('BEGIN')
    await db.query("SET LOCAL statement_timeout='5s'")
    await db.query("SET LOCAL lock_timeout='2s'")
    await db.query('SELECT pg_advisory_xact_lock(hashtextextended($1,0))', [`experimental-agreement:${chatId}`])
    const context = await authorize(db)
    const hash = crypto.createHash('sha256').update(JSON.stringify({ chatId, agreementId, creating, command })).digest('hex')
    const receipt = (await db.query(`SELECT request_hash, agreement_id::text FROM experimental_chat_agreement_operations
      WHERE actor_id=$1 AND operation_id=$2::uuid`, [context.actorId, command.operationId])).rows[0]
    if (receipt) {
      if (receipt.request_hash !== hash || receipt.agreement_id !== agreementId) throw fail('AGREEMENT_OPERATION_CONFLICT', 409)
      const current = (await db.query('SELECT state FROM experimental_chat_agreements WHERE chat_id=$1 AND id=$2::uuid', [chatId, agreementId])).rows[0]
      if (!current) throw fail('AGREEMENT_NOT_FOUND', 404)
      await db.query('COMMIT')
      return { state: current.state, context, replayed: true }
    }
    const now = new Date().toISOString()
    let state
    if (creating) {
      if ((await db.query('SELECT id FROM experimental_chat_agreements WHERE id=$1::uuid', [agreementId])).rows.length) {
        throw fail('AGREEMENT_ID_CONFLICT', 409)
      }
      if ((await db.query('SELECT count(*)::int AS total FROM experimental_chat_agreements WHERE chat_id=$1 AND archived=FALSE', [chatId])).rows[0].total >= 50) {
        throw fail('AGREEMENT_ROOM_LIMIT', 409)
      }
      if (!await context.sourceVisible(db, command.sourceMessageId)) throw fail('AGREEMENT_SOURCE_UNAVAILABLE', 404)
      state = createAgreement(command, context.actorId, context.participants, now)
      await db.query(`INSERT INTO experimental_chat_agreements(id,chat_id,author_id,state) VALUES($1::uuid,$2,$3,$4::jsonb)`,
        [agreementId, chatId, context.actorId, JSON.stringify(state)])
    } else {
      const previous = (await db.query('SELECT state FROM experimental_chat_agreements WHERE chat_id=$1 AND id=$2::uuid FOR UPDATE', [chatId, agreementId])).rows[0]
      if (!previous) throw fail('AGREEMENT_NOT_FOUND', 404)
      state = changeAgreement(previous.state, command, context.actorId, now)
      if (command.action === 'restore' && previous.state.archived && !state.archived &&
          (await db.query('SELECT count(*)::int AS total FROM experimental_chat_agreements WHERE chat_id=$1 AND archived=FALSE', [chatId])).rows[0].total >= 50) {
        throw fail('AGREEMENT_ROOM_LIMIT', 409)
      }
      await db.query('UPDATE experimental_chat_agreements SET state=$3::jsonb,archived=$4,updated_at=NOW() WHERE chat_id=$1 AND id=$2::uuid',
        [chatId, agreementId, JSON.stringify(state), Boolean(state.archived)])
    }
    await db.query(`INSERT INTO experimental_chat_agreement_operations(actor_id,operation_id,request_hash,agreement_id)
      VALUES($1,$2::uuid,$3,$4::uuid)`, [context.actorId, command.operationId, hash, agreementId])
    await db.query('COMMIT')
    return { state, context, replayed: false }
  } catch (error) {
    try { await db.query('ROLLBACK') } catch (rollback) { console.warn('[agreement] rollback failed', { code: rollback.code || 'DATABASE_FAILURE' }) }
    if (error.code === '23505') throw fail('AGREEMENT_OPERATION_CONFLICT', 409)
    throw error
  } finally { db.release() }
}

module.exports = { ensureChatAgreementTables, writeAgreement }
