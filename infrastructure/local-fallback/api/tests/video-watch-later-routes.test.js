const assert = require('node:assert/strict')
const { test } = require('node:test')
const { registerVideoWatchLaterRoutes } = require('../src/video-watch-later-routes')

const videoId = '11111111-1111-4111-8111-111111111111'
const privateId = '22222222-2222-4222-8222-222222222222'
const deletedId = '33333333-3333-4333-8333-333333333333'

function response() {
  return { status(code) { this.statusCode = code; return this },
    json(body) { this.body = body; return this }, setHeader() {} }
}

function handlers(overrides = {}) {
  const routes = new Map()
  const app = Object.fromEntries(['get', 'put', 'delete'].map((method) => [method,
    (path, _auth, handler) => routes.set(`${method} ${path}`, handler)]))
  registerVideoWatchLaterRoutes(app, {
    pool: { query: async () => ({ rows: [] }) }, authRequired: () => {},
    getCurrentUser: async () => ({ id: 'viewer' }),
    userIdentity: () => ({ displayUserId: 'viewer', ids: new Set(['viewer']) }),
    videoRowsByIds: async () => [], localVideoDeletionIds: async () => new Set(),
    visibleVideoForIdentity: async () => ({ id: videoId }), ...overrides,
    publishChange: async () => {},
  })
  return routes
}

test('watch-later mutations are private and reject invisible videos', async () => {
  const queries = []
  const routes = handlers({
    pool: { query: async (sql, params) => { queries.push({ sql, params }); return { rows: [] } } },
    visibleVideoForIdentity: async (_pool, id) => id === videoId ? { id } : null,
  })
  const save = response()
  await routes.get('put /v1/data/videos/watch-later/:videoId')({ params: { videoId } }, save, assert.fail)
  assert.equal(save.body.saved, true)
  assert.deepEqual(queries[0].params, ['viewer', videoId])
  const denied = response()
  await routes.get('put /v1/data/videos/watch-later/:videoId')(
    { params: { videoId: privateId } }, denied, assert.fail)
  assert.equal(denied.statusCode, 404)
  assert.equal(queries.length, 1)
  const remove = response()
  await routes.get('delete /v1/data/videos/watch-later/:videoId')(
    { params: { videoId } }, remove, assert.fail)
  assert.deepEqual(queries[1].params, ['viewer', videoId])
})

test('watch-later list keeps unavailable entries without exposing private metadata', async () => {
  const saved = [videoId, privateId, deletedId].map((id) =>
    ({ video_id: id, added_at: '2026-09-24T10:00:00Z' }))
  const routes = handlers({
    pool: { query: async (_sql, params) => {
      assert.equal(params[0], 'viewer')
      return { rows: saved }
    } },
    videoRowsByIds: async () => [
      { id: videoId, title: 'Visible', is_public: true },
      { id: privateId, title: 'Secreto', is_public: false, user_id: 'other' },
      { id: deletedId, title: 'Eliminado', is_public: true },
    ],
    localVideoDeletionIds: async () => new Set([deletedId]),
  })
  const res = response()
  await routes.get('get /v1/data/videos/watch-later/list')({ query: {} }, res, assert.fail)
  assert.equal(res.body.items[0].title, 'Visible')
  assert.deepEqual(res.body.items[1], { videoId: privateId,
    addedAt: '2026-09-24T10:00:00Z', unavailable: true })
  assert.equal(res.body.items[2].unavailable, true)
  assert.equal(JSON.stringify(res.body).includes('Secreto'), false)
})

test('watch-later list validates cursor before querying', async () => {
  const routes = handlers()
  const res = response()
  await routes.get('get /v1/data/videos/watch-later/list')(
    { query: { cursor: 'garbage' } }, res, assert.fail)
  assert.equal(res.statusCode, 400)
  assert.equal(res.body.error, 'INVALID_WATCH_LATER_CURSOR')
})

test('watch-later supports oldest-first cursor ordering without mixing sorts', async () => {
  const queries = []
  const rows = Array.from({ length: 51 }, (_, index) => ({ video_id: videoId,
    added_at: new Date(Date.UTC(2026, 8, 1, 0, 0, index)).toISOString() }))
  const routes = handlers({ pool: { query: async (sql, params) => {
    queries.push({ sql, params }); return { rows }
  } } })
  const first = response()
  await routes.get('get /v1/data/videos/watch-later/list')(
    { query: { sort: 'oldest' } }, first, assert.fail)
  assert.match(queries[0].sql, /ORDER BY added_at ASC, video_id ASC/)
  assert.equal(JSON.parse(Buffer.from(first.body.nextCursor, 'base64url').toString('utf8')).sort, 'oldest')
  const mismatch = response()
  await routes.get('get /v1/data/videos/watch-later/list')(
    { query: { sort: 'newest', cursor: first.body.nextCursor } }, mismatch, assert.fail)
  assert.equal(mismatch.statusCode, 400)
})
