'use strict'

const assert = require('node:assert/strict')
const { Pool } = require('pg')
const { registerMessageMarkerRoutes } = require('../src/message-markers-routes')
const { lookupMarkerMessages } = require('../src/data-routes')

async function main() {
  const url = process.env.MESSAGE_MARKERS_TEST_DATABASE_URL
  if (!url) throw new Error('Set MESSAGE_MARKERS_TEST_DATABASE_URL to an isolated PostgreSQL fixture.')
  const pool = new Pool({ connectionString: url })
  const handlers = new Map()
  const app = Object.fromEntries(['get', 'put', 'delete'].map((method) => [method,
    (path, _auth, handler) => handlers.set(`${method} ${path}`, handler)]))
  let target
  try {
    const { rows } = await pool.query(`SELECT m.chat_id, m.id::text AS message_id, m.content,
        m.deleted_by, m.deleted_for_all, c.participants
      FROM fallback_messages m JOIN fallback_chats c ON c.id::text=m.chat_id
      WHERE NOT m.deleted_for_all AND array_length(c.participants,1)=2 LIMIT 1`)
    target = rows[0]
    assert.ok(target, 'Fixture needs one direct message with two participants')
    const [first, second] = target.participants
    const contentSearch = String(target.content || '').trim().slice(0, 40).toLowerCase()
    registerMessageMarkerRoutes(app, {
      pool, authRequired: (_req, _res, next) => next?.(),
      getCurrentUser: async (_pool, req) => ({ id: req.userId }),
      userIdentity: (user) => ({ ids: new Set([user.id.toLowerCase()]), displayUserId: user.id.toLowerCase() }),
      privateChatAccessContext: async (_pool, chatId, identity) => ({
        hasAccess: chatId === target.chat_id && target.participants.some((id) => identity.ids.has(id.toLowerCase())),
      }),
      loadCollectionRoomAccess: async () => null,
      lookupMessage: async (_pool, kind, roomId, messageId, identity) =>
        (await lookupMarkerMessages(pool, kind, roomId, [messageId], identity)).get(messageId) || null,
      lookupMessages: lookupMarkerMessages,
      memberUserId: (row) => row.user_id,
      rowMatchesAny: () => false,
      boolOf: () => false,
    })
    async function call(method, path, userId, body = {}, query = {}) {
      const handler = handlers.get(`${method} ${path}`)
      const res = { status(code) { this.statusCode = code; return this }, json(value) { this.body = value; return this } }
      await handler({ userId, body, query, params: {
        kind: 'chat', roomId: target.chat_id, messageId: target.message_id,
      } }, res, (error) => { throw error })
      assert.ok(res.body, `${method} did not respond`)
      return res
    }
    const base = '/v1/data/messages/markers/:kind/:roomId'
    const pin = `${base}/:messageId/pin`
    const saved = `${base}/:messageId/saved`
    assert.equal((await call('put', pin, first)).body.pins.length, 1)
    assert.equal((await call('get', base, second)).body.pins.length, 1)
    assert.equal((await call('put', saved, second, { label: 'Acuerdo' })).body.saved[0].label, 'Acuerdo')
    assert.equal((await call('get', base, first)).body.saved.length, 0)
    const globalSaved = await call('get', '/v1/data/messages/saved', second)
    assert.ok(globalSaved.body.saved.some((entry) => entry.message_id === target.message_id &&
      entry.label === 'Acuerdo' && entry.room_id === target.chat_id))
    const byLabel = await call('get', '/v1/data/messages/saved', second, {}, { search: 'acuerdo' })
    assert.equal(byLabel.body.saved[0]?.message_id, target.message_id)
    if (contentSearch) {
      const byContent = await call('get', '/v1/data/messages/saved', second, {}, { search: contentSearch })
      assert.equal(byContent.body.saved[0]?.message_id, target.message_id)
    }
    const noMatch = await call('get', '/v1/data/messages/saved', second, {}, { search: 'palabra inexistente' })
    assert.equal(noMatch.body.saved.length, 0)
    const otherAccountGlobal = await call('get', '/v1/data/messages/saved', first)
    assert.equal(otherAccountGlobal.body.saved.some((entry) => entry.message_id === target.message_id), false)
    await pool.query(`UPDATE fallback_messages SET deleted_for_all=TRUE WHERE id::text=$1`, [target.message_id])
    assert.equal((await call('get', base, second)).body.saved[0].message, null)
    if (contentSearch) assert.equal((await call('get', '/v1/data/messages/saved', second, {},
      { search: contentSearch })).body.saved.length, 0)
    await pool.query(`UPDATE fallback_messages SET deleted_for_all=FALSE, deleted_by=$2::jsonb WHERE id::text=$1`,
      [target.message_id, JSON.stringify([second])])
    assert.equal((await call('get', base, second)).body.saved[0].message, null)
    if (contentSearch) assert.equal((await call('get', '/v1/data/messages/saved', second, {},
      { search: contentSearch })).body.saved.length, 0)
    assert.ok((await call('get', base, first)).body.pins[0].message)
    await pool.query(`UPDATE fallback_messages SET deleted_by='[]'::jsonb WHERE id::text=$1`, [target.message_id])
    assert.equal((await call('delete', saved, second)).body.saved.length, 0)
    assert.equal((await call('delete', pin, first)).body.pins.length, 0)
    const events = await pool.query(`SELECT action FROM fallback_message_pin_events
      WHERE room_kind='chat' AND room_id=$1 AND message_id=$2 ORDER BY id DESC LIMIT 2`,
    [target.chat_id, target.message_id])
    assert.deepEqual(events.rows.map((row) => row.action), ['unpin', 'pin'])
    console.log('PASS: shared pin, private/global save, server search, deletion privacy and audit event in PostgreSQL')
  } finally {
    if (target) {
      await pool.query(`UPDATE fallback_messages SET deleted_for_all=$2, deleted_by=$3::jsonb WHERE id::text=$1`,
        [target.message_id, target.deleted_for_all, JSON.stringify(target.deleted_by || [])])
      await pool.query(`DELETE FROM fallback_message_pins WHERE room_kind='chat' AND room_id=$1 AND message_id=$2`,
        [target.chat_id, target.message_id])
      await pool.query(`DELETE FROM fallback_saved_messages WHERE room_kind='chat' AND room_id=$1 AND message_id=$2`,
        [target.chat_id, target.message_id])
      await pool.query(`DELETE FROM fallback_message_pin_events WHERE room_kind='chat' AND room_id=$1 AND message_id=$2`,
        [target.chat_id, target.message_id])
    }
    await pool.end()
  }
}

main().catch((error) => { console.error(error); process.exitCode = 1 })
