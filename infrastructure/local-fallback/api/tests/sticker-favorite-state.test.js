const test = require('node:test')
const assert = require('node:assert/strict')
const { _test } = require('../src/postgrest-compat-routes')

test('explicit sticker removal stays removed when the request is repeated', () => {
  assert.equal(_test.desiredStickerFavoriteState({ id: 'saved' }, false), false)
  assert.equal(_test.desiredStickerFavoriteState(null, false), false)
})

test('explicit sticker add stays saved when the request is repeated', () => {
  assert.equal(_test.desiredStickerFavoriteState(null, true), true)
  assert.equal(_test.desiredStickerFavoriteState({ id: 'saved' }, true), true)
})

test('older clients retain toggle behavior', () => {
  assert.equal(_test.desiredStickerFavoriteState(null, undefined), true)
  assert.equal(_test.desiredStickerFavoriteState({ id: 'saved' }, undefined), false)
})
