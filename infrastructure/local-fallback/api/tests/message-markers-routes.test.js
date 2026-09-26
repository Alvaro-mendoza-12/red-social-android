const assert = require('node:assert/strict')
const { test } = require('node:test')
const { markerParams, registerMessageMarkerRoutes } = require('../src/message-markers-routes')

const roomId = '11111111-1111-4111-8111-111111111111'
const messageId = '22222222-2222-4222-8222-222222222222'
const identity = { ids: new Set(['me']), displayUserId: 'me' }

function response() {
  return { status(code) { this.statusCode = code; return this }, json(body) { this.body = body; return this } }
}

function routes(overrides = {}) {
  const handlers = new Map()
  const app = Object.fromEntries(['get', 'put', 'delete'].map((method) => [method,
    (path, _auth, handler) => handlers.set(`${method} ${path}`, handler)]))
  const dependencies = {
    pool: { query: () => assert.fail('unexpected query') }, authRequired: () => {},
    getCurrentUser: async () => ({ id: 'me' }), userIdentity: () => identity,
    privateChatAccessContext: async () => ({ hasAccess: true }),
    loadCollectionRoomAccess: async () => null,
    lookupMessage: async () => ({ id: messageId, content: 'Hola' }),
    memberUserId: (row) => row.user_id,
    rowMatchesAny: (row, keys, ids) => keys.some((key) => ids.has(row[key])),
    boolOf: (row, keys) => keys.some((key) => row[key] === true),
    ...overrides,
  }
  registerMessageMarkerRoutes(app, dependencies)
  return handlers
}

test('marker target rejects malformed room and message IDs', () => {
  assert.equal(markerParams({ params: { kind: 'chat', roomId: 'bad' } }), null)
  assert.equal(markerParams({ params: { kind: 'group', roomId, messageId: 'bad' } }), null)
  assert.deepEqual(markerParams({ params: { kind: 'community', roomId, messageId } }),
    { kind: 'community', roomId, messageId })
})

test('a removed group member cannot read saved or pinned markers', async () => {
  const handlers = routes({
    loadCollectionRoomAccess: async () => ({ room: { creator_id: 'other' },
      roomMembers: [{ user_id: 'me', status: 'removed', role: 'admin' }] }),
  })
  const res = response()
  await handlers.get('get /v1/data/messages/markers/:kind/:roomId')(
    { params: { kind: 'group', roomId } }, res, assert.fail)
  assert.equal(res.statusCode, 403)
  assert.equal(res.body.error, 'ROOM_ACCESS_DENIED')
})

test('non-admin member cannot pin a group message', async () => {
  const handlers = routes({
    loadCollectionRoomAccess: async () => ({ room: { creator_id: 'other' },
      roomMembers: [{ user_id: 'me', status: 'joined', role: 'member' }] }),
  })
  const res = response()
  await handlers.get('put /v1/data/messages/markers/:kind/:roomId/:messageId/pin')(
    { params: { kind: 'group', roomId, messageId } }, res, assert.fail)
  assert.equal(res.statusCode, 403)
  assert.equal(res.body.error, 'PIN_REQUIRES_ADMIN')
})

test('a group chat cannot use the direct-chat marker route to bypass admin policy', async () => {
  const handlers = routes({
    privateChatAccessContext: async () => ({ hasAccess: true, chat: { is_group: true } }),
  })
  const res = response()
  await handlers.get('put /v1/data/messages/markers/:kind/:roomId/:messageId/pin')(
    { params: { kind: 'chat', roomId, messageId } }, res, assert.fail)
  assert.equal(res.statusCode, 400)
  assert.equal(res.body.error, 'INVALID_MARKER_TARGET')
})

test('pin limit is checked while holding a room-scoped transactional lock', async () => {
  const statements = []
  const client = {
    query: async (sql) => {
      statements.push(sql)
      if (sql.includes('SELECT 1 FROM fallback_message_pins')) return { rowCount: 0, rows: [] }
      if (sql.includes('count(*)::int')) return { rows: [{ total: 10 }] }
      return { rows: [] }
    },
    release() {},
  }
  const handlers = routes({ pool: { connect: async () => client } })
  const res = response()
  await handlers.get('put /v1/data/messages/markers/:kind/:roomId/:messageId/pin')(
    { params: { kind: 'chat', roomId, messageId } }, res, assert.fail)
  assert.equal(res.statusCode, 409)
  assert.equal(res.body.error, 'PIN_LIMIT_REACHED')
  assert.ok(statements.some((sql) => sql.includes('pg_advisory_xact_lock')))
  assert.equal(statements.at(-1), 'ROLLBACK')
})

test('saved list is scoped to the account and hides unavailable message content', async () => {
  const queries = []
  const handlers = routes({
    pool: { query: async (sql, params) => {
      queries.push({ sql, params })
      return { rows: sql.includes('fallback_saved_messages')
        ? [{ message_id: messageId, label: 'Importante', saved_at: '2026-09-24' }] : [] }
    } },
    lookupMessage: async () => null,
  })
  const res = response()
  await handlers.get('get /v1/data/messages/markers/:kind/:roomId')(
    { params: { kind: 'chat', roomId } }, res, assert.fail)
  assert.equal(queries.find((query) => query.sql.includes('fallback_saved_messages')).params[0], 'me')
  assert.equal(res.body.saved[0].unavailable, true)
  assert.equal(res.body.saved[0].message, null)
})

test('global saved inbox skips rooms without current access and scopes the query to the account', async () => {
  const groupId = '33333333-3333-4333-8333-333333333333'
  const otherId = '44444444-4444-4444-8444-444444444444'
  const queries = []
  const handlers = routes({
    pool: { query: async (sql, params) => {
      queries.push(params)
      return { rows: [
        { room_kind: 'group', room_id: groupId, message_id: otherId, label: '', saved_at: '2026-09-24T10:00:00Z' },
        { room_kind: 'chat', room_id: roomId, message_id: messageId, label: 'Tarea', saved_at: '2026-09-24T09:00:00Z' },
      ] }
    } },
    loadCollectionRoomAccess: async () => null,
    lookupMessages: async () => new Map([[messageId, { id: messageId, content: 'Hola', content_type: 'text' }]]),
  })
  const res = response()
  await handlers.get('get /v1/data/messages/saved')({ query: {} }, res, assert.fail)
  assert.equal(queries[0][0], 'me')
  assert.equal(queries[0].length, 9)
  assert.equal(res.body.saved.length, 1)
  assert.equal(res.body.saved[0].room_kind, 'chat')
  assert.equal(res.body.saved[0].room_name, `Chat ${roomId.slice(0, 8)}`)
  assert.equal(res.body.nextCursor, null)
})

test('global saved inbox validates cursor and filters before querying', async () => {
  const handlers = routes()
  const invalidCursor = response()
  await handlers.get('get /v1/data/messages/saved')({ query: { cursor: 'garbage' } }, invalidCursor, assert.fail)
  assert.equal(invalidCursor.statusCode, 400)
  assert.equal(invalidCursor.body.error, 'INVALID_SAVED_CURSOR')
  const invalidFilter = response()
  await handlers.get('get /v1/data/messages/saved')({ query: { roomId: 'bad' } }, invalidFilter, assert.fail)
  assert.equal(invalidFilter.body.error, 'INVALID_SAVED_FILTER')
  const invalidSearch = response()
  await handlers.get('get /v1/data/messages/saved')({ query: { search: 'x'.repeat(121) } }, invalidSearch, assert.fail)
  assert.equal(invalidSearch.body.error, 'INVALID_SAVED_FILTER')
})

test('global saved search filters before pagination and does not reveal deleted matches', async () => {
  const queries = []
  const hiddenId = '55555555-5555-4555-8555-555555555555'
  const handlers = routes({
    pool: { query: async (sql, params) => {
      queries.push({ sql, params })
      return { rows: [
        { room_kind: 'chat', room_id: roomId, message_id: hiddenId, label: '', saved_at: '2026-09-24T10:00:00Z' },
        { room_kind: 'chat', room_id: roomId, message_id: messageId, label: 'Recibo', saved_at: '2026-09-24T09:00:00Z' },
      ] }
    } },
    lookupMessages: async () => new Map([[messageId,
      { id: messageId, content: 'Pago recibido', content_type: 'text' }]]),
  })
  const res = response()
  await handlers.get('get /v1/data/messages/saved')({ query: { search: 'Recibo' } }, res, assert.fail)
  assert.equal(queries[0].params[9], 'recibo')
  assert.match(queries[0].sql, /strpos\(lower\(m\.content\), \$10\)/)
  assert.match(queries[0].sql, /NOT \(m\.deleted_by \?\| \$11::text\[\]\)/)
  assert.equal(res.body.saved.length, 1)
  assert.equal(res.body.saved[0].message_id, messageId)
})

test('global saved search includes an imported message table only when present', async () => {
  const queries = []
  const handlers = routes({
    pool: { query: async (sql) => { queries.push(sql); return { rows: [] } } },
    importedMessageTableExists: async (table) => table === 'messages',
  })
  await handlers.get('get /v1/data/messages/saved')({ query: { search: 'hola' } }, response(), assert.fail)
  assert.match(queries[0], /supabase_import\.messages/)
  assert.doesNotMatch(queries[0], /supabase_import\.group_messages/)
})

test('global saved type filter is applied before the cursor page is selected', async () => {
  const queries = []
  const handlers = routes({
    pool: { query: async (sql, params) => { queries.push({ sql, params }); return { rows: [] } } },
  })
  await handlers.get('get /v1/data/messages/saved')(
    { query: { type: 'document', search: 'contrato' } }, response(), assert.fail)
  assert.match(queries[0].sql, /lower\(m\.content_type\) IN \('document','file'\)/)
  assert.match(queries[0].sql, /AND \(\s*EXISTS \(SELECT 1 FROM fallback_messages/)
  assert.match(queries[0].sql, /strpos\(lower\(m\.content\), \$11\)/)
  assert.equal(queries[0].params[10], 'contrato')
  assert.equal(queries[0].params.length, 12)
})

test('global saved inbox cursor includes the full deterministic ordering key', async () => {
  const rows = Array.from({ length: 51 }, (_, index) => ({ room_kind: 'chat', room_id: roomId,
    message_id: `${(index + 1).toString(16).padStart(8, '0')}-2222-4222-8222-222222222222`,
    label: '', saved_at: '2026-09-24T09:00:00Z' }))
  const handlers = routes({
    pool: { query: async () => ({ rows }) },
    lookupMessages: async () => new Map(),
  })
  const res = response()
  await handlers.get('get /v1/data/messages/saved')({ query: {} }, res, assert.fail)
  assert.equal(res.body.saved.length, 50)
  assert.deepEqual(JSON.parse(Buffer.from(res.body.nextCursor, 'base64url').toString('utf8')),
    { savedAt: rows[49].saved_at, kind: 'chat', roomId, messageId: rows[49].message_id })
})
