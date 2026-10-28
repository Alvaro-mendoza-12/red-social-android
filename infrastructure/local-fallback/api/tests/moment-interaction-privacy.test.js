'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { visibleMomentForInteraction, registerMomentsRoutes } = require('../src/moments-routes')
const id = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'
const identity = { ids: new Set(['viewer']), displayUserId: 'viewer' }
const future = new Date(Date.now() + 3600_000).toISOString()

function fixture(row, { deleted = false, follows = [], chats = [] } = {}) {
  const writes = []
  return { writes, query: async sql => {
    if (sql.includes('FROM fallback_moment_deletions')) return { rows: deleted ? [{}] : [] }
    if (sql.includes('FROM fallback_moments')) return { rows: row ? [row] : [] }
    if (sql.includes('FROM fallback_follows')) return { rows: follows }
    if (sql.includes('FROM fallback_chats')) {
      assert.match(sql, /ANY\(participants\)/)
      assert.match(sql, /participants &&/)
      return { rows: chats }
    }
    if (sql.includes('to_regclass') || sql.includes('supabase_import.')) return { rows: [] }
    if (/INSERT|UPDATE|DELETE/.test(sql)) { writes.push(sql); return { rows: [] } }
    throw Error('Unexpected fixture query')
  } }
}

test('moment interactions hide private, silent, deleted, expired and invalid IDs', async () => {
  for (const visibility of ['private', 'silent', 'only_me']) {
    assert.equal(await visibleMomentForInteraction(fixture({ id, user_id: 'owner', visibility, visibility_type: visibility, expires_at: future }), identity, id), null)
  }
  for (const expiry of ['invalid-date', new Date(1).toISOString()]) {
    assert.equal(await visibleMomentForInteraction(fixture({ id, user_id: 'viewer', expires_at: expiry }), identity, id), null)
  }
  assert.equal(await visibleMomentForInteraction(fixture({ id, user_id: 'owner', expires_at: future }, { deleted: true }), identity, id), null)
  assert.equal(await visibleMomentForInteraction(fixture(null), identity, id), null)
  assert.equal(await visibleMomentForInteraction(fixture(null), identity, 'invalid'), null)
})

test('moment interactions keep owner, selected, followers and direct-chat audiences', async () => {
  const owner = { id, user_id: 'viewer', visibility_type: 'private', expires_at: future }
  assert.equal(await visibleMomentForInteraction(fixture(owner), identity, id), owner)
  const selected = { id, user_id: 'owner', visibility: 'only_selected', audience_ids: ['viewer'], expires_at: future }
  assert.equal(await visibleMomentForInteraction(fixture(selected), identity, id), selected)
  assert.equal(await visibleMomentForInteraction(fixture({ ...selected, audience_ids: ['another'] }), identity, id), null)
  const followers = { ...selected, visibility: 'followers' }
  assert.equal(await visibleMomentForInteraction(fixture(followers, { follows: [{ follower_id: 'viewer', following_id: 'owner' }] }), identity, id), followers)
  assert.equal(await visibleMomentForInteraction(fixture(followers), identity, id), null)
  const chats = { ...selected, visibility: 'chats' }
  assert.equal(await visibleMomentForInteraction(fixture(chats, { chats: [{ participants: ['viewer', 'owner'], is_group: false }] }), identity, id), chats)
})

test('private moment view and reaction routes never write or notify an outsider', async () => {
  const handlers = new Map()
  const app = Object.fromEntries(['get', 'post', 'put', 'patch', 'delete'].map(verb => [verb, (path, ...stack) => handlers.set(`${verb} ${path}`, stack.at(-1))]))
  const pool = fixture({ id, user_id: 'owner', visibility: 'private', visibility_type: 'private', expires_at: future })
  let notices = 0
  registerMomentsRoutes(app, { pool, authRequired: () => {}, getCurrentUser: async () => ({ id: 'viewer' }),
    userIdentity: () => identity, createLocalNotification: async () => { notices++ },
  })
  for (const route of ['view', 'reaction']) {
    const result = { status: 200 }
    const res = { status: value => { result.status = value; return res }, json: body => { result.body = body; return res } }
    await handlers.get(`post /v1/data/moments/:momentId/${route}`)({ params: { momentId: id } }, res, error => { throw error })
    assert.equal(result.status, 404)
    assert.deepEqual(result.body, { error: 'MOMENT_NOT_FOUND' })
  }
  assert.equal(pool.writes.length, 0)
  assert.equal(notices, 0)
})

test('a public moment view is recorded without creating an activity notification', async () => {
  const handlers = new Map()
  const app = Object.fromEntries(['get', 'post', 'put', 'patch', 'delete']
    .map(verb => [verb, (path, ...stack) => handlers.set(`${verb} ${path}`, stack.at(-1))]))
  const pool = fixture({ id, user_id: 'owner', visibility: 'public', expires_at: future })
  let notices = 0
  registerMomentsRoutes(app, { pool, authRequired: () => {},
    getCurrentUser: async () => ({ id: 'viewer' }), userIdentity: () => identity,
    createLocalNotification: async () => { notices++ },
  })
  const result = {}
  const res = { json: body => { result.body = body; return res },
    status: value => { result.status = value; return res } }
  await handlers.get('post /v1/data/moments/:momentId/view')(
    { params: { momentId: id } }, res, error => { throw error })
  assert.deepEqual(result.body, { ok: true })
  assert.equal(pool.writes.length, 1)
  assert.match(pool.writes[0], /INSERT INTO fallback_moment_views/)
  assert.equal(notices, 0)
})

test('a new moment like notifies its owner once and a retried POST stays idempotent', async () => {
  const handlers = new Map(), notices = []
  const app = Object.fromEntries(['get', 'post', 'put', 'patch', 'delete'].map(verb => [verb, (path, ...stack) => handlers.set(`${verb} ${path}`, stack.at(-1))]))
  const pool = fixture({ id, user_id: 'owner', visibility: 'public', expires_at: future })
  const query = pool.query
  let inserted = false
  pool.query = async (sql, parameters) => {
    if (sql.includes('INSERT INTO fallback_moment_reactions')) {
      assert.match(sql, /DO NOTHING RETURNING moment_id/)
      const rows = inserted ? [] : [{ moment_id: id }]
      inserted = true
      return { rows }
    }
    return query(sql, parameters)
  }
  registerMomentsRoutes(app, { pool, authRequired: () => {}, getCurrentUser: async () => ({ id: 'viewer', display_name: 'Persona' }),
    userIdentity: () => identity, createLocalNotification: async (_pool, input) => { notices.push(input) },
  })
  const handler = handlers.get('post /v1/data/moments/:momentId/reaction')
  const results = []
  const res = { json: value => { results.push(value); return res } }
  for (let attempt = 0; attempt < 2; attempt++) await handler({ params: { momentId: id } }, res, error => { throw error })
  assert.deepEqual(results, [{ ok: true, liked: true }, { ok: true, liked: true }])
  assert.equal(notices.length, 1)
  assert.equal(notices[0].type, 'like_moment')
  assert.equal(notices[0].recipientId, 'owner')
  assert.equal(notices[0].actorName, 'Persona')
  assert.equal(notices[0].targetId, id)
  assert.equal(notices[0].extraData.expires_at, future)
})

test('a self-like does not notify and notification failure does not undo a persisted like', async () => {
  for (const owner of ['viewer', 'owner']) {
    const handlers = new Map()
    const app = Object.fromEntries(['get', 'post', 'put', 'patch', 'delete'].map(verb => [verb, (path, ...stack) => handlers.set(`${verb} ${path}`, stack.at(-1))]))
    const pool = fixture({ id, user_id: owner, visibility: 'public', expires_at: future })
    const query = pool.query
    pool.query = async (sql, parameters) => sql.includes('INSERT INTO fallback_moment_reactions') ? { rows: [{ moment_id: id }] } : query(sql, parameters)
    let notices = 0, result
    registerMomentsRoutes(app, { pool, authRequired: () => {}, getCurrentUser: async () => ({ id: 'viewer' }), userIdentity: () => identity,
      createLocalNotification: async () => { notices++; throw Object.assign(new Error('Delivery unavailable'), { code: 'TEST_DELIVERY_UNAVAILABLE' }) },
    })
    await handlers.get('post /v1/data/moments/:momentId/reaction')({ params: { momentId: id } }, { json: value => { result = value } }, error => { throw error })
    assert.deepEqual(result, { ok: true, liked: true })
    assert.equal(notices, owner === 'viewer' ? 0 : 1)
  }
})
