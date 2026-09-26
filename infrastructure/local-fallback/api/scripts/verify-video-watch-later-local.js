'use strict'

const assert = require('node:assert/strict')
const { randomUUID } = require('node:crypto')
const { Pool } = require('pg')
const { registerVideoWatchLaterRoutes } = require('../src/video-watch-later-routes')

async function main() {
  const connectionString = process.env.VIDEO_WATCH_LATER_TEST_DATABASE_URL
  if (!connectionString) throw new Error('Set VIDEO_WATCH_LATER_TEST_DATABASE_URL to an isolated PostgreSQL fixture.')
  const pool = new Pool({ connectionString })
  const alice = randomUUID()
  const bob = randomUUID()
  const publicId = randomUUID()
  const privateId = randomUUID()
  const videos = new Map([
    [publicId, { id: publicId, title: 'Visible', user_id: alice, is_public: true }],
    [privateId, { id: privateId, title: 'Privado', user_id: alice, is_public: false }],
  ])
  const deleted = new Set()
  const handlers = new Map()
  const app = Object.fromEntries(['get', 'put', 'delete'].map((method) => [method,
    (path, _auth, handler) => handlers.set(`${method} ${path}`, handler)]))
  try {
    registerVideoWatchLaterRoutes(app, {
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
    async function call(method, path, userId, videoId = '', query = {}) {
      const handler = handlers.get(`${method} ${path}`)
      const res = { status(code) { this.statusCode = code; return this },
        json(body) { this.body = body; return this }, setHeader() {} }
      await handler({ userId, params: { videoId }, query }, res, (error) => { throw error })
      return res
    }
    const itemRoute = '/v1/data/videos/watch-later/:videoId'
    const listRoute = '/v1/data/videos/watch-later/list'
    assert.equal((await call('put', itemRoute, alice, publicId)).body.saved, true)
    assert.equal((await call('put', itemRoute, alice, privateId)).body.saved, true)
    assert.equal((await call('put', itemRoute, bob, privateId)).statusCode, 404)
    assert.deepEqual((await call('get', listRoute, bob)).body.items, [])
    const mine = (await call('get', listRoute, alice)).body.items
    assert.equal(mine.length, 2)
    assert.equal(mine.find((item) => item.videoId === privateId).title, 'Privado')
    deleted.add(privateId)
    const hidden = (await call('get', listRoute, alice)).body.items.find((item) => item.videoId === privateId)
    assert.deepEqual(Object.keys(hidden).sort(), ['addedAt', 'unavailable', 'videoId'])
    assert.equal(hidden.unavailable, true)
    assert.equal((await call('delete', itemRoute, alice, privateId)).body.saved, false)
    assert.equal((await call('get', listRoute, alice)).body.items.length, 1)
    const olderIds = Array.from({ length: 51 }, () => randomUUID())
    await pool.query(`INSERT INTO fallback_video_watch_later(user_id, video_id, added_at)
      SELECT $1, id, NOW() - INTERVAL '1 day' FROM unnest($2::text[]) AS id`, [alice, olderIds])
    const firstPage = (await call('get', listRoute, alice)).body
    assert.equal(firstPage.items.length, 50)
    assert.ok(firstPage.nextCursor)
    assert.equal(JSON.parse(Buffer.from(firstPage.nextCursor, 'base64url').toString('utf8')).sort, 'newest')
    const secondPage = (await call('get', listRoute, alice, '', { cursor: firstPage.nextCursor })).body
    assert.equal(secondPage.items.length, 2)
    assert.equal(secondPage.nextCursor, null)
    assert.equal(new Set([...firstPage.items, ...secondPage.items].map((item) => item.videoId)).size, 52)
    const oldestPage = (await call('get', listRoute, alice, '', { sort: 'oldest' })).body
    assert.equal(oldestPage.items.length, 50)
    assert.ok(oldestPage.nextCursor)
    const oldestTail = (await call('get', listRoute, alice, '',
      { sort: 'oldest', cursor: oldestPage.nextCursor })).body
    assert.equal(oldestTail.items.length, 2)
    assert.equal(new Set([...oldestPage.items, ...oldestTail.items].map((item) => item.videoId)).size, 52)
    console.log('PASS: watch-later privacy, deleted videos and 50-item cursor pages in PostgreSQL')
  } finally {
    await pool.query(`DELETE FROM fallback_video_watch_later WHERE user_id = ANY($1::text[])`, [[alice, bob]])
    await pool.end()
  }
}

main().catch((error) => { console.error(error); process.exitCode = 1 })
