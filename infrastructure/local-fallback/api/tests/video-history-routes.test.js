const assert = require('node:assert/strict')
const { test } = require('node:test')
const { registerVideoHistoryRoutes } = require('../src/video-history-routes')

const videoId = '11111111-1111-4111-8111-111111111111'

function response() {
  return { status(code) { this.statusCode = code; return this },
    json(body) { this.body = body; return this }, setHeader() {} }
}

function routes(overrides = {}) {
  const handlers = new Map()
  const app = Object.fromEntries(['get', 'put', 'delete'].map((method) => [method,
    (path, _auth, handler) => handlers.set(`${method} ${path}`, handler)]))
  registerVideoHistoryRoutes(app, {
    pool: { query: async () => ({ rows: [] }) }, authRequired: () => {},
    getCurrentUser: async () => ({ id: 'me' }),
    userIdentity: () => ({ ids: new Set(['me']), displayUserId: 'me' }),
    videoRowsByIds: async () => [], localVideoDeletionIds: async () => new Set(),
    visibleVideoForIdentity: async () => ({ id: videoId }), publishChange: async () => {},
    ...overrides,
  })
  return handlers
}

test('history rejects invalid positions and videos the viewer cannot open', async () => {
  let writes = 0
  const handlers = routes({
    pool: { query: async () => { writes++; return { rows: [] } } },
    visibleVideoForIdentity: async () => null,
  })
  const invalid = response()
  await handlers.get('put /v1/data/videos/history/:videoId')({ params: { videoId },
    body: { positionMs: 5001, durationMs: 5000 } }, invalid, assert.fail)
  assert.equal(invalid.statusCode, 400)
  const invisible = response()
  await handlers.get('put /v1/data/videos/history/:videoId')({ params: { videoId },
    body: { positionMs: 2500, durationMs: 5000 } }, invisible, assert.fail)
  assert.equal(invisible.statusCode, 404)
  assert.equal(writes, 0)
})

test('history writes and deletes only the current account playback record', async () => {
  const queries = []
  const handlers = routes({ pool: { query: async (sql, params) => {
    queries.push({ sql, params }); return { rows: [] }
  } } })
  await handlers.get('put /v1/data/videos/history/:videoId')({ params: { videoId },
    body: { positionMs: 2500, durationMs: 5000 } }, response(), assert.fail)
  assert.deepEqual(queries[0].params, [videoId, 'me', 2500, 5000])
  assert.match(queries[0].sql, /fallback_video_playback_history/)
  await handlers.get('delete /v1/data/videos/history/:videoId')(
    { params: { videoId } }, response(), assert.fail)
  assert.deepEqual(queries[1].params, ['me', videoId])
  await handlers.get('delete /v1/data/videos/history')({}, response(), assert.fail)
  assert.deepEqual(queries[2].params, ['me'])
})

test('history hides deleted and private video metadata and validates cursor', async () => {
  const hiddenId = '22222222-2222-4222-8222-222222222222'
  const handlers = routes({
    pool: { query: async () => ({ rows: [
      { video_id: videoId, position_ms: 1000, duration_ms: 5000,
        viewed_at: '2026-09-24T12:00:00Z' },
      { video_id: hiddenId, position_ms: 900, duration_ms: 5000,
        viewed_at: '2026-09-24T11:00:00Z' },
    ] }) },
    videoRowsByIds: async () => [{ id: videoId, is_public: true, title: 'Visible' },
      { id: hiddenId, is_public: false, user_id: 'other', title: 'Secreto' }],
  })
  const res = response()
  await handlers.get('get /v1/data/videos/history/list')({ query: {} }, res, assert.fail)
  assert.equal(res.body.items[0].positionMs, 1000)
  assert.equal(res.body.items[1].unavailable, true)
  assert.equal(res.body.items[1].positionMs, 0)
  assert.equal(JSON.stringify(res.body).includes('Secreto'), false)
  const invalid = response()
  await handlers.get('get /v1/data/videos/history/list')(
    { query: { cursor: 'garbage' } }, invalid, assert.fail)
  assert.equal(invalid.statusCode, 400)
})
