'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { registerPostgrestCompatRoutes } = require('../src/postgrest-compat-routes')
// Notifications are an external dependency; these route tests never contact a server.
global.fetch = async () => ({ ok: true, status: 202 })

const viewer = '11111111-1111-4111-8111-111111111111'
const publicViewer = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const owner = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
const future = '2099-01-01T00:00:00Z'

function fixture({ tables = {}, imported = {}, compat = {}, follows = [], overrides = [], deletedMoments = [], concurrentConflict = false, profiles = [{ id: owner, profile_visibility: 'everyone' }, { id: viewer, supabase_user_id: publicViewer, profile_visibility: 'everyone' }], deniedCreators = [] } = {}) {
  require('../src/data-routes').invalidateImportedDataCache()
  const writes = []
  const pool = { writes, async query(sql, params = []) {
    sql = String(sql)
    if (/^(BEGIN|COMMIT|ROLLBACK)$/.test(sql) || sql.includes('pg_advisory_xact_lock')) return { rows: [] }
    if (sql.includes('WITH candidate_ids')) return { rows: deniedCreators.map(id => ({ id })) }
    if (sql.includes('FROM fallback_users u') && sql.includes('profile_visibility')) return { rows: profiles }
    if (sql.includes('information_schema.tables')) return { rows: [] }
    if (sql.includes(' AS local_user_id')) return { rows: [{ local_user_id: viewer, public_user_id: publicViewer, display_name: 'Test viewer' }] }
    if (sql.includes('fallback_profile_moderation') || sql.includes('fallback_user_blocks')) return { rows: [] }
    if (sql.includes('to_regclass')) {
      const name = params[0] || ''
      const present = name.startsWith('supabase_import.') ? imported[name.split('.')[1]] : tables[name.replace('public.fallback_', '')]
      return { rows: present ? [{ oid: name }] : [] }
    }
    if (sql.includes('FROM fallback_follow_overrides')) return { rows: overrides }
    if (sql.includes('FROM fallback_moment_deletions')) return { rows: deletedMoments.map(moment_id => ({ moment_id })) }
    if (sql.includes('FROM fallback_follows')) return { rows: follows }
    const importedName = sql.match(/FROM "supabase_import"\."([a-z_]+)"/)
    if (importedName) return { rows: (imported[importedName[1]] || []).filter(row => !sql.includes('WHERE') || String(row.id).toLowerCase() === params[0]).map(row => ({ row })) }
    if (sql.includes('SELECT row') && sql.includes('FROM fallback_compat_rows')) {
      const rows = compat[params[0]] || []
      return { rows: rows.filter(row => !sql.includes('lower(row_id)') || String(row.id).toLowerCase() === params[1]).map(row => ({ row })) }
    }
    if (sql.includes('INSERT INTO fallback_compat_rows')) {
      if (concurrentConflict && params.length === 4) return { rows: [], rowCount: 0 }
      writes.push({ table: params[0], id: params[1], row: params[2] })
      return { rows: [{ row: params[2] }], rowCount: 1 }
    }
    const nativeName = sql.match(/FROM (?:"public"\.)?"?fallback_([a-z_]+)"?\s/)
    if (nativeName) {
      const rows = tables[nativeName[1]] || []
      return { rows: sql.includes('to_jsonb(t)') ? rows.filter(row => String(row.id).toLowerCase() === params[0]).map(row => ({ row })) : rows }
    }
    throw new Error('Unexpected fixture SQL: ' + sql)
  } }
  pool.connect = async () => ({ query: pool.query.bind(pool), release() {} })
  return pool
}

async function request(pool, method, table, body, query = {}) {
  const routes = {}
  const app = Object.fromEntries(['get', 'head', 'post', 'patch', 'delete'].map(verb => [verb, (path, ...stack) => {
    if (!String(path).includes('rpc')) routes[verb] = stack.at(-1)
  }]))
  registerPostgrestCompatRoutes(app, { pool, authOptional() {} })
  const result = { status: 200, headers: {} }
  const res = { status(code) { result.status = code; return res }, json(value) { result.body = value; return res },
    setHeader(name, value) { result.headers[name.toLowerCase()] = String(value) }, end() { return res } }
  await routes[method]({ auth: { sub: viewer }, params: [table], body, query, headers: { prefer: 'count=exact' } }, res, error => { result.error = error })
  return result
}

for (const source of ['tables', 'imported', 'compat']) {
  test(`compatibility GET and HEAD hide private posts from followers (${source})`, async () => {
    const posts = [
      { id: 'private', user_id: owner, visibility: 'private' },
      { id: 'followers', user_id: owner, visibility: 'followers' },
      { id: 'public', user_id: owner, visibility: 'public' },
      { id: 'own', user_id: publicViewer, visibility: 'private' },
      { id: 'unknown', user_id: owner, visibility: 'future-audience' },
    ]
    const pool = fixture({ [source]: { posts }, follows: [{ follower_id: viewer, following_id: owner }] })
    const result = await request(pool, 'get', 'posts')
    assert.ifError(result.error)
    assert.deepEqual(result.body.map(row => row.id).sort(), ['followers', 'own', 'public'])
    const head = await request(pool, 'head', 'posts', undefined, { id: 'eq.private' })
    assert.ifError(head.error)
    assert.equal(head.headers['content-range'], '0-0/0')
  })
}

test('a removed imported follow does not authorize follower-only compatibility posts', async () => {
  const pool = fixture({ tables: { posts: [{ id: 'hidden', user_id: owner, visibility: 'followers' }] },
    follows: [{ follower_id: publicViewer, following_id: owner }], overrides: [{ follower_id: publicViewer, following_id: owner, active: false }] })
  const result = await request(pool, 'get', 'posts')
  assert.ifError(result.error)
  assert.deepEqual(result.body, [])
})

test('compatibility moments enforce selected/excluded audiences, private state, deletion and expiry', async () => {
  const moments = [
    { id: 'public', user_id: owner, visibility: 'all', expires_at: future },
    { id: 'private', user_id: owner, visibility: 'private', expires_at: future },
    { id: 'silent', user_id: owner, visibility_type: 'silent', expires_at: future },
    { id: 'unknown-audience', user_id: owner, visibility: 'unknown', expires_at: future },
    { id: 'legacy-silent', user_id: owner, visibility: 'silent', expires_at: future },
    { id: 'selected-out', user_id: owner, visibility: 'only_selected', audience_ids: ['someone-else'], expires_at: future },
    { id: 'selected-in', user_id: owner, visibility: 'only_selected', audience_ids: [publicViewer], expires_at: future },
    { id: 'excluded', user_id: owner, visibility: 'except_selected', excluded_ids: [viewer], expires_at: future },
    { id: 'expired', user_id: owner, visibility: 'all', expires_at: '2000-01-01T00:00:00Z' },
    { id: 'invalid-expiry', user_id: owner, visibility: 'all', expires_at: 'not-a-date' },
    { id: 'deleted', user_id: owner, visibility: 'all', expires_at: future },
    { id: 'own', user_id: viewer, visibility: 'only_me', expires_at: future },
  ]
  const pool = fixture({ tables: { moments }, deletedMoments: ['deleted'] })
  const result = await request(pool, 'get', 'moments')
  assert.ifError(result.error)
  assert.deepEqual(result.body.map(row => row.id).sort(), ['own', 'public', 'selected-in'])
})

for (const source of ['tables', 'imported', 'compat']) {
  test(`supplied ID cannot overwrite a foreign ${source} projection`, async () => {
    const id = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'
    const pool = fixture({ [source]: { posts: [{ id, user_id: owner, content: 'Original', visibility: 'private' }] } })
    const result = await request(pool, 'post', 'posts', { id, user_id: publicViewer, content: 'Replacement' })
    assert.equal(result.error?.status, 403)
    assert.equal(pool.writes.length, 0)
  })
}

module.exports = { fixture, request, viewer, publicViewer, owner }

for (const followActive of [true, false]) {
test(`native profile respects post audience and current follow state (${followActive})`, async () => {
  const { registerDataRoutes, invalidateImportedDataCache } = require('../src/data-routes')
  invalidateImportedDataCache()
  const handlers = new Map()
  const app = Object.fromEntries(['get', 'head', 'post', 'put', 'patch', 'delete', 'use'].map(verb => [verb, (path, ...stack) => handlers.set(`${verb} ${path}`, stack.at(-1))]))
  const posts = ['public', 'followers', 'private', 'unknown'].map(visibility => ({ id: visibility, user_id: owner, visibility, created_at: '2026-01-01T00:00:00Z' }))
  const pool = { async query(sql) {
    if (sql.includes('FROM fallback_users') && sql.includes('WHERE id = $1')) return { rows: [{ id: viewer, supabase_user_id: publicViewer }] }
    if (sql.includes('FROM fallback_users u') && sql.includes('profile_visibility')) return { rows: [{ id: owner, display_name: 'Owner', profile_visibility: 'everyone' }] }
    if (sql.includes('FROM fallback_follow_overrides')) return { rows: [{ follower_id: publicViewer, following_id: owner, active: followActive }] }
    if (sql.includes('FROM fallback_follows')) return { rows: [{ follower_id: publicViewer, following_id: owner }] }
    if (sql.includes('FROM fallback_posts')) return { rows: posts }
    if (sql.includes('to_regclass') || sql.includes('information_schema.tables') || sql.includes('fallback_follow_overrides') || sql.includes('fallback_post_reactions') || sql.includes('fallback_post_comments')) return { rows: [] }
    throw new Error('Unexpected profile SQL: ' + sql)
  } }
  registerDataRoutes(app, { pool, authRequired() {} })
  const result = { headers: {} }
  const res = { status(value) { result.status = value; return res }, json(body) { result.body = body; return res }, setHeader(key, value) { result.headers[key] = value } }
  await handlers.get('get /v1/data/profiles/:profileId')({ auth: { sub: viewer }, params: { profileId: owner }, query: {} }, res, error => { throw error })
  assert.deepEqual(result.body.posts.map(post => post.id).sort(), followActive ? ['followers', 'public'] : ['public'])
  assert.equal(result.body.stats.posts, followActive ? 2 : 1)
})
}

for (const source of ['tables', 'imported', 'compat']) {
  test(`an owner may update their existing ${source} projection`, async () => {
    const id = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'
    const pool = fixture({ [source]: { posts: [{ id, user_id: publicViewer, content: 'Original', visibility: 'private' }] } })
    const result = await request(pool, 'post', 'posts', { id, user_id: publicViewer, content: 'Updated' })
    assert.ifError(result.error)
    assert.equal(result.body[0].content, 'Updated')
    assert.equal(pool.writes[0].row.user_id, publicViewer)
  })
}

test('a conflicting write after authorization is not reported as a successful upsert', async () => {
  const pool = fixture({ concurrentConflict: true })
  const result = await request(pool, 'post', 'posts', { id: 'new-post', content: 'Text' })
  assert.equal(result.error?.status, 409)
  assert.equal(pool.writes.length, 0)
})

for (const table of ['post_comments', 'post_likes', 'post_reactions']) {
  test(`${table} cannot reveal content or engagement on an unreadable post`, async () => {
    const pool = fixture({ tables: { posts: [
      { id: 'hidden', user_id: owner, visibility: 'private' },
      { id: 'visible', user_id: owner, visibility: 'public' },
    ] }, compat: { [table]: [
      { id: 'hidden-child', post_id: 'hidden', user_id: owner, content: 'Private reply' },
      { id: 'visible-child', post_id: 'visible', user_id: owner, content: 'Public reply' },
    ] } })
    const result = await request(pool, 'get', table)
    assert.ifError(result.error)
    assert.deepEqual(result.body.map(row => row.id), ['visible-child'])
  })
}

test('a repeated authorized profile visit refreshes only the same viewer/profile pair', async () => {
  const id = `profile_id:${owner}|viewer_id:${publicViewer}`
  const previous = { id, profile_id: owner, viewer_id: publicViewer, viewed_at: '2000-01-01T00:00:00Z', is_deleted: false }
  const pool = fixture({ compat: {
    profiles: [{ id: publicViewer, profile_view_history_enabled: true }, { id: owner, profile_view_history_enabled: true }],
    profile_views: [previous],
  } })
  const result = await request(pool, 'post', 'profile_views', { profile_id: owner })
  assert.ifError(result.error)
  assert.equal(result.body[0].viewer_id, publicViewer)
  assert.equal(result.body[0].profile_id, owner)
  assert(Date.parse(result.body[0].viewed_at) > Date.parse(previous.viewed_at))
})

for (const patch of [{ id: 'victim' }, { user_id: owner }, { owner_id: owner }, { userId: owner }]) {
  test(`PATCH cannot rekey or transfer an owned post: ${JSON.stringify(patch)}`, async () => {
    const pool = fixture({ compat: { posts: [
      { id: 'owned', user_id: publicViewer, content: 'Mine', visibility: 'private' },
      { id: 'victim', user_id: owner, content: 'Theirs', visibility: 'public' },
    ] } })
    const result = await request(pool, 'patch', 'posts', patch, { id: 'eq.owned' })
    assert.equal(result.error?.status, 403)
    assert.equal(pool.writes.length, 0)
  })
}

test('PATCH keeps legitimate owned content edits on the original resource', async () => {
  const pool = fixture({ compat: { posts: [{ id: 'owned', user_id: publicViewer, content: 'Before', visibility: 'private' }] } })
  const result = await request(pool, 'patch', 'posts', { content: 'After' }, { id: 'eq.owned' })
  assert.ifError(result.error)
  assert.equal(result.body[0].content, 'After')
  assert.equal(pool.writes[0].id, 'owned')
  assert.equal(pool.writes[0].row.user_id, publicViewer)
})

for (const [label, profile, denied] of [
  ['private', { id: owner, is_private: true }, false],
  ['followers-only', { id: owner, profile_visibility: 'followers' }, false],
  ['hidden', { id: owner, break_mode: 'hidden' }, false],
  ['missing', null, false],
  ['blocked-or-suspended-or-deleted', { id: owner }, true],
]) {
  test(`compatibility GET/HEAD and child reads honor ${label} creator policy`, async () => {
    const pool = fixture({ profiles: profile ? [profile] : [], deniedCreators: denied ? [owner] : [],
      tables: { posts: [{ id: 'post', user_id: owner, visibility: 'public' }] },
      compat: { post_comments: [{ id: 'comment', post_id: 'post', content: 'Hidden content' }] },
    })
    const get = await request(pool, 'get', 'posts')
    assert.ifError(get.error)
    assert.deepEqual(get.body, [])
    const head = await request(pool, 'head', 'posts')
    assert.ifError(head.error)
    assert.equal(head.headers['content-range'], '0-0/0')
    const comments = await request(pool, 'get', 'post_comments')
    assert.ifError(comments.error)
    assert.deepEqual(comments.body, [])
  })
}

test('an owner can update a native post stored under their local identity alias', async () => {
  const pool = fixture({ tables: { posts: [{ id: 'local-owned', user_id: viewer, content: 'Before', visibility: 'private' }] } })
  const result = await request(pool, 'post', 'posts', { id: 'local-owned', user_id: viewer, content: 'After' })
  assert.ifError(result.error)
  assert.equal(result.body[0].content, 'After')
  assert.equal(result.body[0].user_id, publicViewer)
})

for (const field of ['host_user_id', 'hostUserId']) {
  test(`PATCH cannot transfer a LIVE host through ${field}`, async () => {
    const pool = fixture({ tables: { live_streams: [{ id: 'owned-live', host_user_id: publicViewer }] } })
    const result = await request(pool, 'patch', 'live_streams', { [field]: owner }, { id: 'eq.owned-live' })
    assert.equal(result.error?.status, 403)
    assert.equal(pool.writes.length, 0)
  })
}

test('owned native voice-room retries accept the server-derived owner alias', async () => {
  const pool = fixture({ tables: { voice_rooms: [{ id: 'owned-room', creator_id: publicViewer, name: 'Before' }] } })
  const result = await request(pool, 'post', 'voice_rooms', { id: 'owned-room', name: 'After' })
  assert.ifError(result.error)
  assert.equal(result.body[0].owner_id, publicViewer)
  assert.equal(result.body[0].name, 'After')
})

for (const visibility of ['chats', ' CHATS ']) {
  test(`native direct-chat peers retain chats-only moment access (${visibility})`, async () => {
    const pool = fixture({ tables: {
      moments: [{ id: 'moment', user_id: owner, visibility, expires_at: future }],
      chats: [{ id: 'chat', participants: [publicViewer, owner], is_group: false }],
    } })
    const result = await request(pool, 'get', 'moments')
    assert.ifError(result.error)
    assert.deepEqual(result.body.map(row => row.id), ['moment'])
  })
}

test('native group-message retries preserve the authorized room projection', async () => {
  const pool = fixture({ tables: {
    room_messages: [{ id: 'message', room_id: 'room', room_kind: 'group', sender_id: publicViewer, content: 'Before', created_at: '2026-01-01T00:00:00Z' }],
    collection_rooms: [{ id: 'room', room_kind: 'group', creator_id: publicViewer, is_deleted: false }],
  } })
  const result = await request(pool, 'post', 'group_messages', { id: 'message', group_id: 'room', content: 'After' })
  assert.ifError(result.error)
  assert.equal(result.body[0].content, 'After')
  assert.equal(result.body[0].group_id, 'room')
})

test('native voice-room retries preserve ownership across local/public account aliases', async () => {
  const pool = fixture({ tables: { voice_rooms: [{ id: 'alias-room', creator_id: viewer, name: 'Before' }] } })
  const result = await request(pool, 'post', 'voice_rooms', { id: 'alias-room', name: 'After' })
  assert.ifError(result.error)
  assert.equal(result.body[0].owner_id, publicViewer)
})
