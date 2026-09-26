const assert = require('node:assert/strict')
const { randomUUID } = require('node:crypto')
const { Client } = require('pg')
const realtime = require('../src/realtime-publish')
const { registerDataRoutes } = require('../src/data-routes')

// Invokes the real HTTP handlers and message SQL. Auth/profile/catalog lookups
// use fixtures; no connection to a real account, gateway or notification service.
async function verifyRoutes() {
  const connectionString = process.env.MESSAGE_INSERT_TEST_DATABASE_URL
  if (!connectionString) throw new Error('MESSAGE_INSERT_TEST_DATABASE_URL is required')
  const url = new URL(connectionString)
  assert.ok(['127.0.0.1', 'localhost'].includes(url.hostname))
  assert.equal(url.pathname, '/nowi_message_insert_test')
  const client = new Client({ connectionString, connectionTimeoutMillis: 5000 })
  await client.connect()
  let fixture
  let events = []
  let effects = []
  const originalPublish = realtime.publishChatMessageEvent
  realtime.publishChatMessageEvent = async (payload) => { events.push(payload); return true }
  const pool = { query: async (sql, params) => {
    if (/\b(?:FROM|INTO|UPDATE)\s+fallback_(?:room_)?messages\b/i.test(sql)) {
      return client.query(sql, params)
    }
    if (/information_schema/.test(sql)) return { rows: [] }
    if (/FROM fallback_users/.test(sql)) return { rows: [{ id: fixture.actor,
      supabase_user_id: null, email: 'fixture@example.test', display_name: 'Fixture' }] }
    if (/FROM fallback_user_blocks/.test(sql)) return { rows: [] }
    if (/FROM fallback_chats/.test(sql)) return { rows: [{ id: fixture.scope,
      participants: [fixture.actor], created_by: fixture.owner, is_group: false }] }
    if (/FROM fallback_chat_participants/.test(sql)) return { rows: [
      { chat_id: fixture.scope, user_id: fixture.actor }] }
    if (/FROM fallback_collection_rooms/.test(sql)) return { rows: [{ id: fixture.scope,
      room_kind: fixture.kind, creator_id: fixture.owner, is_public: true, name: 'Fixture' }] }
    if (/FROM fallback_collection_members/.test(sql)) return { rows: fixture.member ? [{
      room_id: fixture.scope, room_kind: fixture.kind, user_id: fixture.actor, role: 'member',
    }] : [] }
    if (/fallback_community_channels/.test(sql)) return { rows: [{ id: fixture.channel,
      community_id: fixture.scope, channel_type: 'text', is_default: true }] }
    if (/^\s*(UPDATE|DELETE|INSERT)/i.test(sql)) { effects.push(sql); return { rows: [] } }
    throw new Error(`Unexpected fixture query: ${sql.slice(0, 160)}`)
  } }
  try {
    await client.query('BEGIN')
    await client.query('SET LOCAL search_path = pg_temp, public')
    await client.query(`CREATE TEMP TABLE fallback_messages
      (LIKE public.fallback_messages INCLUDING ALL) ON COMMIT DROP`)
    await client.query(`CREATE TEMP TABLE fallback_room_messages
      (LIKE public.fallback_room_messages INCLUDING ALL) ON COMMIT DROP`)
    const routes = []
    const app = {}
    for (const method of ['get', 'post', 'put', 'patch', 'delete', 'head', 'use']) {
      app[method] = (route, ...handlers) => routes.push({ method, route, handler: handlers.at(-1) })
    }
    registerDataRoutes(app, { pool, authRequired: () => {} })
    const invoke = async (kind, body) => {
      const routePath = kind === 'dm' ? '/v1/data/messages/chats/:chatId/messages' :
        `/v1/data/messages/${kind === 'group' ? 'groups' : 'communities'}/:roomId/messages`
      const found = routes.find((r) => r.method === 'post' && [r.route].flat().includes(routePath))
      assert.ok(found, routePath)
      const req = { auth: { sub: fixture.actor }, path: routePath, originalUrl: routePath,
        params: { chatId: fixture.scope, roomId: fixture.scope }, body }
      const res = { statusCode: 200, body: null, status(value) { this.statusCode = value; return this },
        json(value) { this.body = value; return this } }
      let failure
      await found.handler(req, res, (error) => { failure = error })
      if (failure) { res.statusCode = failure.status || 500; res.body = { error: failure.message } }
      return res
    }
    let checks = 0
    for (const kind of ['dm', 'group', 'community']) {
      const owner = randomUUID()
      fixture = { kind, actor: owner, owner, scope: randomUUID(), channel: randomUUID(), member: true }
      const body = { id: randomUUID(), content: 'Original', contentType: 'text' }
      events = []; effects = []
      const first = await invoke(kind, body)
      assert.equal(first.statusCode, 201, JSON.stringify(first.body))
      assert.equal(first.body.message.id, body.id)
      assert.equal(first.body.message.sender_id, owner)
      assert.equal(events.length, 1)
      checks++

      const table = kind === 'dm' ? 'pg_temp.fallback_messages' : 'pg_temp.fallback_room_messages'
      await client.query(`UPDATE ${table} SET status = 'read', read_at = NOW(), is_edited = TRUE,
        content = 'Edited', deleted_for_all = TRUE WHERE id = $1`, [body.id])
      const before = (await client.query(`SELECT * FROM ${table} WHERE id = $1`, [body.id])).rows
      events = []; effects = []
      const replay = await invoke(kind, { ...body, content: 'Old payload' })
      assert.equal(replay.statusCode, 200, JSON.stringify(replay.body))
      assert.equal(replay.body.message.content, 'Edited')
      assert.equal(replay.body.message.status, 'read')
      assert.equal(replay.body.message.deleted_for_all, true)
      assert.equal(replay.body.message.is_edited, true)
      assert.deepEqual((await client.query(`SELECT * FROM ${table} WHERE id = $1`, [body.id])).rows, before)
      assert.equal(events.length, 0)
      assert.deepEqual(effects, [])
      checks++

      fixture.actor = randomUUID() // Authenticated member, but not the author.
      const foreign = await invoke(kind, body)
      assert.equal(foreign.statusCode, 409, JSON.stringify(foreign.body))
      assert.equal(foreign.body.error, 'MESSAGE_ID_CONFLICT')
      assert.equal(foreign.body.message, undefined)
      assert.deepEqual((await client.query(`SELECT * FROM ${table} WHERE id = $1`, [body.id])).rows, before)
      assert.equal(events.length, 0)
      checks++

      if (kind !== 'dm') {
        fixture.member = false
        const nonMember = await invoke(kind, { ...body, id: randomUUID() })
        assert.equal(nonMember.statusCode, 403, JSON.stringify(nonMember.body))
        assert.equal(nonMember.body.error, 'ROOM_ACCESS_DENIED')
        checks++
      }
    }
    return { ok: true, checks, handlers: 'direct, group, community', storage: 'local PostgreSQL, rollback' }
  } finally {
    realtime.publishChatMessageEvent = originalPublish
    await client.query('ROLLBACK')
    await client.end()
  }
}

if (require.main === module) verifyRoutes().then((result) => console.log(JSON.stringify(result))).catch((error) => {
  console.error(error)
  process.exitCode = 1
})

module.exports = { verifyRoutes }
