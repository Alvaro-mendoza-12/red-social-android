'use strict'

const assert = require('node:assert/strict')
const { test } = require('node:test')
const { collectionName, registerVideoCollectionRoutes } = require('../src/video-collections-routes')

const collectionId = '11111111-1111-4111-8111-111111111111'

function setup() {
  const handlers = new Map()
  const app = Object.fromEntries(['get', 'put', 'post', 'patch', 'delete'].map((method) => [method,
    (path, _auth, handler) => handlers.set(`${method} ${path}`, handler)]))
  const pool = { query: async () => { throw new Error('Unexpected database call') },
    connect: async () => { throw new Error('Unexpected transaction') } }
  registerVideoCollectionRoutes(app, {
    pool, authRequired: () => {}, getCurrentUser: async () => ({ id: 'viewer' }),
    userIdentity: () => ({ ids: new Set(['viewer']), displayUserId: 'viewer' }),
    videoRowsByIds: async () => [], localVideoDeletionIds: async () => new Set(),
    visibleVideoForIdentity: async () => null, publishChange: async () => {},
  })
  return handlers
}

async function call(handler, req) {
  const response = { status(code) { this.statusCode = code; return this },
    json(body) { this.body = body; return this } }
  await handler(req, response, (error) => { throw error })
  return response
}

test('collection names are bounded and reject control characters', () => {
  assert.equal(collectionName('  Recetas  '), 'Recetas')
  assert.equal(collectionName('  '), null)
  assert.equal(collectionName('a'.repeat(61)), null)
  assert.equal(collectionName('A\nB'), null)
})

test('invalid create, collection id and reorder stop before any database mutation', async () => {
  const handlers = setup()
  const badName = await call(handlers.get('post /v1/data/videos/collections'),
    { body: { name: '\u0000' } })
  assert.equal(badName.statusCode, 400)
  const badId = await call(handlers.get('delete /v1/data/videos/collections/:collectionId'),
    { params: { collectionId: 'not-an-id' } })
  assert.equal(badId.statusCode, 400)
  const duplicateOrder = await call(handlers.get('post /v1/data/videos/collections/:collectionId/items/reorder'),
    { params: { collectionId }, body: { orderedVideoIds: [collectionId, collectionId] } })
  assert.equal(duplicateOrder.statusCode, 400)
})
