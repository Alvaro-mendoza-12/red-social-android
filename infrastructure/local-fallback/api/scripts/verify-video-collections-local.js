'use strict'

const assert = require('node:assert/strict')
const { randomUUID } = require('node:crypto')
const { Pool } = require('pg')
const { ensureVideoCollectionTables, registerVideoCollectionRoutes } = require('../src/video-collections-routes')

async function main() {
  const connectionString = process.env.VIDEO_COLLECTION_TEST_DATABASE_URL
  if (!connectionString) throw new Error('Set VIDEO_COLLECTION_TEST_DATABASE_URL to an isolated PostgreSQL fixture.')
  const pool = new Pool({ connectionString })
  const alice = randomUUID()
  const bob = randomUUID()
  const firstVideo = randomUUID()
  const secondVideo = randomUUID()
  const privateVideo = randomUUID()
  const videos = new Map([
    [firstVideo, { id: firstVideo, title: 'Receta de pan', is_public: true, user_id: alice }],
    [secondVideo, { id: secondVideo, title: 'Viaje', is_public: true, user_id: alice }],
    [privateVideo, { id: privateVideo, title: 'Privado', is_public: false, user_id: alice }],
  ])
  const deleted = new Set()
  const handlers = new Map()
  const app = Object.fromEntries(['get', 'put', 'post', 'patch', 'delete'].map((method) => [method,
    (path, _auth, handler) => handlers.set(`${method} ${path}`, handler)]))
  try {
    await ensureVideoCollectionTables(pool)
    registerVideoCollectionRoutes(app, {
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
    async function call(method, route, userId, params = {}, body = {}, query = {}) {
      const res = { status(code) { this.statusCode = code; return this },
        json(value) { this.body = value; return this }, setHeader() {} }
      await handlers.get(`${method} ${route}`)({ userId, params, body, query },
        res, (error) => { throw error })
      return res
    }
    const base = '/v1/data/videos/collections'
    const collection = (id) => `${base}/:collectionId`
    const items = (id) => `${collection(id)}/items`
    const item = (id) => `${items(id)}/:videoId`
    const create = await call('post', base, alice, {}, { name: 'Recetas' })
    assert.equal(create.statusCode, 201)
    const id = create.body.id
    assert.equal((await call('get', base, bob)).body.items.length, 0)
    assert.equal((await call('get', items(id), bob, { collectionId: id })).statusCode, 404)
    assert.equal((await call('patch', collection(id), bob, { collectionId: id }, { name: 'Hurtado' })).statusCode, 404)
    assert.equal((await call('put', item(id), bob,
      { collectionId: id, videoId: firstVideo })).statusCode, 404)
    assert.equal((await call('put', item(id), alice,
      { collectionId: id, videoId: firstVideo })).body.added, true)
    assert.equal((await call('put', item(id), alice,
      { collectionId: id, videoId: firstVideo })).body.added, false)
    assert.equal((await call('put', item(id), alice,
      { collectionId: id, videoId: secondVideo })).body.added, true)
    assert.equal((await call('put', item(id), bob,
      { collectionId: id, videoId: privateVideo })).statusCode, 404)
    assert.equal((await call('put', item(id), alice,
      { collectionId: id, videoId: privateVideo })).body.added, true)
    const search = (await call('get', items(id), alice, { collectionId: id }, {},
      { search: 'pan' })).body
    assert.equal(search.total, 1)
    assert.equal(search.items[0].videoId, firstVideo)
    const reordered = await call('post', `${items(id)}/reorder`, alice, { collectionId: id },
      { orderedVideoIds: [privateVideo, secondVideo, firstVideo] })
    assert.equal(reordered.body.ok, true)
    let listing = (await call('get', items(id), alice, { collectionId: id })).body.items
    assert.deepEqual(listing.map((entry) => entry.videoId), [privateVideo, secondVideo, firstVideo])
    assert.equal((await call('patch', `${item(id)}/move`, alice,
      { collectionId: id, videoId: firstVideo }, { direction: 'up' })).body.moved, true)
    listing = (await call('get', items(id), alice, { collectionId: id })).body.items
    assert.deepEqual(listing.map((entry) => entry.videoId), [privateVideo, firstVideo, secondVideo])
    deleted.add(privateVideo)
    listing = (await call('get', items(id), alice, { collectionId: id })).body.items
    assert.deepEqual(Object.keys(listing[0]).sort(),
      ['addedAt', 'sortOrder', 'unavailable', 'videoId'])
    assert.equal((await call('get', items(id), alice, { collectionId: id }, {},
      { search: 'privado' })).body.total, 0)
    assert.equal((await call('patch', collection(id), alice, { collectionId: id },
      { name: 'Mis recetas' })).body.name, 'Mis recetas')
    assert.equal((await call('post', base, alice, {}, { name: 'MIS RECETAS' })).statusCode, 409)
    const fillerIds = Array.from({ length: 497 }, () => randomUUID())
    await pool.query(`INSERT INTO fallback_video_collection_items(collection_id,video_id,sort_order)
      SELECT $1, id, ordinality::int + 3 FROM unnest($2::text[]) WITH ORDINALITY AS item(id,ordinality)`,
    [id, fillerIds])
    assert.equal((await call('get', items(id), alice, { collectionId: id })).body.items.length, 50)
    assert.equal((await call('get', items(id), alice, { collectionId: id }, {},
      { offset: 450 })).body.items.length, 50)
    const extraVideo = randomUUID()
    videos.set(extraVideo, { id: extraVideo, is_public: true, title: 'Extra' })
    assert.equal((await call('put', item(id), alice,
      { collectionId: id, videoId: extraVideo })).statusCode, 409)
    assert.equal((await call('delete', item(id), alice,
      { collectionId: id, videoId: firstVideo })).body.removed, true)
    assert.equal((await call('put', item(id), alice,
      { collectionId: id, videoId: extraVideo })).body.added, true)
    assert.equal((await call('delete', collection(id), bob, { collectionId: id })).statusCode, 404)
    assert.equal((await call('delete', collection(id), alice, { collectionId: id })).body.ok, true)
    assert.equal((await call('get', base, alice)).body.items.length, 0)
    await pool.query(`INSERT INTO fallback_video_collections(id,user_id,name,sort_order)
      SELECT gen_random_uuid(), $1, 'Lista ' || n::text, n FROM generate_series(1,100) AS n`, [alice])
    assert.equal((await call('post', base, alice, {}, { name: 'Número 101' })).statusCode, 409)
    console.log('PASS: private video collections, search, order, 500-item cap and deletion')
  } finally {
    await pool.query('DELETE FROM fallback_video_collections WHERE user_id = ANY($1::text[])', [[alice, bob]])
    await pool.end()
  }
}

main().catch((error) => { console.error(error); process.exitCode = 1 })
