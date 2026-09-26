'use strict'

const assert = require('node:assert/strict')
const { randomUUID } = require('node:crypto')
const { Pool } = require('pg')
const { ensureVideoHistoryColumns, registerVideoHistoryRoutes } = require('../src/video-history-routes')

async function main() {
  const connectionString = process.env.VIDEO_HISTORY_TEST_DATABASE_URL
  if (!connectionString) throw new Error('Set VIDEO_HISTORY_TEST_DATABASE_URL to an isolated PostgreSQL fixture.')
  const pool = new Pool({ connectionString })
  const alice = randomUUID()
  const bob = randomUUID()
  const visibleId = randomUUID()
  const privateId = randomUUID()
  const deleted = new Set()
  const videos = new Map([
    [visibleId, { id: visibleId, title: 'Visible', is_public: true, user_id: alice }],
    [privateId, { id: privateId, title: 'Privado', is_public: false, user_id: alice }],
  ])
  const handlers = new Map()
  const app = Object.fromEntries(['get', 'put', 'delete'].map((method) => [method,
    (path, _auth, handler) => handlers.set(`${method} ${path}`, handler)]))
  try {
    await ensureVideoHistoryColumns(pool)
    registerVideoHistoryRoutes(app, {
      pool, authRequired: () => {},
      getCurrentUser: async (_pool, req) => ({ id: req.userId }),
      userIdentity: (user) => ({ ids: new Set([user.id]), displayUserId: user.id }),
      videoRowsByIds: async (_pool, ids) => ids.map((id) => videos.get(id)).filter(Boolean),
      localVideoDeletionIds: async () => deleted,
      visibleVideoForIdentity: async (_pool, id, identity) => {
        const video = videos.get(id)
        return video && !deleted.has(id) && (video.is_public || identity.ids.has(video.user_id)) ? video : null
      },
      publishChange: async () => {},
    })
    async function call(method, path, userId, videoId = '', body = {}, query = {}) {
      const res = { status(code) { this.statusCode = code; return this },
        json(value) { this.body = value; return this }, setHeader() {} }
      await handlers.get(`${method} ${path}`)({ userId, params: { videoId }, body, query },
        res, (error) => { throw error })
      return res
    }
    const itemPath = '/v1/data/videos/history/:videoId'
    const listPath = '/v1/data/videos/history/list'
    const clearPath = '/v1/data/videos/history'
    assert.equal((await call('put', itemPath, alice, visibleId,
      { positionMs: 4200, durationMs: 10000 })).body.positionMs, 4200)
    assert.equal((await call('put', itemPath, alice, privateId,
      { positionMs: 500, durationMs: 10000 })).statusCode, undefined)
    assert.equal((await call('put', itemPath, bob, privateId,
      { positionMs: 500, durationMs: 10000 })).statusCode, 404)
    assert.deepEqual((await call('get', listPath, bob)).body.items, [])
    const mine = (await call('get', listPath, alice)).body.items
    assert.equal(mine.length, 2)
    assert.equal(mine.find((item) => item.videoId === visibleId).positionMs, 4200)
    deleted.add(privateId)
    const hidden = (await call('get', listPath, alice)).body.items.find((item) => item.videoId === privateId)
    assert.deepEqual(Object.keys(hidden).sort(),
      ['durationMs', 'positionMs', 'unavailable', 'videoId', 'viewedAt'])
    assert.equal(hidden.positionMs, 0)
    assert.equal((await call('delete', itemPath, alice, privateId)).body.ok, true)
    const olderIds = Array.from({ length: 51 }, () => randomUUID())
    await pool.query(`INSERT INTO fallback_video_playback_history(user_id, video_id, position_ms, duration_ms, viewed_at)
      SELECT $1, id, 1000, 10000, NOW() - INTERVAL '1 day' FROM unnest($2::text[]) AS id`, [alice, olderIds])
    const first = (await call('get', listPath, alice)).body
    const second = (await call('get', listPath, alice, '', {}, { cursor: first.nextCursor })).body
    assert.equal(first.items.length, 50)
    assert.equal(second.items.length, 2)
    assert.equal(second.nextCursor, null)
    assert.equal(new Set([...first.items, ...second.items].map((item) => item.videoId)).size, 52)
    await call('delete', clearPath, alice)
    assert.deepEqual((await call('get', listPath, alice)).body.items, [])
    assert.deepEqual((await call('get', listPath, bob)).body.items, [])
    console.log('PASS: playback history privacy, position, deleted videos, 50-item pagination and clear')
  } finally {
    await pool.query('DELETE FROM fallback_video_playback_history WHERE user_id = ANY($1::text[])', [[alice, bob]])
    await pool.end()
  }
}

main().catch((error) => { console.error(error); process.exitCode = 1 })
