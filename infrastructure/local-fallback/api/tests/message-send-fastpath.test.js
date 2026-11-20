'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const { registerCallAccessRoutes } = require('../src/call-access-store')

test('group and community message send and mutation routes avoid loading full message history', () => {
  const source = fs.readFileSync(path.join(__dirname, '..', 'src', 'data-routes.js'), 'utf8')
  const postStart = source.indexOf("['/v1/data/messages/groups/:roomId/messages', '/v1/data/messages/communities/:roomId/messages']")
  const mutationStart = source.indexOf('async function loadCollectionMessageMutationContext', postStart)
  const patchStart = source.indexOf("['/v1/data/messages/groups/:roomId/messages/:messageId'", mutationStart)
  assert.ok(postStart > 0 && mutationStart > postStart && patchStart > mutationStart)

  const postSlice = source.slice(postStart, mutationStart)
  const mutationSlice = source.slice(mutationStart, patchStart)

  assert.match(postSlice, /loadCollectionRoomAccess\(/)
  assert.doesNotMatch(postSlice, /loadCollectionRoomPayload\(/)
  assert.match(mutationSlice, /loadCollectionRoomAccess\(/)
  assert.doesNotMatch(mutationSlice, /loadCollectionRoomPayload\(/)
})

test('DM message send uses local access fast-path and does not block HTTP 201 on notification fan-out', () => {
  const source = fs.readFileSync(path.join(__dirname, '..', 'src', 'data-routes.js'), 'utf8')
  const dmStart = source.indexOf("app.post('/v1/data/messages/chats/:chatId/messages'")
  const dmEnd = source.indexOf("app.patch('/v1/data/messages/chats/:chatId/messages/:messageId'", dmStart)
  assert.ok(dmStart > 0 && dmEnd > dmStart)
  const dmSlice = source.slice(dmStart, dmEnd)

  assert.match(dmSlice, /includeImportedPreferences:\s*false/)
  assert.match(dmSlice, /const deliveryTask = \(async \(\) => \{/)
  assert.match(dmSlice, /res\.status\(201\)\.json\(\{ message: insertedMessage \}\)/)
})

test('call accept and decline routes trigger multi-device session resolution callback', async () => {
  const A = '11111111-1111-4111-8111-111111111111'
  const B = '22222222-2222-4222-8222-222222222222'
  const CALL = '55555555-5555-4555-8555-555555555555'
  const SID_A = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
  const SID_B = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'

  const sessions = new Map()
  const invitees = new Map()
  const key = (call, user) => `${call}:${user}`
  const pool = {
    async query(sql, params = []) {
      const text = String(sql).replace(/\s+/g, ' ').trim()
      if (['BEGIN', 'COMMIT', 'ROLLBACK'].includes(text) || text.startsWith('SELECT pg_advisory_xact_lock')) return { rows: [] }
      if (text.includes('FROM fallback_users')) return { rows: [{ id: params[0], supabase_user_id: null }] }
      if (text.includes('FROM fallback_user_blocks')) return { rows: [] }
      if (text.startsWith('SELECT 1 FROM fallback_call_sessions')) return { rows: [] }
      if (text.startsWith('INSERT INTO fallback_call_sessions')) {
        const s = { id: params[0], owner_user_id: params[1], context_type: params[2], context_id: params[3], request_hash: params[4], state: 'ringing', expires_at: new Date(Date.now() + 45_000).toISOString() }
        sessions.set(s.id, s)
        return { rows: [s] }
      }
      if (text.startsWith('INSERT INTO fallback_call_invitees')) {
        const accepted = text.includes("'owner','accepted'") || text.includes("'member','accepted'")
        const existing = invitees.get(key(params[0], params[1]))
        invitees.set(key(params[0], params[1]), {
          role: existing?.role || (text.includes("'owner'") ? 'owner' : 'member'),
          state: accepted ? 'accepted' : 'invited',
          answer_session_id: accepted ? (existing?.state === 'accepted' ? existing.answer_session_id : params[2]) : null,
          updated_at: new Date().toISOString(),
        })
        return { rows: [] }
      }
      if (text.startsWith('SELECT id::text') && text.includes('FROM fallback_call_sessions')) return { rows: sessions.has(params[0]) ? [{ ...sessions.get(params[0]) }] : [] }
      if (text.startsWith('SELECT COUNT(*)::int')) return { rows: [{ count: 1 }] }
      if (text.startsWith('SELECT user_id FROM fallback_call_invitees')) return { rows: [] }
      if ((text.startsWith('SELECT state') || text.startsWith('SELECT role,state')) && text.includes('FROM fallback_call_invitees')) {
        return { rows: invitees.has(key(params[0], params[1])) ? [{ ...invitees.get(key(params[0], params[1])) }] : [] }
      }
      if (text.startsWith('UPDATE fallback_call_sessions')) {
        const s = sessions.get(params[0])
        s.state = text.includes("state='ended'") ? 'ended' : 'active'
        s.expires_at = new Date(Date.now() + 120_000).toISOString()
        return { rows: [{ expires_at: s.expires_at }] }
      }
      if (text.startsWith('UPDATE fallback_call_invitees')) {
        invitees.get(key(params[0], params[1])).state = params[2]
        return { rows: [] }
      }
      throw new Error(`UNEXPECTED_SQL:${text}`)
    },
    async connect() { return { query: pool.query, release() {} } },
  }

  const handlers = new Map()
  const app = { post: (routePath, _auth, handler) => handlers.set(routePath, handler) }
  const events = []
  registerCallAccessRoutes(app, {
    pool,
    authRequired: () => {},
    getCurrentUser: async (_pool, req) => ({ id: req.auth.sub }),
    loadContext: async () => ({ memberIds: [A, B] }),
    onCallSessionResolved: async (payload) => { events.push(payload) },
  })

  const callRoute = async (routePath, req) => {
    const res = {
      statusCode: 200,
      headers: {},
      body: null,
      setHeader(k, v) { this.headers[k] = v },
      status(code) { this.statusCode = code; return this },
      json(body) { this.body = body; return this },
    }
    await handlers.get(routePath)(req, res, (err) => { throw err })
    return res
  }

  const prep = await callRoute('/v1/data/calls/prepare', {
    auth: { sub: A, sid: SID_A },
    params: {},
    body: { callId: CALL, contextType: 'dm', contextId: 'chat-1', inviteeIds: [B] },
  })
  assert.equal(prep.statusCode, 200)

  const acc = await callRoute('/v1/data/calls/:callId/accept', {
    auth: { sub: B, sid: SID_B },
    params: { callId: CALL },
    body: {},
  })
  assert.equal(acc.statusCode, 200)
  assert.equal(events.length, 1)
  assert.equal(events[0].event, 'answered_elsewhere')
  assert.equal(events[0].callId, CALL)
  assert.equal(events[0].sourceSessionId, SID_B)

  const leave = await callRoute('/v1/data/calls/:callId/leave', {
    auth: { sub: B, sid: SID_B },
    params: { callId: CALL },
    body: { action: 'end' },
  })
  assert.equal(leave.statusCode, 200)
  assert.equal(events.length, 2)
  assert.equal(events[1].event, 'handled_elsewhere')
  assert.equal(events[1].action, 'end')
})

test('DM and group/community message sends validate string types and prevent NaN media_size crashes', () => {
  const srcDir = path.join(__dirname, '..', 'src')
  const dataRoutes = fs.readFileSync(path.join(srcDir, 'data-routes.js'), 'utf8')
  const serverSource = fs.readFileSync(path.join(srcDir, 'server.js'), 'utf8')
  const matches = dataRoutes.match(/INVALID_MESSAGE_PAYLOAD/g) || []
  assert.ok(matches.length >= 2, 'Both DM and collection message send routes must reject non-string content/contentType payloads')
  assert.doesNotMatch(dataRoutes, /Number\(req\.body\?\.mediaSize \|\| 0\)/, 'mediaSize must guard against NaN instead of passing NaN to PostgreSQL BIGINT')
  assert.match(serverSource, /INVALID_NULL_BYTE/, 'API server must reject raw NUL bytes before PostgreSQL UTF-8 queries')
  assert.match(serverSource, /PAYLOAD_TOO_DEEPLY_NESTED/, 'API server must reject excessively nested JSON payloads')
})
