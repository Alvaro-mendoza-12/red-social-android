'use strict'

const { test } = require('node:test')
const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const { Pool } = require('pg')
const express = require('express')
const { ensureChatAgreementTables } = require('../src/chat-agreements-store')
const { registerChatAgreementRoutes } = require('../src/chat-agreements-routes')
const { purgeChatAgreementsForAccounts } = require('../src/chat-agreements-cleanup')
const { createAgreement } = require('../src/chat-agreements-domain')

const ALICE = '11111111-1111-4111-8111-111111111111', BOB = '22222222-2222-4222-8222-222222222222'
const CHAT = '33333333-3333-4333-8333-333333333333', SOURCE = '44444444-4444-4444-8444-444444444444'

async function fixture(connectionString) {
  const url = new URL(connectionString)
  if (!['127.0.0.1', 'localhost'].includes(url.hostname) || url.pathname !== '/novvin_agreements_test') {
    throw Error('Requires the isolated loopback novvin_agreements_test database')
  }
  const pool = new Pool({ connectionString, max: 5 })
  await pool.query(`CREATE TABLE IF NOT EXISTS fallback_users(id UUID PRIMARY KEY,supabase_user_id UUID,display_name TEXT);
    CREATE TABLE IF NOT EXISTS fallback_chats(id UUID PRIMARY KEY,participants TEXT[],is_group BOOLEAN DEFAULT FALSE,policy TEXT DEFAULT 'open');
    CREATE TABLE IF NOT EXISTS fallback_messages(id UUID PRIMARY KEY,chat_id TEXT,content TEXT,content_type TEXT DEFAULT 'text',
      created_at TIMESTAMPTZ DEFAULT NOW(),deleted_for_all BOOLEAN DEFAULT FALSE);
    CREATE OR REPLACE FUNCTION fallback_direct_message_request_state(chat TEXT,viewer TEXT) RETURNS JSONB
      LANGUAGE SQL STABLE AS $$ SELECT jsonb_build_object('status',policy) FROM fallback_chats WHERE id::text=chat $$;`)
  await ensureChatAgreementTables(pool)
  await ensureChatAgreementTables(pool)
  await pool.query(`INSERT INTO fallback_users(id,display_name) VALUES($1,'Ana'),($2,'Luis') ON CONFLICT DO NOTHING`, [ALICE, BOB])
  await pool.query(`INSERT INTO fallback_chats(id,participants) VALUES($1,$2) ON CONFLICT DO NOTHING`, [CHAT, [ALICE, BOB]])
  await pool.query(`INSERT INTO fallback_messages(id,chat_id,content) VALUES($1,$2,'Ensayamos este sábado') ON CONFLICT DO NOTHING`, [SOURCE, CHAT])
  const app = express(); app.use(express.json({ limit: '16kb' }))
  const published = []
  registerChatAgreementRoutes(app, { pool, enabled: true,
    authRequired(req, res, next) {
      const actor = String(req.headers.authorization || '').replace('Bearer ', '')
      if (![ALICE, BOB].includes(actor)) return res.status(401).json({ error: 'UNAUTHORIZED' })
      req.qaActor = actor; next()
    },
    getCurrentUser: async (db, req) => (await db.query('SELECT * FROM fallback_users WHERE id=$1', [req.qaActor])).rows[0],
    userIdentity: user => ({ ids: new Set([user.id]), displayUserId: user.id }),
    privateChatAccessContext: async (db, id, identity) => {
      const chat = (await db.query('SELECT * FROM fallback_chats WHERE id=$1', [id])).rows[0]
      return { chat, hasAccess: chat?.participants.includes(identity.displayUserId), participantRows: [] }
    },
    lookupMessages: async (db, _kind, chatId, ids) => new Map((await db.query(`SELECT id::text,content,content_type,created_at
      FROM fallback_messages WHERE chat_id=$1 AND id::text=ANY($2::text[]) AND deleted_for_all=FALSE`, [chatId, ids])).rows.map(row => [row.id, row])),
    publish: async (channel, payload) => { published.push({ channel, payload }) },
  })
  app.use((error, _req, res, _next) => { console.error('QA request failed', { code: error.code || error.message }); res.status(500).json({ error: 'QA_FAILURE' }) })
  return { pool, app, published }
}

test('isolated PostgreSQL and HTTP enforce consent, concurrent versions and lost-ACK replay',
  { skip: !process.env.AGREEMENTS_TEST_DATABASE_URL || process.env.AGREEMENTS_QA_SERVE === 'true' }, async t => {
    const { pool, app, published } = await fixture(process.env.AGREEMENTS_TEST_DATABASE_URL)
    t.after(() => pool.end())
    const server = await new Promise(resolve => { const value = app.listen(0, '127.0.0.1', () => resolve(value)) })
    t.after(() => new Promise(resolve => server.close(resolve)))
    const base = `http://127.0.0.1:${server.address().port}/v1/data/messages/chats/${CHAT}/agreements`
    const request = async (actor, suffix = '', body) => {
      const response = await fetch(base + suffix, { method: body ? 'POST' : 'GET',
        headers: { Authorization: `Bearer ${actor}`, 'Content-Type': 'application/json' }, ...(body ? { body: JSON.stringify(body) } : {}) })
      return { status: response.status, body: await response.json(), cache: response.headers.get('cache-control') }
    }
    const create = { id: crypto.randomUUID(), operationId: crypto.randomUUID(), sourceMessageId: SOURCE, text: 'Ensayar el sábado' }
    const initial = await request(ALICE, '', create)
    assert.equal(initial.status, 201); assert.equal(initial.body.agreement.status, 'pending'); assert.equal(initial.cache, 'no-store')
    assert.deepEqual(initial.body.agreement.participants.map(member => member.response), ['pending', 'pending'])
    const path = `/${create.id}/actions`
    const accept = { operationId: crypto.randomUUID(), expectedVersion: 1, action: 'accept' }
    const accepted = await request(ALICE, path, accept)
    assert.equal(accepted.status, 200); assert.equal(accepted.body.agreement.status, 'pending')
    const ackReplay = await request(ALICE, path, accept)
    assert.equal(ackReplay.body.agreement.version, 2)
    assert.equal((await request(ALICE, path, { ...accept, action: 'withdraw' })).status, 409)
    const bobAccept = { operationId: crypto.randomUUID(), expectedVersion: 2, action: 'accept' }
    assert.equal((await request(BOB, path, bobAccept)).body.agreement.status, 'accepted')
    const edit = await request(ALICE, path, { operationId: crypto.randomUUID(), expectedVersion: 3, action: 'revise', text: 'Ensayar el domingo' })
    assert.equal(edit.body.agreement.proposalRevision, 2)
    assert.ok(edit.body.agreement.participants.every(member => member.response === 'pending'))
    // Replaying the old accept returns the current revision without accepting it.
    const replayOld = await request(ALICE, path, accept)
    assert.equal(replayOld.body.agreement.version, 4); assert.equal(replayOld.body.agreement.status, 'pending')
    const simultaneous = await Promise.all([
      request(ALICE, path, { operationId: crypto.randomUUID(), expectedVersion: 4, action: 'accept' }),
      request(BOB, path, { operationId: crypto.randomUUID(), expectedVersion: 4, action: 'accept' }),
    ])
    assert.deepEqual(simultaneous.map(row => row.status).sort(), [200, 409])
    const duplicateCreate = await Promise.all([request(ALICE, '', create), request(ALICE, '', create)])
    assert.ok(duplicateCreate.every(row => row.status === 200))
    assert.equal((await pool.query('SELECT count(*)::int AS total FROM experimental_chat_agreements WHERE id=$1', [create.id])).rows[0].total, 1)
    await pool.query('UPDATE fallback_messages SET deleted_for_all=TRUE WHERE id=$1', [SOURCE])
    const listing = await request(BOB)
    assert.equal(listing.body.agreements.find(row => row.id === create.id).sourceAvailable, false)
    assert.equal((await request(BOB, `/${create.id}/source`)).body.source, null)
    assert.equal((await request(ALICE, '', { ...create, id: crypto.randomUUID(), operationId: crypto.randomUUID() })).status, 404)
    await pool.query("UPDATE fallback_chats SET policy='blocked' WHERE id=$1", [CHAT])
    assert.equal((await request(BOB)).status, 403)
    assert.equal((await request(ALICE, path, accept)).status, 403)
    assert.equal((await request('unknown')).status, 401)
    assert.ok(published.length >= 4)
    assert.ok(published.every(event => Object.keys(event.payload).sort().join(',') === 'agreementId,chatId,event'))
    await pool.query("UPDATE fallback_chats SET policy='open' WHERE id=$1", [CHAT])
    await pool.query('UPDATE fallback_messages SET deleted_for_all=FALSE WHERE id=$1', [SOURCE])
    // The real purge participates in the caller's transaction and receipt cascades.
    const transaction = await pool.connect()
    try {
      await transaction.query('BEGIN')
      await purgeChatAgreementsForAccounts(transaction, [BOB])
      assert.equal((await transaction.query('SELECT count(*)::int AS total FROM experimental_chat_agreements WHERE id=$1', [create.id])).rows[0].total, 0)
      await transaction.query('ROLLBACK')
      assert.equal((await pool.query('SELECT count(*)::int AS total FROM experimental_chat_agreements WHERE id=$1', [create.id])).rows[0].total, 1)
      await transaction.query('BEGIN')
      await purgeChatAgreementsForAccounts(transaction, [BOB])
      await transaction.query('COMMIT')
      assert.equal((await pool.query('SELECT count(*)::int AS total FROM experimental_chat_agreement_operations WHERE agreement_id=$1', [create.id])).rows[0].total, 0)
    } finally { transaction.release() }
    // An account deletion that already owns the user row must win over a new proposal.
    const deleting = await pool.connect()
    const raced = { ...create, id: crypto.randomUUID(), operationId: crypto.randomUUID() }
    let outcome = null, pendingCreation
    try {
      await deleting.query('BEGIN')
      await deleting.query('SELECT id FROM fallback_users WHERE id=$1 FOR UPDATE', [BOB])
      pendingCreation = request(ALICE, '', raced).then(result => { outcome = result; return result })
      await new Promise(resolve => setTimeout(resolve, 100))
      assert.equal(outcome, null, 'The proposal must wait for the deleting participant row')
      await purgeChatAgreementsForAccounts(deleting, [BOB])
      await deleting.query('DELETE FROM fallback_users WHERE id=$1', [BOB])
      await deleting.query('COMMIT')
      assert.equal((await pendingCreation).status, 403)
      assert.equal((await pool.query('SELECT count(*)::int AS total FROM experimental_chat_agreements WHERE id=$1', [raced.id])).rows[0].total, 0)
    } finally {
      await deleting.query('ROLLBACK')
      deleting.release()
      if (pendingCreation) await pendingCreation
      await pool.query("INSERT INTO fallback_users(id,display_name) VALUES($1,'Luis') ON CONFLICT DO NOTHING", [BOB])
    }
  })

module.exports = { fixture }

test('archive pagination is stable at microsecond ties and active quotas serialize create/restore races',
  { skip: !process.env.AGREEMENTS_TEST_DATABASE_URL || process.env.AGREEMENTS_QA_SERVE === 'true' }, async t => {
    const { pool, app } = await fixture(process.env.AGREEMENTS_TEST_DATABASE_URL)
    const chat = crypto.randomUUID(), foreignChat = crypto.randomUUID(), source = crypto.randomUUID()
    const server = await new Promise(resolve => { const value = app.listen(0, '127.0.0.1', () => resolve(value)) })
    t.after(async () => {
      await new Promise(resolve => server.close(resolve))
      try {
        await pool.query('DELETE FROM experimental_chat_agreements WHERE chat_id=ANY($1::text[])', [[chat, foreignChat]])
        await pool.query('DELETE FROM fallback_messages WHERE id=$1', [source])
        await pool.query('DELETE FROM fallback_chats WHERE id=ANY($1::uuid[])', [[chat, foreignChat]])
      } finally { await pool.end() }
    })
    await pool.query('INSERT INTO fallback_chats(id,participants) VALUES($1,$2)', [chat, [ALICE, BOB]])
    await pool.query("INSERT INTO fallback_messages(id,chat_id,content) VALUES($1,$2,'Origen de paginación')", [source, chat])
    const ids = Array.from({ length: 50 }, () => crypto.randomUUID()).sort().reverse()
    const time = '2026-11-04T08:00:00.123456Z'
    const make = id => createAgreement({ id, sourceMessageId: source, text: 'Acuerdo conservado' }, ALICE, [{ id: ALICE }, { id: BOB }], time)
    for (const id of ids) await pool.query('INSERT INTO experimental_chat_agreements(id,chat_id,author_id,state,created_at) VALUES($1,$2,$3,$4::jsonb,$5::timestamptz)',
      [id, chat, ALICE, JSON.stringify(make(id)), time])
    const base = `http://127.0.0.1:${server.address().port}/v1/data/messages/chats/${chat}/agreements`
    const request = async (suffix = '', body, actor = ALICE) => {
      const res = await fetch(base + suffix, { method: body ? 'POST' : 'GET', headers: { Authorization: `Bearer ${actor}`, 'Content-Type': 'application/json' },
        ...(body ? { body: JSON.stringify(body) } : {}) })
      return { status: res.status, body: await res.json() }
    }
    const action = (id, name, version, actor = ALICE, operationId = crypto.randomUUID()) => request(`/${id}/actions`, { action: name, expectedVersion: version, operationId }, actor)
    const first = (await request('?limit=25')).body
    assert.deepEqual(first.agreements.map(row => row.id), ids.slice(0, 25))
    assert.equal(first.previousCursor, null); assert.ok(first.nextCursor)
    const second = (await request(`?limit=25&cursor=${first.nextCursor}`)).body
    assert.deepEqual(second.agreements.map(row => row.id), ids.slice(25)); assert.equal(second.nextCursor, null)
    const back = (await request(`?limit=25&cursor=${second.previousCursor}`)).body
    assert.deepEqual(back.agreements, first.agreements)
    assert.equal((await request(`?archived=true&cursor=${first.nextCursor}`)).status, 400)
    const creation = { operationId: crypto.randomUUID(), id: crypto.randomUUID(), sourceMessageId: source, text: 'El siguiente' }
    assert.equal((await request('', creation)).body.error, 'AGREEMENT_ROOM_LIMIT')
    const archiveKey = crypto.randomUUID()
    assert.equal((await action(ids[0], 'archive', 1, BOB, archiveKey)).body.agreement.archived, true)
    assert.equal((await request('', creation)).status, 201, 'A rolled-back quota failure must not consume the operation key')
    assert.equal((await action(ids[0], 'restore', 2)).body.error, 'AGREEMENT_ROOM_LIMIT')
    await action(ids[1], 'archive', 1)
    assert.equal((await action(ids[0], 'restore', 2)).body.agreement.archived, false)
    const replay = (await action(ids[0], 'archive', 1, BOB, archiveKey)).body.agreement
    assert.equal(replay.archived, false); assert.equal(replay.version, 3)
    await action(ids[2], 'archive', 1)
    const focused = (await request(`?limit=25&focus=${ids[2]}`)).body
    assert.equal(focused.focus.id, ids[2]); assert.equal(focused.focus.archived, true)
    assert.ok(focused.agreements.every(row => !row.archived))
    const archived = (await request('?archived=true&limit=25')).body
    assert.deepEqual(new Set(archived.agreements.map(row => row.id)), new Set([ids[1], ids[2]]))
    const competing = await Promise.all([action(ids[1], 'restore', 2), action(ids[2], 'restore', 2, BOB)])
    assert.deepEqual(competing.map(row => row.status).sort(), [200, 409])
    assert.equal((await pool.query('SELECT count(*)::int AS n FROM experimental_chat_agreements WHERE chat_id=$1 AND archived=FALSE', [chat])).rows[0].n, 50)
    const foreignId = crypto.randomUUID()
    await pool.query('INSERT INTO experimental_chat_agreements(id,chat_id,author_id,state) VALUES($1,$2,$3,$4::jsonb)', [foreignId, foreignChat, ALICE, JSON.stringify(make(foreignId))])
    const hidden = (await request(`?focus=${foreignId}`)).body
    assert.equal(hidden.focus, null); assert.equal(hidden.focusMissing, true)
    // An archive that removes every row beyond a cursor returns the newest page.
    await pool.query('UPDATE experimental_chat_agreements SET archived=TRUE,state=jsonb_set(state,\'{archived}\',\'true\'::jsonb) WHERE chat_id=$1', [chat])
    const reset = (await request(`?limit=25&cursor=${first.nextCursor}`)).body
    assert.equal(reset.reset, true); assert.equal(reset.cursor, null); assert.deepEqual(reset.agreements, [])
  })

// The standalone fixture is only for the isolated QA container and its emulator tunnel.
if (require.main === module && process.env.AGREEMENTS_QA_SERVE === 'true') {
  fixture(process.env.AGREEMENTS_TEST_DATABASE_URL).then(({ app }) => {
    const fs = require('node:fs')
    const host = process.env.AGREEMENTS_QA_BIND || '127.0.0.1'
    if (!['127.0.0.1', '0.0.0.0'].includes(host)) throw Error('Invalid isolated QA binding')
    require('node:https').createServer({ cert: fs.readFileSync(process.env.AGREEMENTS_QA_CERT || '/qa/tls/cert.pem'),
      key: fs.readFileSync(process.env.AGREEMENTS_QA_KEY || '/qa/tls/key.pem') }, app)
      .listen(18107, host, () => console.log('Isolated agreement fixture ready'))
  }).catch(error => { console.error(error.message); process.exitCode = 1 })
}
