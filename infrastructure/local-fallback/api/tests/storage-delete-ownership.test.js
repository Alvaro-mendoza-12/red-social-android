const test = require('node:test')
const assert = require('node:assert/strict')
const express = require('express')
const { registerStorageRoutes } = require('../src/storage-routes')

test('an authenticated user cannot delete a legacy storage object without an owner', async (t) => {
  let deletionQueries = 0
  const pool = {
    async query(sql) {
      if (sql.includes('SELECT *') && sql.includes('FROM fallback_storage_objects')) {
        return { rows: [{ bucket_id: 'moment-media', name: 'legacy/video.mp4', owner_id: null }] }
      }
      if (sql.includes('DELETE FROM fallback_storage_objects')) deletionQueries += 1
      return { rows: [] }
    },
  }
  const app = express()
  registerStorageRoutes(app, {
    pool,
    authRequired(req, _res, next) {
      req.auth = { sub: '11111111-1111-4111-8111-111111111111' }
      next()
    },
  })
  app.use((error, _req, res, _next) => res.status(500).json({ error: error.message }))
  const server = await new Promise((resolve) => {
    const listening = app.listen(0, '127.0.0.1', () => resolve(listening))
  })
  t.after(() => new Promise((resolve) => server.close(resolve)))

  const response = await fetch(
    `http://127.0.0.1:${server.address().port}/v1/storage/objects/moment-media/legacy/video.mp4`,
    { method: 'DELETE' },
  )
  assert.equal(response.status, 403)
  assert.equal((await response.json()).error, 'FORBIDDEN')
  assert.equal(deletionQueries, 0)
})

test('published video and thumbnail objects cannot be deleted through generic storage', async (t) => {
  const userId = '11111111-1111-4111-8111-111111111111'
  let deletionQueries = 0
  const pool = {
    async query(sql, params = []) {
      if (sql.includes('SELECT *') && sql.includes('FROM fallback_storage_objects')) {
        return { rows: [{ bucket_id: params[0], name: params[1], owner_id: userId }] }
      }
      if (sql.includes('FROM fallback_videos')) {
        assert.match(params[0], /\/storage\/v1\/object\/public\/(videos|thumbnails)\/user\/clip\.(mp4|jpg)$/)
        return { rows: [{ one: 1 }] }
      }
      if (sql.includes('DELETE FROM fallback_storage_objects')) deletionQueries += 1
      return { rows: [] }
    },
  }
  const app = express()
  registerStorageRoutes(app, {
    pool,
    authRequired(req, _res, next) {
      req.auth = { sub: userId }
      next()
    },
  })
  app.use((error, _req, res, _next) => res.status(500).json({ error: error.message }))
  const server = await new Promise((resolve) => {
    const listening = app.listen(0, '127.0.0.1', () => resolve(listening))
  })
  t.after(() => new Promise((resolve) => server.close(resolve)))
  for (const [bucket, objectPath] of [['videos', 'user/clip.mp4'], ['thumbnails', 'user/clip.jpg']]) {
    const response = await fetch(
      `http://127.0.0.1:${server.address().port}/v1/storage/objects/${bucket}/${objectPath}`,
      { method: 'DELETE' },
    )
    assert.equal(response.status, 409)
    assert.equal((await response.json()).error, 'STORAGE_OBJECT_IN_USE')
  }
  assert.equal(deletionQueries, 0)
})
